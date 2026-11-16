package software.coley.recaf.android.cdi;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.event.Event;
import jakarta.enterprise.event.NotificationOptions;
import jakarta.enterprise.util.TypeLiteral;

import java.lang.annotation.Annotation;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * {@link Event} implementation of {@link LiteContainer}.
 *
 * @param <T>
 * 		Event type.
 *
 * @author Recaf Android port
 */
final class LiteEvent<T> implements Event<T> {
	private final LiteContainer container;
	private final Class<?> type;

	LiteEvent(@Nonnull LiteContainer container, @Nonnull Class<?> type) {
		this.container = container;
		this.type = type;
	}

	@Override
	public void fire(T event) {
		container.fire(event);
	}

	@Override
	public <U extends T> CompletionStage<U> fireAsync(U event) {
		return CompletableFuture.supplyAsync(() -> {
			container.fire(event);
			return event;
		});
	}

	@Override
	public <U extends T> CompletionStage<U> fireAsync(U event, NotificationOptions options) {
		return fireAsync(event);
	}

	@Override
	public Event<T> select(Annotation... qualifiers) {
		return this;
	}

	@Override
	public <U extends T> Event<U> select(Class<U> subtype, Annotation... qualifiers) {
		return new LiteEvent<>(container, subtype);
	}

	@Override
	public <U extends T> Event<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
		return new LiteEvent<>(container, subtype.getRawType());
	}

	@Override
	public String toString() {
		return "LiteEvent{" + type.getName() + '}';
	}
}
