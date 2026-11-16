package software.coley.recaf.android.foreign;

import java.nio.ByteOrder;

/**
 * Stand-in for {@code java.lang.foreign.ValueLayout}.
 * Layouts only carry a byte order here, all accesses are treated as unaligned.
 */
public interface ValueLayout {
	OfByte JAVA_BYTE = new Layouts.ByteLayout(ByteOrder.nativeOrder());
	OfShort JAVA_SHORT = new Layouts.ShortLayout(ByteOrder.nativeOrder());
	OfShort JAVA_SHORT_UNALIGNED = JAVA_SHORT;
	OfChar JAVA_CHAR = new Layouts.CharLayout(ByteOrder.nativeOrder());
	OfChar JAVA_CHAR_UNALIGNED = JAVA_CHAR;
	OfInt JAVA_INT = new Layouts.IntLayout(ByteOrder.nativeOrder());
	OfInt JAVA_INT_UNALIGNED = JAVA_INT;
	OfLong JAVA_LONG = new Layouts.LongLayout(ByteOrder.nativeOrder());
	OfLong JAVA_LONG_UNALIGNED = JAVA_LONG;

	/**
	 * @return Byte order of the layout.
	 */
	ByteOrder order();

	/**
	 * @return Size of the layout in bytes.
	 */
	long byteSize();

	interface OfByte extends ValueLayout {
		OfByte withOrder(ByteOrder order);
	}

	interface OfShort extends ValueLayout {
		OfShort withOrder(ByteOrder order);
	}

	interface OfChar extends ValueLayout {
		OfChar withOrder(ByteOrder order);
	}

	interface OfInt extends ValueLayout {
		OfInt withOrder(ByteOrder order);
	}

	interface OfLong extends ValueLayout {
		OfLong withOrder(ByteOrder order);
	}
}
