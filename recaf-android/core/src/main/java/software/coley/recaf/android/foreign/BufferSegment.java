package software.coley.recaf.android.foreign;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * {@link MemorySegment} backed by a {@link ByteBuffer}.
 * <p>
 * All accesses are absolute, so segments can be shared between threads for reading as on desktop.
 */
final class BufferSegment implements MemorySegment {
	private final ByteBuffer little;
	private final ByteBuffer big;
	private final int size;

	/**
	 * @param buffer
	 * 		Buffer whose remaining content becomes the segment.
	 */
	BufferSegment(ByteBuffer buffer) {
		ByteBuffer base = buffer.slice();
		this.little = base.duplicate().order(ByteOrder.LITTLE_ENDIAN);
		this.big = base.duplicate().order(ByteOrder.BIG_ENDIAN);
		this.size = base.capacity();
	}

	static void copy(BufferSegment src, long srcOffset, BufferSegment dst, long dstOffset, long bytes) {
		src.check(srcOffset, bytes);
		dst.check(dstOffset, bytes);
		ByteBuffer source = src.big.duplicate();
		source.position((int) srcOffset).limit((int) (srcOffset + bytes));
		ByteBuffer target = dst.big.duplicate();
		target.position((int) dstOffset);
		target.put(source);
	}

	private ByteBuffer view(ValueLayout layout) {
		return layout.order() == ByteOrder.LITTLE_ENDIAN ? little : big;
	}

	private void check(long offset, long length) {
		if (offset < 0 || length < 0 || offset + length > size)
			throw new IndexOutOfBoundsException("Out of bound access on segment; size = " + size +
					", offset = " + offset + ", length = " + length);
	}

	@Override
	public long byteSize() {
		return size;
	}

	@Override
	public MemorySegment asSlice(long offset, long newSize) {
		check(offset, newSize);
		ByteBuffer copy = big.duplicate();
		copy.position((int) offset).limit((int) (offset + newSize));
		return new BufferSegment(copy);
	}

	@Override
	public MemorySegment asSlice(long offset) {
		return asSlice(offset, size - offset);
	}

	@Override
	public ByteBuffer asByteBuffer() {
		return big.asReadOnlyBuffer().order(ByteOrder.BIG_ENDIAN);
	}

	@Override
	public byte[] toArray(ValueLayout.OfByte layout) {
		byte[] array = new byte[size];
		ByteBuffer copy = big.duplicate();
		copy.position(0).limit(size);
		copy.get(array);
		return array;
	}

	@Override
	public byte get(ValueLayout.OfByte layout, long offset) {
		check(offset, 1);
		return big.get((int) offset);
	}

	@Override
	public short get(ValueLayout.OfShort layout, long offset) {
		check(offset, 2);
		return view(layout).getShort((int) offset);
	}

	@Override
	public char get(ValueLayout.OfChar layout, long offset) {
		check(offset, 2);
		return view(layout).getChar((int) offset);
	}

	@Override
	public int get(ValueLayout.OfInt layout, long offset) {
		check(offset, 4);
		return view(layout).getInt((int) offset);
	}

	@Override
	public long get(ValueLayout.OfLong layout, long offset) {
		check(offset, 8);
		return view(layout).getLong((int) offset);
	}

	@Override
	public void set(ValueLayout.OfByte layout, long offset, byte value) {
		check(offset, 1);
		big.put((int) offset, value);
	}

	@Override
	public long mismatch(MemorySegment other) {
		BufferSegment that = (BufferSegment) other;
		int common = Math.min(size, that.size);
		for (int i = 0; i < common; i++)
			if (big.get(i) != that.big.get(i))
				return i;
		return size == that.size ? -1 : common;
	}

	@Override
	public boolean isReadOnly() {
		return big.isReadOnly();
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof BufferSegment that && that.size == size && mismatch(that) == -1;
	}

	@Override
	public int hashCode() {
		// Content based, matching how LL-Java-Zip uses segment hashes for de-duplication.
		int h = 1;
		for (int i = 0; i < size; i++)
			h = 31 * h + big.get(i);
		return h;
	}

	@Override
	public String toString() {
		return "MemorySegment{size=" + size + '}';
	}
}
