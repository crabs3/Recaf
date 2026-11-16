package software.coley.recaf.android.foreign;

import java.io.IOException;
import java.nio.channels.FileChannel;

/**
 * Bridges for platform methods that take foreign-memory types.
 */
public final class ForeignSupport {
	private ForeignSupport() {}

	/**
	 * Replacement for {@code FileChannel.map(MapMode, long, long, Arena)}.
	 *
	 * @param channel
	 * 		Channel to map.
	 * @param mode
	 * 		Mapping mode.
	 * @param offset
	 * 		Offset into the file.
	 * @param size
	 * 		Size of the mapping.
	 * @param arena
	 * 		Ignored.
	 *
	 * @return Segment of the mapped file region.
	 *
	 * @throws IOException
	 * 		When the region cannot be mapped, or exceeds the 2GB limit of a single buffer.
	 */
	public static MemorySegment map(FileChannel channel, FileChannel.MapMode mode, long offset, long size, Arena arena) throws IOException {
		if (size > Integer.MAX_VALUE)
			throw new IOException("Cannot map more than 2GB into a single segment on Android (requested " + size + " bytes)");
		if (size == 0)
			return MemorySegment.NULL;
		return new BufferSegment(channel.map(mode, offset, size));
	}
}
