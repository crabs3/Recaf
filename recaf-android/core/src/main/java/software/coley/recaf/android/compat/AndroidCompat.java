package software.coley.recaf.android.compat;

import jakarta.annotation.Nonnull;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stand-ins for JDK library members that the Android platform does not offer.
 * <p>
 * The desktop sources are rewritten to call these when they are synced into the Android build,
 * see {@code source-patches.gradle}.
 *
 * @author Recaf Android port
 */
public final class AndroidCompat {
	/**
	 * Stack size for threads that run decompilers, assemblers and analyses, which recurse deeply.
	 * The memory is only committed as far as a thread actually uses it.
	 */
	public static final long WORKER_STACK_SIZE = 32L * 1024 * 1024;
	/** Equivalent of {@code StandardCharsets.UTF_32}. */
	public static final Charset UTF_32 = Charset.forName("UTF-32");
	/** Equivalent of {@code StandardCharsets.UTF_32BE}. */
	public static final Charset UTF_32BE = Charset.forName("UTF-32BE");
	/** Equivalent of {@code StandardCharsets.UTF_32LE}. */
	public static final Charset UTF_32LE = Charset.forName("UTF-32LE");
	/** Java language level the Android build of Recaf's core is compiled against. */
	private static final int JAVA_FEATURE_VERSION = 22;

	private AndroidCompat() {}

	/**
	 * @return Equivalent of {@code Runtime.version().feature()}.
	 */
	public static int javaFeatureVersion() {
		return JAVA_FEATURE_VERSION;
	}

	/**
	 * @return Loader of platform classes. On Android this is the boot class loader.
	 */
	@Nonnull
	public static ClassLoader platformClassLoader() {
		ClassLoader loader = Object.class.getClassLoader();
		if (loader != null)
			return loader;
		// Desktop JVMs report the bootstrap loader as 'null', walk up from the system loader instead.
		loader = ClassLoader.getSystemClassLoader();
		while (loader.getParent() != null)
			loader = loader.getParent();
		return loader;
	}

	/**
	 * Equivalent of {@code Files.readString(Path)}.
	 */
	@Nonnull
	public static String readString(@Nonnull Path path) throws IOException {
		return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
	}

	/**
	 * Equivalent of {@code Files.readString(Path, Charset)}.
	 */
	@Nonnull
	public static String readString(@Nonnull Path path, @Nonnull Charset charset) throws IOException {
		return new String(Files.readAllBytes(path), charset);
	}

	/**
	 * Equivalent of {@code Files.writeString(Path, CharSequence, OpenOption...)}.
	 */
	@Nonnull
	public static Path writeString(@Nonnull Path path, @Nonnull CharSequence text, OpenOption... options) throws IOException {
		return Files.write(path, text.toString().getBytes(StandardCharsets.UTF_8), options);
	}

	/**
	 * Equivalent of {@code Files.writeString(Path, CharSequence, Charset, OpenOption...)}.
	 */
	@Nonnull
	public static Path writeString(@Nonnull Path path, @Nonnull CharSequence text, @Nonnull Charset charset, OpenOption... options) throws IOException {
		return Files.write(path, text.toString().getBytes(charset), options);
	}

	/**
	 * Equivalent of {@code String.indexOf(String, int, int)}.
	 */
	public static int indexOf(@Nonnull String text, @Nonnull String target, int beginIndex, int endIndex) {
		int length = text.length();
		beginIndex = Math.max(0, Math.min(beginIndex, length));
		endIndex = Math.max(0, Math.min(endIndex, length));
		if (beginIndex > endIndex)
			return -1;
		int index = text.substring(0, endIndex).indexOf(target, beginIndex);
		return index;
	}

	/**
	 * Equivalent of {@code String.indexOf(int, int, int)}.
	 */
	public static int indexOf(@Nonnull String text, int codePoint, int beginIndex, int endIndex) {
		int length = text.length();
		beginIndex = Math.max(0, Math.min(beginIndex, length));
		endIndex = Math.max(0, Math.min(endIndex, length));
		if (beginIndex >= endIndex)
			return -1;
		return text.substring(0, endIndex).indexOf(codePoint, beginIndex);
	}

	/**
	 * Equivalent of {@code String.splitWithDelimiters(String, int)}.
	 */
	@Nonnull
	public static String[] splitWithDelimiters(@Nonnull String text, @Nonnull String regex, int limit) {
		List<String> parts = new ArrayList<>();
		Matcher matcher = Pattern.compile(regex).matcher(text);
		int index = 0;
		int matchCount = 0;
		while (matcher.find()) {
			if (limit > 0 && matchCount >= limit - 1)
				break;
			if (matcher.end() == 0)
				continue; // No leading empty match
			parts.add(text.substring(index, matcher.start()));
			parts.add(matcher.group());
			index = matcher.end();
			matchCount++;
		}
		if (index == 0)
			return new String[]{text};
		parts.add(text.substring(index));
		if (limit == 0) {
			// Trailing empty strings are discarded
			int size = parts.size();
			while (size > 0 && parts.get(size - 1).isEmpty())
				size--;
			return parts.subList(0, size).toArray(new String[0]);
		}
		return parts.toArray(new String[0]);
	}

	/**
	 * {@code String.repeat(int)} exists on Android. This overload only exists so that the rewrite rule covering the
	 * {@code StringBuilder}/{@code StringBuffer} variants below can be applied uniformly.
	 */
	@Nonnull
	public static String repeat(@Nonnull String text, int count) {
		return text.repeat(count);
	}

	/**
	 * Equivalent of {@code StringBuilder.repeat(CharSequence, int)}.
	 */
	@Nonnull
	public static StringBuilder repeat(@Nonnull StringBuilder builder, CharSequence text, int count) {
		checkCount(count);
		for (int i = 0; i < count; i++)
			builder.append(text);
		return builder;
	}

	/**
	 * Equivalent of {@code StringBuilder.repeat(int, int)}.
	 */
	@Nonnull
	public static StringBuilder repeat(@Nonnull StringBuilder builder, int codePoint, int count) {
		checkCount(count);
		for (int i = 0; i < count; i++)
			builder.appendCodePoint(codePoint);
		return builder;
	}

	/**
	 * Equivalent of {@code StringBuffer.repeat(CharSequence, int)}.
	 */
	@Nonnull
	public static StringBuffer repeat(@Nonnull StringBuffer buffer, CharSequence text, int count) {
		checkCount(count);
		for (int i = 0; i < count; i++)
			buffer.append(text);
		return buffer;
	}

	/**
	 * Equivalent of {@code StringBuffer.repeat(int, int)}.
	 */
	@Nonnull
	public static StringBuffer repeat(@Nonnull StringBuffer buffer, int codePoint, int count) {
		checkCount(count);
		for (int i = 0; i < count; i++)
			buffer.appendCodePoint(codePoint);
		return buffer;
	}

	private static void checkCount(int count) {
		if (count < 0)
			throw new IllegalArgumentException("count is negative: " + count);
	}
}
