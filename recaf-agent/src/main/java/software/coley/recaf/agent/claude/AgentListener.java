package software.coley.recaf.agent.claude;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * Receives what a {@link ClaudeAgent} does as it happens, for display.
 * All methods are called on the thread running the agent.
 *
 * @author Recaf agent tooling
 */
public interface AgentListener {
	/**
	 * @param text
	 * 		Next piece of the agent's reply.
	 */
	default void onText(@Nonnull String text) {}

	/**
	 * @param text
	 * 		Next piece of the summary of the agent's reasoning, or of a progress note between tool calls.
	 */
	default void onThinking(@Nonnull String text) {}

	/**
	 * The agent decided to call a tool. Its arguments are not complete yet.
	 *
	 * @param callId
	 * 		Identifier of the call.
	 * @param toolName
	 * 		Name of the tool.
	 */
	default void onToolCallStarted(@Nonnull String callId, @Nonnull String toolName) {}

	/**
	 * A tool call's arguments are complete and it is about to run.
	 *
	 * @param callId
	 * 		Identifier of the call.
	 * @param toolName
	 * 		Name of the tool.
	 * @param argumentsJson
	 * 		Arguments as JSON.
	 */
	default void onToolCallReady(@Nonnull String callId, @Nonnull String toolName, @Nonnull String argumentsJson) {}

	/**
	 * A tool call finished.
	 *
	 * @param callId
	 * 		Identifier of the call.
	 * @param result
	 * 		Result text.
	 * @param error
	 *        {@code true} when the call failed or was declined.
	 * @param durationMillis
	 * 		Time the call took.
	 */
	default void onToolCallFinished(@Nonnull String callId, @Nonnull String result, boolean error, long durationMillis) {}

	/**
	 * One request to the model completed.
	 *
	 * @param usage
	 * 		Token usage of the session so far.
	 */
	default void onUsage(@Nonnull Usage usage) {}

	/**
	 * A safety classifier declined the request on the configured model and another model answered instead.
	 *
	 * @param fromModel
	 * 		Model that declined.
	 * @param toModel
	 * 		Model that answered.
	 */
	default void onModelFallback(@Nonnull String fromModel, @Nonnull String toModel) {}

	/**
	 * Token usage of an agent session.
	 *
	 * @param inputTokens
	 * 		Input tokens that were neither read from nor written to the prompt cache.
	 * @param outputTokens
	 * 		Output tokens, thinking included.
	 * @param cacheReadTokens
	 * 		Input tokens read from the prompt cache.
	 * @param cacheWriteTokens
	 * 		Input tokens written to the prompt cache.
	 * @param estimatedCost
	 * 		Estimated cost in US dollars, or {@code null} when the model's prices are not known.
	 */
	record Usage(long inputTokens, long outputTokens, long cacheReadTokens, long cacheWriteTokens,
	             @Nullable Double estimatedCost) {}
}
