package software.coley.recaf.info;

import jakarta.annotation.Nonnull;
import software.coley.recaf.android.compat.FileOffload;
import software.coley.recaf.info.builder.FileInfoBuilder;
import software.coley.recaf.info.properties.Property;
import software.coley.recaf.info.properties.PropertyContainer;
import software.coley.recaf.info.properties.builtin.OriginalFileHashesProperty;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.ref.SoftReference;
import java.util.Arrays;
import java.util.Map;

/**
 * Basic implementation of file info.
 * <p>
 * The Android build differs from the desktop one in that a large resource file may not hold its content, and
 * read it from the archive it came from when asked instead.
 *
 * @author Matt Coley
 */
public class BasicFileInfo implements FileInfo {
	private final PropertyContainer properties;
	private final String name;
	/** The content, or {@code null} when it is read from {@link #source} instead. */
	private volatile byte[] rawContent;
	private final FileOffload.Source source;
	private SoftReference<byte[]> loaded;

	public BasicFileInfo(@Nonnull FileInfoBuilder<?> builder) {
		this(builder.getName(),
				builder.getRawContent(),
				builder.getProperties());
	}

	/**
	 * @param name
	 * 		File name/path.
	 * @param rawContent
	 * 		Raw contents of file.
	 * @param properties
	 * 		Assorted properties.
	 */
	public BasicFileInfo(@Nonnull String name, @Nonnull byte[] rawContent, @Nonnull PropertyContainer properties) {
		this.name = name;
		this.properties = properties;
		// The Android build keeps large resource files out of memory, see 'FileOffload'.
		this.source = FileOffload.offer(name, rawContent, getClass());
		this.rawContent = rawContent;
		if (source != null) {
			// Equality of files goes by a hash of their content. Taken now, it never has to be fetched for that.
			OriginalFileHashesProperty.hash(this);
			this.rawContent = null;
		}
	}

	@Nonnull
	@Override
	public byte[] getRawContent() {
		byte[] kept = rawContent;
		if (kept != null)
			return kept;
		synchronized (this) {
			byte[] content = loaded == null ? null : loaded.get();
			if (content == null) {
				try {
					content = source.read();
				} catch (IOException ex) {
					throw new UncheckedIOException("Content of '" + name + "' could not be read back", ex);
				}
				loaded = new SoftReference<>(content);
			}
			return content;
		}
	}

	@Nonnull
	@Override
	public String getName() {
		return name;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) return true;
		if (o == null) return false;
		if (o instanceof FileInfo other) {
			if (!name.equals(other.getName())) return false;
			int hash = OriginalFileHashesProperty.hash(this);
			int hashOther = OriginalFileHashesProperty.hash(other);
			return hash == hashOther;
		}
		return false;
	}

	@Override
	public int hashCode() {
		int result = name.hashCode();
		int contentHashCode = OriginalFileHashesProperty.hash(this);
		result = 31 * result + contentHashCode;
		return result;
	}

	@Override
	public <V> void setProperty(Property<V> property) {
		properties.setProperty(property);
	}

	@Override
	public void removeProperty(String key) {
		properties.removeProperty(key);
	}

	@Nonnull
	@Override
	public Map<String, Property<?>> getProperties() {
		return properties.getProperties();
	}

	@Override
	public String toString() {
		return name;
	}
}
