package software.coley.recaf.android.cdi;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.AmbiguousResolutionException;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.UnsatisfiedResolutionException;
import jakarta.enterprise.inject.se.SeContainer;
import jakarta.enterprise.inject.spi.BeanContainer;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.ObserverMethod;
import jakarta.enterprise.util.TypeLiteral;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import software.coley.recaf.cdi.EagerInitialization;
import software.coley.recaf.cdi.InitializationStage;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * A small reflective dependency-injection container implementing the subset of CDI that Recaf's core relies on.
 * <p>
 * Desktop Recaf uses Weld, which generates proxy classes as JVM bytecode at runtime. Android's runtime cannot
 * load JVM bytecode, so this container takes Weld's place. Supported features:
 * <ul>
 *     <li>{@link ApplicationScoped} <i>(singleton)</i> and {@link Dependent} <i>(new instance per lookup)</i> beans.</li>
 *     <li>{@link Inject} constructor injection, plus field injection for unmanaged instances.</li>
 *     <li>Injection of {@link Instance}, {@link Provider}, {@link Event}, {@link BeanManager} and {@link BeanContainer}.</li>
 *     <li>{@link Produces} methods.</li>
 *     <li>{@link Observes} methods with {@link Priority} ordering.</li>
 *     <li>{@link PostConstruct} / {@link PreDestroy} callbacks.</li>
 *     <li>{@link EagerInitialization} beans.</li>
 * </ul>
 * Beans are handed out as direct references rather than client proxies. Circular constructor dependencies between
 * application scoped beans are therefore only supported when the cycle can be broken at an interface-typed
 * injection point <i>(a JDK dynamic proxy is injected there instead)</i>.
 *
 * @author Recaf Android port
 */
public final class LiteContainer implements SeContainer {
	private static final Annotation[] NO_QUALIFIERS = new Annotation[0];
	private final List<LiteBean<?>> beans = new CopyOnWriteArrayList<>();
	private final Map<Class<?>, List<LiteBean<?>>> resolutionCache = new ConcurrentHashMap<>();
	private final Map<LiteBean<?>, Object> singletons = new ConcurrentHashMap<>();
	private final List<Object> creationOrder = new ArrayList<>();
	private final List<ObserverRef> observers = new CopyOnWriteArrayList<>();
	private final ReentrantLock creationLock = new ReentrantLock();
	private final ThreadLocal<Deque<LiteBean<?>>> creationStack = ThreadLocal.withInitial(ArrayDeque::new);
	private final LiteBeanContainer beanContainer = new LiteBeanContainer(this);
	private final BeanManager beanManager;
	private volatile boolean running = true;

	/**
	 * @param beanClasses
	 * 		Bean classes to manage.
	 */
	public LiteContainer(@Nonnull Collection<Class<?>> beanClasses) {
		// The full BeanManager interface is large and mostly irrelevant here.
		// Expose the supported BeanContainer subset through a dynamic proxy.
		beanManager = (BeanManager) Proxy.newProxyInstance(LiteContainer.class.getClassLoader(),
				new Class[]{BeanManager.class}, (proxy, method, args) -> {
					try {
						Method target = LiteBeanContainer.class.getMethod(method.getName(), method.getParameterTypes());
						return target.invoke(beanContainer, args);
					} catch (NoSuchMethodException ex) {
						throw new UnsupportedOperationException("BeanManager." + method.getName() +
								" is not supported by the Android CDI container");
					} catch (InvocationTargetException ex) {
						throw ex.getTargetException();
					}
				});
		for (Class<?> beanClass : beanClasses)
			register(beanClass);
	}

