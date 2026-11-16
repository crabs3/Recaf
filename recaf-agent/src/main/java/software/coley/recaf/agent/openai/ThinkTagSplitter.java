package software.coley.recaf.agent.openai;

import jakarta.annotation.Nonnull;

import java.util.function.Consumer;

/**
 * Separates the reasoning that some models write into their reply, between {@code <think>} and {@code </think>} at
 * its start, from the reply itself. Models served by an API that has a field for reasoning do not need this, but
 * many locally hosted reasoning models put it in the text.
 * <p>
 * Text arrives in pieces of any size, so a tag can be split across pieces. Anything that could still turn out to
 * be part of a tag is held back until it is known.
 *
 * @author Recaf agent tooling
 */
final class ThinkTagSplitter {
	private static final String OPEN = "<think>";
	private static final String CLOSE = "</think>";
	private final Consumer<String> text;
	private final Consumer<String> thinking;
	private final StringBuilder pending = new StringBuilder();
	private State state = State.START;

	/**
	 * @param text
	 * 		Receives the reply.
	 * @param thinking
	 * 		Receives the reasoning.
	 */
	ThinkTagSplitter(@Nonnull Consumer<String> text, @Nonnull Consumer<String> thinking) {
		this.text = text;
		this.thinking = thinking;
	}

	/**
	 * @param piece
	 * 		Next piece of the model's output.
	 */
	void accept(@Nonnull String piece) {
		if (piece.isEmpty())
			return;
		if (state == State.TEXT) {
			text.accept(piece);
			return;
		}
		if (state == State.AFTER_THINKING) {
			// The line breaks that separate reasoning from reply are not part of the reply.
			int begin = 0;
			while (begin < piece.length() && (piece.charAt(begin) == '\n' || piece.charAt(begin) == '\r'))
				begin++;
			if (begin == piece.length())
				return;
			state = State.TEXT;
			text.accept(piece.substring(begin));
			return;
		}
		pending.append(piece);
		if (state == State.START) {
			// Leading whitespace in front of the tag is common and belongs to neither side.
			int begin = 0;
			while (begin < pending.length() && Character.isWhitespace(pending.charAt(begin)))
				begin++;
			String rest = pending.substring(begin);
			if (rest.startsWith(OPEN)) {
				pending.delete(0, begin + OPEN.length());
				state = State.THINKING;
			} else if (OPEN.startsWith(rest)) {
				return; // Could still become the tag.
			} else {
				flushAsText();
				return;
			}
		}
		// In the reasoning: pass on everything that cannot be the start of the closing tag.
		int close = pending.indexOf(CLOSE);
		if (close >= 0) {
			if (close > 0)
				thinking.accept(pending.substring(0, close));
			pending.delete(0, close + CLOSE.length());
			String rest = pending.toString();
			pending.setLength(0);
			state = State.AFTER_THINKING;
			accept(rest);
			return;
		}
		int keep = partialSuffix(pending, CLOSE);
		if (pending.length() > keep) {
			thinking.accept(pending.substring(0, pending.length() - keep));
			pending.delete(0, pending.length() - keep);
		}
	}

	/**
	 * Call when the output is complete, to release what was held back.
	 */
	void finish() {
		if (pending.length() == 0)
			return;
		if (state == State.THINKING)
			thinking.accept(pending.toString());
		else
			text.accept(pending.toString());
		pending.setLength(0);
	}

	private void flushAsText() {
		state = State.TEXT;
		if (pending.length() > 0)
			text.accept(pending.toString());
		pending.setLength(0);
	}

	/**
	 * @return Length of the longest end of {@code content} that is a beginning of {@code tag}.
	 */
	private static int partialSuffix(@Nonnull CharSequence content, @Nonnull String tag) {
		int max = Math.min(content.length(), tag.length() - 1);
		for (int length = max; length > 0; length--) {
			boolean matches = true;
			for (int i = 0; i < length && matches; i++)
				matches = content.charAt(content.length() - length + i) == tag.charAt(i);
			if (matches)
				return length;
		}
		return 0;
	}

	private enum State {START, THINKING, AFTER_THINKING, TEXT}
}
