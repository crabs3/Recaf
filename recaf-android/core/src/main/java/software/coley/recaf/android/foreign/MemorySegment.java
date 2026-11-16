package software.coley.recaf.android.foreign;

import java.nio.ByteBuffer;

/**
 * Stand-in for {@code java.lang.foreign.MemorySegment}, covering the subset used by Recaf and LL-Java-Zip.
 *
 * @see BufferSegment
 */
public interface MemorySegment {
	/** Empty segment. */
	MemorySegment NULL = new BufferSegment(ByteBuffer.allocate(0));

	/**
	 * @param array
	 * 		Array to wrap. Changes to the array are visible in the segment.
	 *
	 * @return Segment over the whole array.
	 */
	static MemorySegment ofArray(byte[] array) {
		return new BufferSegment(ByteBuffer.wrap(array));
	}

	/**
	 * @param buffer
	 * 		Buffer to wrap. The segment covers the buffer's remaining content.
	 *
	 * @return Segment over the buffer's remaining content.
	 */
	static MemorySegment ofBuffer(ByteBuffer buffer) {
		return new BufferSegment(buffer.slice());
	}

	/**
	 * Bulk copy between segments.
	 *
	 * @param src
	 * 		Source segment.
	 * @param srcOffset
	 * 		Offset into the source.
	 * @param dst
	 * 		Destination segment.
	 * @param dstOffset
	 * 		Offset into the destination.
	 * @param bytes
	 * 		Number of bytes to copy.
	 */
	static void copy(MemorySegment src, long srcOffset, MemorySegment dst, long dstOffset, long bytes) {
		BufferSegment.copy((BufferSegment) src, srcOffset, (BufferSegment) dst, dstOffset, bytes);
	}

	/**
	 * @return Size of the segment in bytes.
	 */
	long byteSize();

	/**
	 * @param offset
	 * 		Offset into this segment.
	 * @param newSize
	 * 		Size of the slice.
	 *
	 * @return View of a sub-range of this segment.
	 */
	MemorySegment asSlice(long offset, long newSize);

	/**
	 * @param offset
	 * 		Offset into this segment.
	 *
	 * @return View of this segment from the given offset to the end.
	 */
	MemorySegment asSlice(long offset);

	/**
	 * @return Read-only buffer view of this segment.
	 */
	ByteBuffer asByteBuffer();

	/**
	 * @param layout
	 * 		Byte layout.
	 *
	 * @return Copy of the segment content.
	 */
	byte[] toArray(ValueLayout.OfByte layout);

	byte get(ValueLayout.OfByte layout, long offset);

	short get(ValueLayout.OfShort layout, long offset);

	char get(ValueLayout.OfChar layout, long offset);

	int get(ValueLayout.OfInt layout, long offset);

	long get(ValueLayout.OfLong layout, long offset);

	void set(ValueLayout.OfByte layout, long offset, byte value);

	/**
	 * @param other
	 * 		Segment to compare against.
	 *
	 * @return Offset of the first differing byte, or {@code -1} if the segments have equal content.
	 */
	long mismatch(MemorySegment other);

	/**
	 * @return {@code true} when the segment content cannot be modified.
	 */
	boolean isReadOnly();
}
