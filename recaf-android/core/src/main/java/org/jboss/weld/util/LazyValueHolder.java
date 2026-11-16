package org.jboss.weld.util;

import java.util.function.Supplier;

/**
 * Source compatible stand-in for the one Weld utility class that Recaf's core uses outside of its CDI bootstrap.
 * Weld itself is not part of the Android build.
 *
 * @param <T>
 * 		Value type.
 */
public abstract class LazyValueHolder<T> {
	private volatile T value;

	/**
	 * @param supplier
	 * 		Supplier of the value, invoked on first access.
	 * @param <T>
	 * 		Value type.
	 *
	 * @return Holder that computes its value lazily.
	 */
	public static <T> LazyValueHolder<T> forSupplier(Supplier<T> supplier) {
		return new LazyValueHolder<>() {
			@Override
			protected T computeValue() {
				return supplier.get();
			}
		};
	}

	/**
	 * @return The lazily computed value.
	 */
	public T get() {
		T local = value;
		if (local == null) {
			synchronized (this) {
				local = value;
				if (local == null)
					value = local = computeValue();
			}
		}
		return local;
	}

	/**
	 * @return The lazily computed value, or {@code null} if it has not been computed yet.
	 */
	public T getIfPresent() {
		return value;
	}

	/**
	 * Discards the computed value.
	 */
	public void clear() {
		synchronized (this) {
			value = null;
		}
	}

	/**
	 * @return {@code true} when the value has been computed.
	 */
	public boolean isAvailable() {
		return value != null;
	}

	protected abstract T computeValue();
}
