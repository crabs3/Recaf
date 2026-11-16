package software.coley.recaf.agent.chat;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import software.coley.recaf.agent.tool.AgentTool;
import software.coley.recaf.agent.tool.ToolArgs;

/**
 * Asks the user whether the agent may run a tool that modifies the workspace.
 *
 * @author Recaf agent tooling
 */
@FunctionalInterface
public interface ToolApprover {
	/**
	 * Called on the agent's thread, which waits for the answer.
	 *
	 * @param tool
	 * 		Tool the agent wants to run.
	 * @param args
	 * 		Arguments of the call.
	 *
	 * @return The user's decision.
	 */
	@Nonnull
	Decision approve(@Nonnull AgentTool tool, @Nonnull ToolArgs args);

	/**
	 * Outcome of asking the user.
	 *
	 * @param allowed
	 *        {@code true} to run the tool.
	 * @param reason
	 * 		Optional note from the user for the agent when declining.
	 */
	record Decision(boolean allowed, @Nullable String reason) {
		public static final Decision ALLOW = new Decision(true, null);
		public static final Decision DENY = new Decision(false, null);
	}
}
