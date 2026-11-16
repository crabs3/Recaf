package software.coley.recaf.agent.tool;

import jakarta.annotation.Nonnull;

/**
 * Outcome of a tool call.
 *
 * @param text
 * 		Text handed back to the model.
 * @param error
 *        {@code true} when the call failed. The text then explains the failure.
 *
 * @author Recaf agent tooling
 */
public record ToolResult(@Nonnull String text, boolean error) {
	/**
	 * @param text
	 * 		Result text.
	 *
	 * @return Successful result.
	 */
	@Nonnull
	public static ToolResult ok(@Nonnull String text) {
		return new ToolResult(text, false);
	}

	/**
	 * @param text
	 * 		Explanation of the failure.
	 *
	 * @return Failed result.
	 */
	@Nonnull
	public static ToolResult error(@Nonnull String text) {
		return new ToolResult(text, true);
	}
}
