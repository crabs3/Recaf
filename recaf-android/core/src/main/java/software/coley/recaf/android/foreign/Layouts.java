package software.coley.recaf.android.foreign;

import java.nio.ByteOrder;

/**
 * Layout implementations for {@link ValueLayout}.
 */
final class Layouts {
	private Layouts() {}

	record ByteLayout(ByteOrder order) implements ValueLayout.OfByte {
		@Override
		public long byteSize() {
			return 1;
		}

		@Override
		public ValueLayout.OfByte withOrder(ByteOrder order) {
			return new ByteLayout(order);
		}
	}

	record ShortLayout(ByteOrder order) implements ValueLayout.OfShort {
		@Override
		public long byteSize() {
			return 2;
		}

		@Override
		public ValueLayout.OfShort withOrder(ByteOrder order) {
			return new ShortLayout(order);
		}
	}

	record CharLayout(ByteOrder order) implements ValueLayout.OfChar {
		@Override
		public long byteSize() {
			return 2;
		}

		@Override
		public ValueLayout.OfChar withOrder(ByteOrder order) {
			return new CharLayout(order);
		}
	}

	record IntLayout(ByteOrder order) implements ValueLayout.OfInt {
		@Override
		public long byteSize() {
			return 4;
		}

		@Override
		public ValueLayout.OfInt withOrder(ByteOrder order) {
			return new IntLayout(order);
		}
	}

	record LongLayout(ByteOrder order) implements ValueLayout.OfLong {
		@Override
		public long byteSize() {
			return 8;
		}

		@Override
		public ValueLayout.OfLong withOrder(ByteOrder order) {
			return new LongLayout(order);
		}
	}
}
