package software.coley.recaf.agent.chat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import software.coley.recaf.agent.tool.AgentTool;
import software.coley.recaf.agent.tool.AgentToolbox;
import software.coley.recaf.agent.tool.ToolArgs;
import software.coley.recaf.agent.tool.ToolResult;

import java.util.function.BooleanSupplier;

/**
 * Runs the tool calls a model asks for, subject to the approval policy, and reports them to the listener.
 * Shared by the agents so that the policy is the same whichever API the model is reached through.
 *
 * @author Recaf agent tooling
 */
public final class ToolCallRunner {
	private final AgentToolbox toolbox;
	private final ApprovalMode approvalMode;
	private final AgentListener listener;
	private final ToolApprover approver;
	private final BooleanSupplier cancelled;

	/**
	 * @param toolbox
	 * 		Tools to run.
	 * @param approvalMode
	 * 		Policy for tools that modify the workspace.
	 * @param listener
	 * 		Receiver of the calls and their results.
	 * @param approver
	 * 		Asked before running modifying tools when the policy requires it.
	 * 		When {@code null}, such tools are declined under that policy.
	 * @param cancelled
	 * 		Tells whether the user stopped the agent.
	 */
	public ToolCallRunner(@Nonnull AgentToolbox toolbox, @Nonnull ApprovalMode approvalMode, @Nonnull AgentListener listener,
	                      @Nullable ToolApprover approver, @Nonnull BooleanSupplier cancelled) {
		this.toolbox = toolbox;
		this.approvalMode = approvalMode;
		this.listener = listener;
		this.approver = approver;
		this.cancelled = cancelled;
	}

	/**
	 * Runs one tool call. Never throws: whatever goes wrong becomes an error result for the model to read.
	 *
	 * @param callId
	 * 		Identifier the model gave the call.
	 * @param toolName
	 * 		Name of the tool to run.
	 * @param input
	 * 		Parsed arguments, or {@code null} when what the model sent could not be parsed.
	 * 		Models are not trusted to send a JSON object.
	 * @param rawInput
	 * 		The arguments as the model sent them, quoted back to it when they are unusable.
	 *
	 * @return Result to hand back to the model.
	 */
	@Nonnull
	public ToolResult run(@Nonnull String callId, @Nonnull String toolName, @Nullable JsonElement input, @Nonnull String rawInput) {
		if (cancelled.getAsBoolean())
			return ToolResult.error("Cancelled by the user before this tool ran.");

		listener.onToolCallReady(callId, toolName, input == null ? "{}" : input.toString());
		long start = System.currentTimeMillis();

		ToolResult result;
		AgentTool tool = toolbox.tool(toolName);
		if (input == null || !input.isJsonObject()) {
			JsonObject invalid = new JsonObject();
			invalid.addProperty("INVALID_JSON", rawInput);
			result = ToolResult.error("The tool input was not a JSON object and the tool was not run: " + invalid);
		} else if (tool != null && !tool.readOnly() && approvalMode != ApprovalMode.ALLOW_CHANGES) {
			result = gate(tool, input.getAsJsonObject());
		} else {
			result = toolbox.call(toolName, input.getAsJsonObject());
		}
		listener.onToolCallFinished(callId, result.text(), result.error(), System.currentTimeMillis() - start);
		return result;
	}

	/**
	 * Applies the approval policy to a modifying tool call.
	 */
	@Nonnull
	private ToolResult gate(@Nonnull AgentTool tool, @Nonnull JsonObject input) {
		if (approvalMode == ApprovalMode.READ_ONLY)
			return ToolResult.error("Not run: the user has restricted you to read-only tools, and '" + tool.name() +
					"' modifies the workspace. Describe the change you would make instead.");
		if (approver == null)
			return ToolResult.error("Not run: '" + tool.name() + "' modifies the workspace and there is nobody to approve it.");
		ToolApprover.Decision decision = approver.approve(tool, new ToolArgs(input));
		if (cancelled.getAsBoolean())
			return ToolResult.error("Cancelled by the user before this tool ran.");
		if (!decision.allowed())
			return ToolResult.error("Not run: the user declined this call to '" + tool.name() + "'." +
					(decision.reason() == null || decision.reason().isBlank() ? "" : " They said: " + decision.reason()));
		return toolbox.call(tool.name(), input);
	}
}
