package software.coley.recaf.agent.claude;

import jakarta.annotation.Nonnull;

import java.util.List;

/**
 * A Claude model the agent can run on.
 *
 * @param id
 * 		Model identifier sent to the API.
 * @param name
 * 		Name shown to users.
 * @param summary
 * 		One line on when to pick it.
 * @param refusalFallback
 *        {@code true} when the model supports server-side fallback to another model after a safety classifier
 * 		declines a request.
 * @param inputPrice
 * 		Price of a million input tokens in US dollars, for cost estimates.
 * @param outputPrice
 * 		Price of a million output tokens in US dollars.
 * @param cacheReadPrice
 * 		Price of a million input tokens read from the prompt cache in US dollars.
 *
 * @author Recaf agent tooling
 */
public record ClaudeModel(@Nonnull String id, @Nonnull String name, @Nonnull String summary, boolean refusalFallback,
                          double inputPrice, double outputPrice, double cacheReadPrice) {
	/** The model used unless the user picks another one. */
	public static final ClaudeModel DEFAULT = new ClaudeModel("claude-opus-5-5", "Claude Opus 5.5",
			"Recommended. Strong long-running analysis at a moderate price.", true, 4.00, 20.00, 0.20);
	private static final List<ClaudeModel> KNOWN = List.of(
			DEFAULT,
			new ClaudeModel("claude-fable-5-2", "Claude Fable 5.2",
					"Most capable, for the hardest obfuscation and largest programs. Highest price.", true, 8.00, 40.00, 0.20),
			new ClaudeModel("claude-sonnet-5-5", "Claude Sonnet 5.5",
					"Faster and cheaper, good for routine analysis.", true, 2.00, 10.00, 0.10),
			new ClaudeModel("claude-haiku-5-5", "Claude Haiku 5.5",
					"Fastest and cheapest, for quick questions about small programs.", false, 0.10, 0.50, 0.01)
	);

	/**
	 * @return The models offered in model pickers.
	 */
	@Nonnull
	public static List<ClaudeModel> known() {
		return KNOWN;
	}

	/**
	 * @param id
	 * 		Model identifier.
	 *
	 * @return The known model of that identifier. Unknown identifiers yield a model with no cost information
	 * and no refusal fallback, so that newly released or custom models can still be used.
	 */
	@Nonnull
	public static ClaudeModel of(@Nonnull String id) {
		for (ClaudeModel model : KNOWN)
			if (model.id().equals(id))
				return model;
		return new ClaudeModel(id, id, "Custom model.", false, 0, 0, 0);
	}
}
