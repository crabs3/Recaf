package software.coley.recaf.agent.tool;

import jakarta.annotation.Nonnull;

/**
 * Observer of tool calls made through an {@link AgentToolbox}, regardless of whether the embedded agent or an
 * MCP client made them. User interfaces use this to show what is being done to the workspace.
 *
 * @author Recaf agent tooling
 */
public interface ToolCallListener {
	/**
	 * @param tool
	 * 		Tool being called.
	 * @param args
	 * 		Arguments of the call.
	 */
	default void onToolCall(@Nonnull AgentTool tool, @Nonnull ToolArgs args) {}

	/**
	 * @param tool
	 * 		Tool that was called.
	 * @param args
	 * 		Arguments of the call.
	 * @param result
	 * 		Outcome of the call.
	 * @param durationMillis
	 * 		Time the call took.
	 */
	default void onToolResult(@Nonnull AgentTool tool, @Nonnull ToolArgs args, @Nonnull ToolResult result, long durationMillis) {}
}