	/**
	 * Registers a bean class. Classes that are not concrete types annotated with {@link ApplicationScoped}
	 * or {@link Dependent} are ignored.
	 *
	 * @param beanClass
	 * 		Class to register.
	 */
	public void register(@Nonnull Class<?> beanClass) {
		// The bean index is generated by scanning sources for scope annotations, which also picks up types that
		// merely declare an annotated member (such as an interface with a producer method). Those are not beans.
		if (beanClass.isInterface() || Modifier.isAbstract(beanClass.getModifiers()))
			return;
		Class<? extends Annotation> scope;
		if (beanClass.isAnnotationPresent(ApplicationScoped.class)) scope = ApplicationScoped.class;
		else if (beanClass.isAnnotationPresent(Dependent.class)) scope = Dependent.class;
		else return;
		for (LiteBean<?> existing : beans)
			if (existing.getBeanClass() == beanClass && existing.producer == null)
				return;

		LiteBean<?> bean = new LiteBean<>(this, beanClass, scope, beanClass, null);
		beans.add(bean);

		for (Method method : allMethods(beanClass)) {
			// Producer methods
			if (method.isAnnotationPresent(Produces.class)) {
				Class<? extends Annotation> producerScope = method.isAnnotationPresent(ApplicationScoped.class) ?
						ApplicationScoped.class : Dependent.class;
				beans.add(new LiteBean<>(this, beanClass, producerScope, method.getReturnType(), new ProducerRef(bean, method)));
			}

			// Observer methods
			Annotation[][] parameterAnnotations = method.getParameterAnnotations();
			for (int i = 0; i < parameterAnnotations.length; i++) {
				if (!hasAnnotation(parameterAnnotations[i], Observes.class))
					continue;
				int priority = ObserverMethod.DEFAULT_PRIORITY;
				for (Annotation annotation : parameterAnnotations[i])
					if (annotation instanceof Priority p)
						priority = p.value();
				method.setAccessible(true);
				observers.add(new ObserverRef(bean, method, i, method.getParameterTypes()[i], priority));
			}
		}
		resolutionCache.clear();
	}

	// ============================================ Resolution ============================================

	/**
	 * @param type
	 * 		Required type.
	 * @param <T>
	 * 		Required type.
	 *
	 * @return All beans whose bean types include the required type.
	 */
	@Nonnull
	@SuppressWarnings({"unchecked", "rawtypes"})
	<T> List<LiteBean<? extends T>> resolveAll(@Nonnull Class<T> type) {
		List<LiteBean<?>> matches = resolutionCache.computeIfAbsent(type, t -> beans.stream()
				.filter(bean -> t.isAssignableFrom(bean.type))
				.collect(Collectors.toList()));
		return (List) matches;
	}

	/**
	 * @param type
	 * 		Required type.
	 * @param <T>
	 * 		Required type.
	 *
	 * @return The single bean satisfying the required type.
	 *
	 * @throws UnsatisfiedResolutionException
	 * 		When no bean satisfies the type.
	 * @throws AmbiguousResolutionException
	 * 		When multiple beans satisfy the type.
	 */
	@Nonnull
	<T> LiteBean<? extends T> resolveSingle(@Nonnull Class<T> type) {
		List<LiteBean<? extends T>> matches = resolveAll(type);
		if (matches.isEmpty())
			throw new UnsatisfiedResolutionException("No bean found for type: " + type.getName());
		if (matches.size() == 1)
			return matches.get(0);

		// Prefer an exact match when a bean class is itself extended by other bean classes.
		List<LiteBean<? extends T>> exact = matches.stream().filter(bean -> bean.type == type).toList();
		if (exact.size() == 1)
			return exact.get(0);
		throw new AmbiguousResolutionException("Multiple beans found for type: " + type.getName() + " -> " +
				matches.stream().map(b -> b.type.getName()).collect(Collectors.joining(", ")));
	}

	/**
	 * @param type
	 * 		Required type.
	 * @param <T>
	 * 		Required type.
	 *
	 * @return Reference to the bean instance satisfying the required type.
	 */
	@Nonnull
	public <T> T lookup(@Nonnull Class<T> type) {
		return reference(resolveSingle(type));
	}

	/**
	 * @param bean
	 * 		Bean to get an instance of.
	 * @param <T>
	 * 		Bean type.
	 *
	 * @return The singleton for application scoped beans, otherwise a new instance.
	 */
	@Nonnull
	@SuppressWarnings("unchecked")
	<T> T reference(@Nonnull LiteBean<T> bean) {
		if (bean.getScope() != ApplicationScoped.class)
			return create(bean);

		Object existing = singletons.get(bean);
		if (existing != null)
			return (T) existing;

		creationLock.lock();
		try {
			existing = singletons.get(bean);
			if (existing != null)
				return (T) existing;
			T created = create(bean);
			singletons.put(bean, created);
			creationOrder.add(created);
			return created;
		} finally {
			creationLock.unlock();
		}
	}

