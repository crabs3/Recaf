package software.coley.recaf.agent.claude;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * Settings of a {@link ClaudeAgent}.
 *
 * @param apiKey
 * 		Anthropic API key.
 * @param baseUrl
 * 		API base URL, or {@code null} for the default. Useful for gateways and tests.
 * @param model
 * 		Identifier of the model to run on.
 * @param effort
 * 		How thoroughly the model works: {@code low}, {@code medium}, {@code high}, {@code xhigh} or {@code max}.
 * @param maxTokens
 * 		Upper bound of a single response, thinking included.
 * @param refusalFallback
 *        {@code true} to let the API retry on another model when a safety classifier declines a request.
 * 		Only applied to models that support it.
 * @param approvalMode
 * 		Policy for tool calls that modify the workspace.
 *
 * @author Recaf agent tooling
 */
public record AgentConfig(@Nonnull String apiKey, @Nullable String baseUrl, @Nonnull String model, @Nonnull String effort,
                          long maxTokens, boolean refusalFallback, @Nonnull ApprovalMode approvalMode) {
	/** Default effort. The recommended starting point for Claude Opus 5.5, whose API default it also is. */
	public static final String DEFAULT_EFFORT = "medium";
	/** Default response bound. Leaves room for thinking in long agentic turns, which requires streaming. */
	public static final long DEFAULT_MAX_TOKENS = 64_000;

	/**
	 * @param apiKey
	 * 		Anthropic API key.
	 *
	 * @return Configuration with the default model and settings.
	 */
	@Nonnull
	public static AgentConfig defaults(@Nonnull String apiKey) {
		return new AgentConfig(apiKey, null, ClaudeModel.DEFAULT.id(), DEFAULT_EFFORT, DEFAULT_MAX_TOKENS, true,
				ApprovalMode.ASK_BEFORE_CHANGES);
	}

	/**
	 * Policy for tool calls that modify the workspace.
	 */
	public enum ApprovalMode {
		/** Every modifying tool call is put to the user first. */
		ASK_BEFORE_CHANGES,
		/** Modifying tool calls run without asking. */
		ALLOW_CHANGES,
		/** Modifying tool calls are declined, the agent can only inspect. */
		READ_ONLY
	}
}
