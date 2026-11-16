package software.coley.recaf.agent.tool;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import software.coley.recaf.Recaf;
import software.coley.recaf.agent.state.AgentState;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.workspace.model.Workspace;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Everything a tool needs from its surroundings: Recaf's services, the open workspace, the directories it may
 * touch and the state shared between tool calls.
 *
 * @author Recaf agent tooling
 */
public final class ToolContext {
	private final Recaf recaf;
	private final AgentState state;
	private final Path exportDirectory;
	private final List<Path> inputRoots;
	private final AtomicBoolean cancelled = new AtomicBoolean();
	private volatile AgentToolbox toolbox;
	private volatile boolean unrestrictedScripts;

	/**
	 * @param recaf
	 * 		Recaf instance to operate on.
	 * @param state
	 * 		State shared between tool calls.
	 * @param exportDirectory
	 * 		Directory that exported workspaces are written to.
	 * @param inputRoots
	 * 		Directories that files may be opened from. An empty list allows any readable path.
	 */
	public ToolContext(@Nonnull Recaf recaf, @Nonnull AgentState state,
	                   @Nonnull Path exportDirectory, @Nonnull List<Path> inputRoots) {
		this.recaf = recaf;
		this.state = state;
		this.exportDirectory = exportDirectory;
		this.inputRoots = List.copyOf(inputRoots);
	}

	/**
	 * @return Recaf instance.
	 */
	@Nonnull
	public Recaf recaf() {
		return recaf;
	}

	/**
	 * @param type
	 * 		Service type.
	 * @param <T>
	 * 		Service type.
	 *
	 * @return Recaf service of the given type.
	 */
	@Nonnull
	public <T> T service(@Nonnull Class<T> type) {
		return recaf.get(type);
	}

	/**
	 * @return The open workspace.
	 *
	 * @throws ToolException
	 * 		When no workspace is open.
	 */
	@Nonnull
	public Workspace workspace() {
		Workspace workspace = workspaceOrNull();
		if (workspace == null)
			throw new ToolException("No workspace is open. Open a file first (open_workspace), " +
					"or ask the user to open one.");
		return workspace;
	}

	/**
	 * @return The open workspace, or {@code null} when none is open.
	 */
	@Nullable
	public Workspace workspaceOrNull() {
		WorkspaceManager manager = service(WorkspaceManager.class);
		return manager.hasCurrentWorkspace() ? manager.getCurrent() : null;
	}

	/**
	 * @return State shared between tool calls, such as recorded findings.
	 */
	@Nonnull
	public AgentState state() {
		return state;
	}

	/**
	 * @return Directory that exported workspaces are written to.
	 */
	@Nonnull
	public Path exportDirectory() {
		return exportDirectory;
	}

	/**
	 * @return Directories that files may be opened from. An empty list allows any readable path.
	 */
	@Nonnull
	public List<Path> inputRoots() {
		return inputRoots;
	}

	/**
	 * @return The toolbox whose tools operate in this context, which lets tools call other tools.
	 */
	@Nonnull
	public AgentToolbox toolbox() {
		AgentToolbox toolbox = this.toolbox;
		if (toolbox == null)
			throw new IllegalStateException("The context is not attached to a toolbox");
		return toolbox;
	}

	/**
	 * @param toolbox
	 * 		Toolbox whose tools operate in this context.
	 */
	void attach(@Nonnull AgentToolbox toolbox) {
		this.toolbox = toolbox;
	}

	/**
	 * @return {@code true} when scripts may run without the scripting sandbox.
	 *
	 * @see #setUnrestrictedScripts(boolean)
	 */
	public boolean unrestrictedScripts() {
		return unrestrictedScripts;
	}

	/**
	 * Controls whether scripts that run with the full privileges of the Recaf process are allowed, which is the
	 * case for Recaf's native Java scripts. Off by default: the program being analyzed is untrusted input, and an
	 * agent that was misled by it should not be one tool call away from arbitrary code execution on the host.
	 *
	 * @param unrestrictedScripts
	 *        {@code true} to allow unrestricted scripts.
	 */
	public void setUnrestrictedScripts(boolean unrestrictedScripts) {
		this.unrestrictedScripts = unrestrictedScripts;
	}

	/**
	 * Asks long-running tools to stop at their next opportunity.
	 *
	 * @param cancelled
	 * 		New cancellation state.
	 */
	public void setCancelled(boolean cancelled) {
		this.cancelled.set(cancelled);
	}

	/**
	 * @return {@code true} when the current work was cancelled.
	 */
	public boolean isCancelled() {
		return cancelled.get();
	}

	/**
	 * @throws ToolException
	 * 		When the current work was cancelled.
	 */
	public void checkCancelled() {
		if (isCancelled())
			throw new ToolException("Cancelled by the user.");
	}
}