	/**
	 * @param bean
	 * 		Bean to check.
	 *
	 * @return {@code true} when the application scoped bean has been instantiated.
	 */
	boolean isCreated(@Nonnull LiteBean<?> bean) {
		return singletons.containsKey(bean);
	}

	@Nonnull
	@SuppressWarnings("unchecked")
	private <T> T create(@Nonnull LiteBean<T> bean) {
		Deque<LiteBean<?>> stack = creationStack.get();
		if (bean.getScope() == ApplicationScoped.class && stack.contains(bean)) {
			List<String> path = new ArrayList<>();
			stack.descendingIterator().forEachRemaining(b -> path.add(b.type.getSimpleName()));
			path.add(bean.type.getSimpleName());
			throw new IllegalStateException("Circular bean dependency: " + String.join(" -> ", path));
		}

		stack.push(bean);
		try {
			if (bean.producer != null) {
				Object owner = reference(bean.producer.owner);
				bean.producer.method.setAccessible(true);
				Object produced = bean.producer.method.invoke(owner, resolveParameters(bean.producer.method, -1, null));
				if (produced == null)
					throw new IllegalStateException("Producer returned null: " + bean.producer.method);
				return (T) produced;
			}
			T instance = (T) construct(bean.getBeanClass());
			invokeLifecycle(instance, PostConstruct.class);
			return instance;
		} catch (InvocationTargetException ex) {
			throw new IllegalStateException("Failed to create bean: " + bean.type.getName(), ex.getTargetException());
		} catch (ReflectiveOperationException ex) {
			throw new IllegalStateException("Failed to create bean: " + bean.type.getName(), ex);
		} finally {
			stack.pop();
		}
	}

