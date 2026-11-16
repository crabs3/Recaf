package software.coley.recaf.android.foreign;

/**
 * Stand-in for {@code java.lang.foreign.Arena}.
 * Segments here are plain garbage collected buffers, so arenas carry no lifetime semantics.
 */
public interface Arena extends AutoCloseable {
	/** Shared no-op arena instance. */
	Arena INSTANCE = () -> {};

	static Arena ofAuto() {
		return INSTANCE;
	}

	static Arena ofConfined() {
		return INSTANCE;
	}

	static Arena ofShared() {
		return INSTANCE;
	}

	static Arena global() {
		return INSTANCE;
	}

	@Override
	void close();
}
