package software.coley.recaf.android.cdi;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.InjectionPoint;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Bean model of {@link LiteContainer}. Either a managed bean class, or a producer method of one.
 *
 * @param <T>
 * 		Bean type.
 *
 * @author Recaf Android port
 */
final class LiteBean<T> implements Bean<T> {
	private final LiteContainer container;
	private final Class<?> beanClass;
	private final Class<? extends Annotation> scope;
	private Set<Type> types;
	/** The most specific type of instances this bean yields. */
	final Class<?> type;
	/** Producer method backing this bean, or {@code null} for managed bean classes. */
	@Nullable
	final LiteContainer.ProducerRef producer;

	LiteBean(@Nonnull LiteContainer container, @Nonnull Class<?> beanClass,
	         @Nonnull Class<? extends Annotation> scope, @Nonnull Class<?> type,
	         @Nullable LiteContainer.ProducerRef producer) {
		this.container = container;
		this.beanClass = beanClass;
		this.scope = scope;
		this.type = type;
		this.producer = producer;
	}

	@Override
	public Class<?> getBeanClass() {
		return beanClass;
	}

	@Override
	public Set<InjectionPoint> getInjectionPoints() {
		return Collections.emptySet();
	}

	@Override
	public T create(CreationalContext<T> creationalContext) {
		return container.reference(this);
	}

	@Override
	public void destroy(T instance, CreationalContext<T> creationalContext) {
		try {
			container.release(instance);
		} catch (ReflectiveOperationException ex) {
			throw new IllegalStateException(ex);
		}
	}

	@Override
	public Set<Type> getTypes() {
		if (types == null) {
			Set<Type> collected = new LinkedHashSet<>();
			collect(type, collected);
			collected.add(Object.class);
			types = Collections.unmodifiableSet(collected);
		}
		return types;
	}

	private static void collect(@Nullable Class<?> cls, @Nonnull Set<Type> types) {
		if (cls == null || !types.add(cls))
			return;
		collect(cls.getSuperclass(), types);
		for (Class<?> itf : cls.getInterfaces())
			collect(itf, types);
	}

	@Override
	public Set<Annotation> getQualifiers() {
		return Set.of(Any.Literal.INSTANCE, Default.Literal.INSTANCE);
	}

	@Override
	public Class<? extends Annotation> getScope() {
		return scope;
	}

	@Override
	public String getName() {
		return null;
	}

	@Override
	public Set<Class<? extends Annotation>> getStereotypes() {
		return Collections.emptySet();
	}

	@Override
	public boolean isAlternative() {
		return false;
	}

	@Override
	public String toString() {
		return "LiteBean{" + type.getName() + (producer == null ? "" : " produced by " + producer.method()) +
				", scope=" + scope.getSimpleName() + '}';
	}
}
