package software.coley.recaf.agent.openai;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for {@link ThinkTagSplitter}.
 */
class ThinkTagSplitterTest {
	/** Feeds the pieces through a splitter and returns the reasoning and the reply it made of them. */
	private static List<String> split(String... pieces) {
		StringBuilder text = new StringBuilder();
		StringBuilder thinking = new StringBuilder();
		ThinkTagSplitter splitter = new ThinkTagSplitter(text::append, thinking::append);
		for (String piece : pieces)
			splitter.accept(piece);
		splitter.finish();
		return List.of(thinking.toString(), text.toString());
	}

	@Test
	void separatesLeadingReasoningFromTheReply() {
		assertEquals(List.of("", "Just an answer."), split("Just an answer."));
		assertEquals(List.of("why", "Answer."), split("<think>why</think>Answer."));
		assertEquals(List.of("why", "Answer."), split("\n<think>why</think>\n\nAnswer."));
	}

	@Test
	void copesWithTagsSplitAcrossPieces() {
		assertEquals(List.of("step one, step two", "Answer."), split("<", "thi", "nk>step one,", " step two<", "/thi", "nk>", "\n", "Answer."));
		// Every single character on its own.
		String whole = "<think>a < b and c > d</think>\nresult";
		assertEquals(List.of("a < b and c > d", "result"), split(whole.split("")));
	}

	@Test
	void leavesTextThatOnlyLooksLikeATagAlone() {
		assertEquals(List.of("", "<thinking about it> yes"), split("<thi", "nking about it> yes"));
		assertEquals(List.of("", "a <think>b</think> c"), split("a <think>b</think> c"));
		assertEquals(List.of("", "<"), split("<"));
		// Reasoning that never ends is still reasoning, not part of the reply.
		assertEquals(List.of("unfinished", ""), split("<think>unfin", "ished"));
		assertEquals(List.of("x</thi", ""), split("<think>x</thi"));
	}
}
