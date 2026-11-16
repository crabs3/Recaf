package software.coley.recaf.agent.chat;

/**
 * Policy for tool calls that modify the workspace.
 *
 * @author Recaf agent tooling
 */
public enum ApprovalMode {
	/** Every modifying tool call is put to the user first. */
	ASK_BEFORE_CHANGES,
	/** Modifying tool calls run without asking. */
	ALLOW_CHANGES,
	/** Modifying tool calls are declined, the agent can only inspect. */
	READ_ONLY
}
