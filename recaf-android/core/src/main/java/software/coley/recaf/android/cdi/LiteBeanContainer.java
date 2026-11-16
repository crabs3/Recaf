package software.coley.recaf.android.cdi;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.spi.Context;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanContainer;
import jakarta.enterprise.inject.spi.InterceptionType;
import jakarta.enterprise.inject.spi.Interceptor;
import jakarta.enterprise.inject.spi.ObserverMethod;
import jakarta.inject.Qualifier;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link BeanContainer} implementation of {@link LiteContainer}.
 * <p>
 * This class is also the delegate of the {@code BeanManager} proxy handed out by the container, which matches
 * methods by name and parameter types. Keep the methods public.
 *
 * @author Recaf Android port
 */
public final class LiteBeanContainer implements BeanContainer {
	private final LiteContainer container;

	LiteBeanContainer(@Nonnull LiteContainer container) {
		this.container = container;
	}

	@Override
	@SuppressWarnings({"unchecked", "rawtypes"})
	public Object getReference(Bean<?> bean, Type beanType, CreationalContext<?> ctx) {
		if (bean instanceof LiteBean liteBean)
			return container.reference(liteBean);
		return ((Bean) bean).create(ctx);
	}

	@Override
	public <T> CreationalContext<T> createCreationalContext(Contextual<T> contextual) {
		return new CreationalContext<>() {
			@Override
			public void push(T incompleteInstance) {
				// no-op
			}

			@Override
			public void release() {
				// no-op
			}
		};
	}

	@Override
	public Set<Bean<?>> getBeans(Type beanType, Annotation... qualifiers) {
		return new LinkedHashSet<>(container.resolveAll(LiteContainer.rawClass(beanType)));
	}

	@Override
	public Set<Bean<?>> getBeans(String name) {
		return Collections.emptySet();
	}

	@Override
	public <X> Bean<? extends X> resolve(Set<Bean<? extends X>> beans) {
		if (beans == null || beans.isEmpty())
			return null;
		if (beans.size() > 1)
			throw new jakarta.enterprise.inject.AmbiguousResolutionException("Multiple beans: " + beans);
		return beans.iterator().next();
	}

	@Override
	public <T> Set<ObserverMethod<? super T>> resolveObserverMethods(T event, Annotation... qualifiers) {
		throw new UnsupportedOperationException("Observer method resolution is not supported by the Android CDI container");
	}

	@Override
	public List<Interceptor<?>> resolveInterceptors(InterceptionType type, Annotation... interceptorBindings) {
		return Collections.emptyList();
	}

	@Override
	public boolean isScope(Class<? extends Annotation> annotationType) {
		return annotationType == ApplicationScoped.class || annotationType == Dependent.class;
	}

	@Override
	public boolean isNormalScope(Class<? extends Annotation> annotationType) {
		return annotationType == ApplicationScoped.class;
	}

	@Override
	public boolean isQualifier(Class<? extends Annotation> annotationType) {
		return annotationType.isAnnotationPresent(Qualifier.class);
	}

	@Override
	public boolean isStereotype(Class<? extends Annotation> annotationType) {
		return false;
	}

	@Override
	public boolean isInterceptorBinding(Class<? extends Annotation> annotationType) {
		return false;
	}

	@Override
	public Context getContext(Class<? extends Annotation> scopeType) {
		throw new UnsupportedOperationException("Contexts are not supported by the Android CDI container");
	}

	@Override
	public Collection<Context> getContexts(Class<? extends Annotation> scopeType) {
		return Collections.emptyList();
	}

	@Override
	public Event<Object> getEvent() {
		return new LiteEvent<>(container, Object.class);
	}

	@Override
	public Instance<Object> createInstance() {
		return new LiteInstance<>(container, Object.class);
	}

	@Override
	public boolean isMatchingBean(Set<Type> beanTypes, Set<Annotation> beanQualifiers, Type requiredType, Set<Annotation> requiredQualifiers) {
		Class<?> required = LiteContainer.rawClass(requiredType);
		for (Type beanType : beanTypes)
			if (required == LiteContainer.rawClass(beanType))
				return true;
		return false;
	}

	@Override
	public boolean isMatchingEvent(Type specifiedType, Set<Annotation> specifiedQualifiers, Type observedEventType, Set<Annotation> observedEventQualifiers) {
		return LiteContainer.rawClass(observedEventType).isAssignableFrom(LiteContainer.rawClass(specifiedType));
	}
}
