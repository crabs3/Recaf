package software.coley.recaf.android.cdi;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.util.TypeLiteral;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * {@link Instance} implementation of {@link LiteContainer}.
 *
 * @param <T>
 * 		Required type.
 *
 * @author Recaf Android port
 */
final class LiteInstance<T> implements Instance<T> {
	private final LiteContainer container;
	private final Class<T> type;

	@SuppressWarnings("unchecked")
	LiteInstance(@Nonnull LiteContainer container, @Nonnull Class<?> type) {
		this.container = container;
		this.type = (Class<T>) type;
	}

	@Override
	public Instance<T> select(Annotation... qualifiers) {
		return this;
	}

	@Override
	public <U extends T> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
		return new LiteInstance<>(container, subtype);
	}

	@Override
	public <U extends T> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
		return new LiteInstance<>(container, subtype.getRawType());
	}

	@Override
	public boolean isUnsatisfied() {
		return container.resolveAll(type).isEmpty();
	}

	@Override
	public boolean isAmbiguous() {
		if (container.resolveAll(type).size() <= 1)
			return false;
		try {
			container.resolveSingle(type);
			return false;
		} catch (RuntimeException ex) {
			return true;
		}
	}

	@Override
	public void destroy(T instance) {
		try {
			container.release(instance);
		} catch (ReflectiveOperationException ex) {
			throw new IllegalStateException(ex);
		}
	}

	@Override
	public Handle<T> getHandle() {
		return new LiteHandle<>(container.resolveSingle(type));
	}

	@Override
	public Iterable<? extends Handle<T>> handles() {
		List<Handle<T>> handles = new ArrayList<>();
		for (LiteBean<? extends T> bean : container.resolveAll(type))
			handles.add(new LiteHandle<>(bean));
		return handles;
	}

	@Override
	public T get() {
		return container.lookup(type);
	}

	@Override
	public Iterator<T> iterator() {
		List<T> instances = new ArrayList<>();
		for (LiteBean<? extends T> bean : container.resolveAll(type))
			instances.add(container.reference(bean));
		return instances.iterator();
	}

	/**
	 * Lazy handle of a bean instance.
	 *
	 * @param <T>
	 * 		Required type.
	 */
	private final class LiteHandle<T2> implements Handle<T2> {
		private final LiteBean<? extends T2> bean;
		private T2 instance;

		private LiteHandle(@Nonnull LiteBean<? extends T2> bean) {
			this.bean = bean;
		}

		@Override
		public T2 get() {
			if (instance == null)
				instance = container.reference(bean);
			return instance;
		}

		@Override
		@SuppressWarnings("unchecked")
		public Bean<T2> getBean() {
			return (Bean<T2>) bean;
		}

		@Override
		public void destroy() {
			// Application scoped instances live as long as the container.
			// Dependent instances are released when unreachable.
			instance = null;
		}

		@Override
		public void close() {
			destroy();
		}
	}
}
