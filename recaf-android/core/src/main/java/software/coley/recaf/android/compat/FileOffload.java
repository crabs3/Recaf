package software.coley.recaf.android.compat;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Keeps the content of large resource files out of memory.
 * <p>
 * Recaf holds every file of an opened archive in memory. For an Android application that is mostly native
 * libraries, images, sounds and other assets, often far more than the code, and of little interest to somebody
 * reading the code. While an archive is being imported, files of those kinds above a size threshold give up their
 * content as soon as it is known that the same bytes can be read from the archive again, which is checked by
 * name, size and checksum. From then on their content is read from the archive whenever something asks for it.
 * <p>
 * Nothing changes for whoever uses such a file: it still reports its content, it just fetches it first.
 *
 * @author Recaf Android port
 */
public final class FileOffload {
	/** Size from which a file is worth reading back instead of keeping. */
	public static final int DEFAULT_THRESHOLD = 64 * 1024;
	/** Kinds of file whose content is opaque to code analysis. Text, XML, resource tables and nested archives stay. */
	private static final Set<String> OFFLOADED_TYPES = Set.of("BasicFileInfo", "BasicImageFileInfo", "BasicAudioFileInfo",
			"BasicVideoFileInfo", "BasicNativeLibraryFileInfo");
	private static volatile Archive current;
	private static volatile int threshold = DEFAULT_THRESHOLD;

	private FileOffload() {}

	/**
	 * Call before importing an archive. Files created until {@link #end()} are checked against it.
	 *
	 * @param archive
	 * 		Path of the archive that is about to be imported. It must stay in place, unchanged, for as long as the
	 * 		workspace made from it is open.
	 * @param thresholdBytes
	 * 		Size from which files are read back instead of kept.
	 */
	public static void begin(@Nonnull Path archive, int thresholdBytes) {
		end();
		threshold = thresholdBytes;
		try {
			current = new Archive(archive);
		} catch (IOException | RuntimeException ex) {
			// Not an archive the platform's ZIP reader copes with. Everything simply stays in memory.
			current = null;
		}
	}

	/**
	 * Call when the import is over, whether it succeeded or not.
	 *
	 * @return What was kept out of memory during the import, or {@code null} when nothing was.
	 */
	@Nullable
	public static Summary end() {
		Archive archive = current;
		current = null;
		if (archive == null || archive.files.get() == 0)
			return null;
		return new Summary(archive.files.get(), archive.bytes.get());
	}

	/**
	 * Called for every file while it is created.
	 *
	 * @param name
	 * 		Name of the file, which for a file of the archive is its path in it.
	 * @param content
	 * 		Content of the file.
	 * @param type
	 * 		Class of the file info.
	 *
	 * @return Where the content can be read from again, or {@code null} when the file has to keep it.
	 */
	@Nullable
	public static Source offer(@Nonnull String name, @Nonnull byte[] content, @Nonnull Class<?> type) {
		Archive archive = current;
		if (archive == null || content.length < threshold || !OFFLOADED_TYPES.contains(type.getSimpleName()))
			return null;
		return archive.sourceFor(name, content);
	}

	/**
	 * What an import kept out of memory.
	 *
	 * @param files
	 * 		Number of files.
	 * @param bytes
	 * 		Their total size.
	 */
	public record Summary(int files, long bytes) {}

	/**
	 * Where the content of one file can be read from.
	 */
	public interface Source {
		/**
		 * @return The content.
		 *
		 * @throws IOException
		 * 		When the archive can no longer be read.
		 */
		@Nonnull
		byte[] read() throws IOException;
	}

	private static final class Archive {
		private final Path path;
		private final AtomicInteger files = new AtomicInteger();
		private final AtomicLong bytes = new AtomicLong();
		private ZipFile zip;

		private Archive(@Nonnull Path path) throws IOException {
			this.path = path;
			this.zip = new ZipFile(path.toFile());
		}

		@Nullable
		private Source sourceFor(@Nonnull String name, @Nonnull byte[] content) {
			try {
				ZipEntry entry;
				synchronized (this) {
					entry = zip().getEntry(name);
				}
				// Only when the archive provably holds these exact bytes under this name. An entry of a nested
				// archive, or of an archive with several entries of one name, fails this and stays in memory.
				if (entry == null || entry.isDirectory() || entry.getSize() != content.length)
					return null;
				CRC32 crc = new CRC32();
				crc.update(content);
				if (entry.getCrc() != crc.getValue())
					return null;
				files.incrementAndGet();
				bytes.addAndGet(content.length);
				return () -> read(name, content.length);
			} catch (IOException | RuntimeException ex) {
				return null;
			}
		}

		@Nonnull
		private synchronized byte[] read(@Nonnull String name, int size) throws IOException {
			ZipEntry entry = zip().getEntry(name);
			if (entry == null)
				throw new IOException("'" + name + "' is no longer in " + path.getFileName());
			try (InputStream in = zip().getInputStream(entry)) {
				byte[] content = in.readAllBytes();
				if (content.length != size)
					throw new IOException(path.getFileName() + " changed since it was opened");
				return content;
			}
		}

		@Nonnull
		private ZipFile zip() throws IOException {
			if (zip == null)
				zip = new ZipFile(path.toFile());
			return zip;
		}
	}
}
