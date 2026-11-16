package software.coley.recaf.services.plugin;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.coley.recaf.Bootstrap;
import software.coley.recaf.android.cdi.LiteContainer;

/**
 * Allocator singleton instance that ties into the CDI container.
 * <p>
 * <b>Android variant</b>: Uses the reflective {@link LiteContainer} rather than Weld's injection targets.
 *
 * @author Matt Coley
 */
@ApplicationScoped
public class SharedCdiClassAllocator implements CdiClassAllocator {
	@Inject
	public SharedCdiClassAllocator() {
	}

	@Nonnull
	@Override
	public <T> T instance(@Nonnull Class<T> cls) throws AllocationException {
		try {
			return container().instantiate(cls);
		} catch (Throwable t) {
			throw new AllocationException(cls, t);
		}
	}

	@Override
	public void release(@Nonnull Object instance) throws AllocationException {
		try {
			container().release(instance);
		} catch (Throwable t) {
			throw new AllocationException(instance.getClass(), t);
		}
	}

	@Nonnull
	private static LiteContainer container() {
		return (LiteContainer) Bootstrap.get().getContainer();
	}
}
