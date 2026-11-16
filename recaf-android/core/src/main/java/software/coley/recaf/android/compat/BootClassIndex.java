package software.coley.recaf.android.compat;

import jakarta.annotation.Nonnull;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Enumeration;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Enumerates the names of the platform's classes by reading the DEX files of the boot classpath.
 * <p>
 * Android has no equivalent of the JDK's module image that can be listed, but the boot classpath jars named by
 * the {@code BOOTCLASSPATH} environment variable are readable by applications. Only the class definition table of
 * each DEX file is read. On other runtimes <i>(such as a desktop JVM running the unit tests)</i> the variable is
 * unset and the index is empty.
 *
 * @author Recaf Android port
 */
public final class BootClassIndex {
	private static final String[] PUBLIC_PREFIXES = {"java/", "javax/", "android/", "dalvik/", "org/w3c/", "org/xml/",
			"org/json/", "org/xmlpull/"};

	private BootClassIndex() {}

	/**
	 * @return Internal names of the classes in the platform's public packages.
	 */
	@Nonnull
	public static NavigableSet<String> readPlatformClassNames() {
		NavigableSet<String> names = new TreeSet<>();
		String bootClassPath = System.getenv("BOOTCLASSPATH");
		if (bootClassPath == null || bootClassPath.isBlank())
			return names;
		for (String entry : bootClassPath.split(File.pathSeparator)) {
			File file = new File(entry);
			if (!file.isFile() || !file.canRead())
				continue;
			try (ZipFile zip = new ZipFile(file)) {
				Enumeration<? extends ZipEntry> entries = zip.entries();
				while (entries.hasMoreElements()) {
					ZipEntry zipEntry = entries.nextElement();
					String name = zipEntry.getName();
					if (!name.startsWith("classes") || !name.endsWith(".dex"))
						continue;
					try (InputStream in = zip.getInputStream(zipEntry)) {
						readClassNames(readAll(in), names);
					}
				}
			} catch (IOException | RuntimeException ignored) {
				// Unreadable or unexpected boot classpath entries are skipped.
			}
		}
		return names;
	}

	/**
	 * @param dex
	 * 		Content of a DEX file.
	 * @param names
	 * 		Set to add the internal names of the defined public-package classes to.
	 */
	static void readClassNames(@Nonnull byte[] dex, @Nonnull NavigableSet<String> names) {
		if (dex.length < 0x70 || dex[0] != 'd' || dex[1] != 'e' || dex[2] != 'x')
			return;
		ByteBuffer buffer = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN);
		int stringIdsOffset = buffer.getInt(0x3C);
		int typeIdsOffset = buffer.getInt(0x44);
		int classDefsSize = buffer.getInt(0x60);
		int classDefsOffset = buffer.getInt(0x64);
		for (int i = 0; i < classDefsSize; i++) {
			// class_def_item is 32 bytes and starts with the type index of the class.
			int typeIndex = buffer.getInt(classDefsOffset + i * 32);
			int descriptorIndex = buffer.getInt(typeIdsOffset + typeIndex * 4);
			int stringDataOffset = buffer.getInt(stringIdsOffset + descriptorIndex * 4);
			String descriptor = readString(dex, stringDataOffset);
			if (descriptor.length() < 3 || descriptor.charAt(0) != 'L')
				continue;
			String name = descriptor.substring(1, descriptor.length() - 1);
			for (String prefix : PUBLIC_PREFIXES) {
				if (name.startsWith(prefix)) {
					names.add(name);
					break;
				}
			}
		}
	}

	/**
	 * Reads a {@code string_data_item}. Type descriptors are effectively ASCII, so multi-byte MUTF-8 sequences
	 * are not decoded exactly.
	 */
	@Nonnull
	private static String readString(@Nonnull byte[] dex, int offset) {
		// Skip the ULEB128 encoded UTF-16 length.
		while ((dex[offset++] & 0x80) != 0) ;
		int end = offset;
		while (end < dex.length && dex[end] != 0)
			end++;
		StringBuilder sb = new StringBuilder(end - offset);
		for (int i = offset; i < end; i++)
			sb.append((char) (dex[i] & 0xFF));
		return sb.toString();
	}

	@Nonnull
	private static byte[] readAll(@Nonnull InputStream in) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
		byte[] buffer = new byte[1 << 16];
		int read;
		while ((read = in.read(buffer)) != -1)
			out.write(buffer, 0, read);
		return out.toByteArray();
	}
}