	/**
	 * Creates an instance of any class, satisfying its {@link Inject} constructor, fields and methods.
	 * The class does not need to be a registered bean.
	 *
	 * @param type
	 * 		Class to instantiate.
	 * @param <T>
	 * 		Instance type.
	 *
	 * @return New instance.
	 *
	 * @throws ReflectiveOperationException
	 * 		When the class cannot be instantiated.
	 */
	@Nonnull
	public <T> T instantiate(@Nonnull Class<T> type) throws ReflectiveOperationException {
		T instance = type.cast(construct(type));
		for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
			for (Field field : c.getDeclaredFields()) {
				if (!field.isAnnotationPresent(Inject.class) || Modifier.isStatic(field.getModifiers()))
					continue;
				field.setAccessible(true);
				field.set(instance, resolveInjectionPoint(field.getType(), field.getGenericType()));
			}
			for (Method method : c.getDeclaredMethods()) {
				if (!method.isAnnotationPresent(Inject.class) || Modifier.isStatic(method.getModifiers()))
					continue;
				method.setAccessible(true);
				method.invoke(instance, resolveParameters(method, -1, null));
			}
		}
		invokeLifecycle(instance, PostConstruct.class);
		return instance;
	}

	/**
	 * Invokes {@link PreDestroy} callbacks of an instance created by {@link #instantiate(Class)}.
	 *
	 * @param instance
	 * 		Instance to release.
	 *
	 * @throws ReflectiveOperationException
	 * 		When a callback fails.
	 */
	public void release(@Nonnull Object instance) throws ReflectiveOperationException {
		invokeLifecycle(instance, PreDestroy.class);
	}

	@Nonnull
	private Object construct(@Nonnull Class<?> type) throws ReflectiveOperationException {
		Constructor<?> target = null;
		Constructor<?>[] constructors = type.getDeclaredConstructors();
		for (Constructor<?> constructor : constructors) {
			if (constructor.isAnnotationPresent(Inject.class)) {
				target = constructor;
				break;
			}
		}
		if (target == null) {
			for (Constructor<?> constructor : constructors) {
				if (constructor.getParameterCount() == 0) {
					target = constructor;
					break;
				}
			}
		}
		if (target == null) {
			if (constructors.length == 1) target = constructors[0];
			else throw new NoSuchMethodException("No @Inject or no-arg constructor in " + type.getName());
		}
		target.setAccessible(true);
		return target.newInstance(resolveParameters(target, -1, null));
	}

	@Nonnull
	private Object[] resolveParameters(@Nonnull Executable executable, int eventIndex, @Nullable Object event) {
		Class<?>[] types = executable.getParameterTypes();
		Type[] genericTypes = executable.getGenericParameterTypes();
		// Synthetic/implicit parameters may be missing from the generic signature.
		if (genericTypes.length != types.length)
			genericTypes = types;
		Object[] args = new Object[types.length];
		for (int i = 0; i < types.length; i++)
			args[i] = i == eventIndex ? event : resolveInjectionPoint(types[i], genericTypes[i]);
		return args;
	}

	@Nonnull
	private Object resolveInjectionPoint(@Nonnull Class<?> rawType, @Nonnull Type genericType) {
		if (rawType == Instance.class || rawType == Provider.class)
			return new LiteInstance<>(this, typeArgument(genericType));
		if (rawType == Event.class)
			return new LiteEvent<>(this, typeArgument(genericType));
		if (rawType == BeanManager.class)
			return beanManager;
		if (rawType == BeanContainer.class)
			return beanContainer;
		if (rawType == SeContainer.class || rawType == LiteContainer.class)
			return this;

		LiteBean<?> bean = resolveSingle(rawType);
		// Break circular dependencies at interface-typed injection points with a lazily resolving proxy.
		if (rawType.isInterface() && bean.getScope() == ApplicationScoped.class
				&& !isCreated(bean) && creationStack.get().contains(bean)) {
			return Proxy.newProxyInstance(rawType.getClassLoader(), new Class[]{rawType}, (proxy, method, args) -> {
				try {
					return method.invoke(reference(bean), args);
				} catch (InvocationTargetException ex) {
					throw ex.getTargetException();
				}
			});
		}
		return reference(bean);
	}

	/**
	 * @param type
	 * 		Generic type of an injection point like {@code Instance<Foo>}.
	 *
	 * @return The raw class of the first type argument, or {@link Object} if there is none.
	 */
	@Nonnull
	static Class<?> typeArgument(@Nonnull Type type) {
		if (type instanceof ParameterizedType parameterized) {
			Type[] arguments = parameterized.getActualTypeArguments();
			if (arguments.length > 0)
				return rawClass(arguments[0]);
		}
		return Object.class;
	}

	@Nonnull
	static Class<?> rawClass(@Nonnull Type type) {
		if (type instanceof Class<?> cls)
			return cls;
		if (type instanceof ParameterizedType parameterized)
			return rawClass(parameterized.getRawType());
		if (type instanceof WildcardType wildcard && wildcard.getUpperBounds().length > 0)
			return rawClass(wildcard.getUpperBounds()[0]);
		if (type instanceof TypeVariable<?> variable && variable.getBounds().length > 0)
			return rawClass(variable.getBounds()[0]);
		return Object.class;
	}

	// ============================================ Events & lifecycle ============================================

	/**
	 * Notifies all {@link Observes} methods compatible with the event, in priority order.
	 *
	 * @param event
	 * 		Event to publish.
	 */
	public void fire(@Nonnull Object event) {
		List<ObserverRef> matching = observers.stream()
				.filter(observer -> observer.eventType.isInstance(event))
				.sorted(Comparator.comparingInt(observer -> observer.priority))
				.toList();
		for (ObserverRef observer : matching) {
			try {
				Object target = Modifier.isStatic(observer.method.getModifiers()) ? null : reference(observer.owner);
				observer.method.invoke(target, resolveParameters(observer.method, observer.eventIndex, event));
			} catch (InvocationTargetException ex) {
				throw new IllegalStateException("Observer failed: " + observer.method, ex.getTargetException());
			} catch (ReflectiveOperationException ex) {
				throw new IllegalStateException("Observer failed: " + observer.method, ex);
			}
		}
	}

	/**
	 * Creates all application scoped beans marked with {@link EagerInitialization} for the given stage.
	 *
	 * @param stage
	 * 		Stage to initialize beans of.
	 */
	public void initializeEagerBeans(@Nonnull InitializationStage stage) {
		for (LiteBean<?> bean : beans) {
			if (bean.producer != null || bean.getScope() != ApplicationScoped.class)
				continue;
			EagerInitialization eager = bean.getBeanClass().getAnnotation(EagerInitialization.class);
			if (eager != null && eager.value() == stage)
				reference(bean);
		}
	}

	private static void invokeLifecycle(@Nonnull Object instance, @Nonnull Class<? extends Annotation> annotation)
			throws ReflectiveOperationException {
		for (Method method : allMethods(instance.getClass())) {
			if (method.isAnnotationPresent(annotation) && method.getParameterCount() == 0) {
				method.setAccessible(true);
				try {
					method.invoke(instance);
				} catch (InvocationTargetException ex) {
					if (ex.getTargetException() instanceof ReflectiveOperationException roe) throw roe;
					throw new IllegalStateException(annotation.getSimpleName() + " callback failed: " + method,
							ex.getTargetException());
				}
			}
		}
	}

	/**
	 * @param type
	 * 		Class to collect methods of.
	 *
	 * @return Declared methods of the class and its super-classes, most specific first.
	 */
	@Nonnull
	private static List<Method> allMethods(@Nonnull Class<?> type) {
		List<Method> methods = new ArrayList<>();
		for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
			for (Method method : c.getDeclaredMethods()) {
				if (!method.isSynthetic() && !method.isBridge())
					methods.add(method);
			}
		}
		return methods;
	}

	private static boolean hasAnnotation(@Nonnull Annotation[] annotations, @Nonnull Class<? extends Annotation> type) {
		for (Annotation annotation : annotations)
			if (type.isInstance(annotation))
				return true;
		return false;
	}

	/**
	 * @return All registered beans.
	 */
	@Nonnull
	List<LiteBean<?>> beans() {
		return beans;
	}

	// ============================================ SeContainer ============================================

	@Override
	public void close() {
		if (!running)
			return;
		running = false;
		creationLock.lock();
		try {
			// Destroy in reverse creation order so dependents shut down before their dependencies.
			for (int i = creationOrder.size() - 1; i >= 0; i--) {
				try {
					invokeLifecycle(creationOrder.get(i), PreDestroy.class);
				} catch (Throwable ignored) {
					// Shutdown is best effort.
				}
			}
			creationOrder.clear();
			singletons.clear();
		} finally {
			creationLock.unlock();
		}
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	@Override
	public BeanManager getBeanManager() {
		return beanManager;
	}

	@Override
	public BeanContainer getBeanContainer() {
		return beanContainer;
	}

	@Override
	public Instance<Object> select(Annotation... qualifiers) {
		return new LiteInstance<>(this, Object.class);
	}

	@Override
	public <U> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
		return new LiteInstance<>(this, subtype);
	}

	@Override
	@SuppressWarnings("unchecked")
	public <U> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
		return new LiteInstance<>(this, (Class<U>) subtype.getRawType());
	}

	@Override
	public boolean isUnsatisfied() {
		return beans.isEmpty();
	}

	@Override
	public boolean isAmbiguous() {
		return beans.size() > 1;
	}

	@Override
	public void destroy(Object instance) {
		try {
			release(instance);
		} catch (ReflectiveOperationException ex) {
			throw new IllegalStateException(ex);
		}
	}

	@Override
	public Handle<Object> getHandle() {
		return new LiteInstance<>(this, Object.class).getHandle();
	}

	@Override
	public Iterable<? extends Handle<Object>> handles() {
		return new LiteInstance<>(this, Object.class).handles();
	}

	@Override
	public Object get() {
		return new LiteInstance<>(this, Object.class).get();
	}

	@Override
	public Iterator<Object> iterator() {
		return new LiteInstance<>(this, Object.class).iterator();
	}

	/**
	 * Producer method reference.
	 *
	 * @param owner
	 * 		Bean declaring the producer.
	 * @param method
	 * 		Producer method.
	 */
	record ProducerRef(@Nonnull LiteBean<?> owner, @Nonnull Method method) {}

	/**
	 * Observer method reference.
	 *
	 * @param owner
	 * 		Bean declaring the observer.
	 * @param method
	 * 		Observer method.
	 * @param eventIndex
	 * 		Index of the event parameter.
	 * @param eventType
	 * 		Observed event type.
	 * @param priority
	 * 		Observer priority, lower values are notified first.
	 */
	private record ObserverRef(@Nonnull LiteBean<?> owner, @Nonnull Method method, int eventIndex,
	                           @Nonnull Class<?> eventType, int priority) {}
}
