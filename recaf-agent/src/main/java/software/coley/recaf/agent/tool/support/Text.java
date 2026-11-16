package software.coley.recaf.agent.tool.support;

import jakarta.annotation.Nonnull;

import java.util.List;
import java.util.function.Function;

/**
 * Formatting helpers that keep tool results compact and pageable.
 * <p>
 * Tool results stay in the model's context for the rest of a conversation, so every tool that can produce a lot of
 * text returns a window of it together with an explicit note on how to get the rest. Nothing is cut silently.
 *
 * @author Recaf agent tooling
 */
public final class Text {
	private Text() {}

	/**
	 * Formats one page of a list.
	 *
	 * @param items
	 * 		All items.
	 * @param offset
	 * 		Index of the first item to show.
	 * @param limit
	 * 		Maximum number of items to show.
	 * @param noun
	 * 		Plural noun describing the items, such as {@code "classes"}.
	 * @param formatter
	 * 		Maps an item to its line.
	 * @param <T>
	 * 		Item type.
	 *
	 * @return The lines of the page, preceded by a summary and followed by a paging hint when more items remain.
	 */
	@Nonnull
	public static <T> String page(@Nonnull List<T> items, int offset, int limit, @Nonnull String noun,
	                              @Nonnull Function<T, String> formatter) {
		int total = items.size();
		if (total == 0)
			return "No " + noun + " found.";
		if (offset >= total)
			return "No " + noun + " at offset " + offset + " (there are " + total + " in total).";
		int end = Math.min(total, offset + limit);
		StringBuilder sb = new StringBuilder();
		sb.append("Showing ").append(noun).append(' ').append(offset + 1).append('-').append(end)
				.append(" of ").append(total).append(":\n");
		for (int i = offset; i < end; i++)
			sb.append(formatter.apply(items.get(i))).append('\n');
		if (end < total)
			sb.append("[").append(total - end).append(" more. Call again with offset=").append(end).append(" to continue.]");
		return sb.toString().stripTrailing();
	}

	/**
	 * Returns a window of lines of a text.
	 *
	 * @param text
	 * 		Full text.
	 * @param startLine
	 * 		First line to include, starting at 1.
	 * @param maxLines
	 * 		Maximum number of lines to include.
	 * @param lineNumbers
	 *        {@code true} to prefix each line with its number.
	 *
	 * @return The requested lines, followed by a paging hint when more lines remain.
	 */
	@Nonnull
	public static String lines(@Nonnull String text, int startLine, int maxLines, boolean lineNumbers) {
		String[] lines = text.split("\r?\n", -1);
		int total = lines.length;
		// A trailing newline yields an empty last element that is not a real line.
		if (total > 0 && lines[total - 1].isEmpty())
			total--;
		int start = Math.max(1, startLine);
		if (start > total)
			return "[No line " + start + ". The text has " + total + " lines.]";
		int end = Math.min(total, start + maxLines - 1);
		int width = String.valueOf(end).length();
		StringBuilder sb = new StringBuilder();
		for (int i = start; i <= end; i++) {
			if (lineNumbers) {
				String number = String.valueOf(i);
				for (int pad = number.length(); pad < width; pad++)
					sb.append(' ');
				sb.append(number).append("  ");
			}
			sb.append(lines[i - 1]).append('\n');
		}
		if (end < total)
			sb.append("[Lines ").append(start).append('-').append(end).append(" of ").append(total)
					.append(". Call again with start_line=").append(end + 1).append(" to continue.]");
		else if (start > 1)
			sb.append("[Lines ").append(start).append('-').append(end).append(" of ").append(total).append(".]");
		return sb.toString().stripTrailing();
	}

	/**
	 * @param text
	 * 		Text to shorten.
	 * @param maxLength
	 * 		Maximum length of the result.
	 *
	 * @return The text, shortened with a visible marker if it exceeds the maximum length.
	 */
	@Nonnull
	public static String shorten(@Nonnull String text, int maxLength) {
		if (text.length() <= maxLength)
			return text;
		return text.substring(0, Math.max(0, maxLength - 1)) + "…";
	}

	/**
	 * @param text
	 * 		Text to quote.
	 *
	 * @return The text as a Java-style string literal on a single line.
	 */
	@Nonnull
	public static String quote(@Nonnull String text) {
		StringBuilder sb = new StringBuilder(text.length() + 2).append('"');
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			switch (c) {
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> {
					if (c < 0x20 || c == 0x7F)
						sb.append(String.format("\\u%04x", (int) c));
					else
						sb.append(c);
				}
			}
		}
		return sb.append('"').toString();
	}

	/**
	 * @param bytes
	 * 		Size in bytes.
	 *
	 * @return Human-readable size.
	 */
	@Nonnull
	public static String size(long bytes) {
		if (bytes < 1024)
			return bytes + " B";
		if (bytes < 1024 * 1024)
			return String.format("%.1f KB", bytes / 1024.0);
		return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
	}
}
