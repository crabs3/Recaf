package software.coley.recaf.agent.tool;

import com.google.gson.JsonObject;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import software.coley.recaf.Recaf;
import software.coley.recaf.agent.state.AgentState;
import software.coley.recaf.agent.tool.impl.StandardTools;
import software.coley.recaf.analytics.logging.Logging;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * The set of tools available to agents, and the single entry point through which they are called.
 * <p>
 * Both the embedded Claude agent and the MCP server call tools through {@link #call(String, JsonObject)}, which
 * turns every failure into an error result so that a model can read it and adjust, bounds the size of results,
 * and notifies listeners.
 *
 * @author Recaf agent tooling
 */
public final class AgentToolbox {
	/**
	 * Upper bound of a single tool result, in characters. Results are context the model pays for on every later
	 * turn, so tools page their output well below this. The bound is a backstop for the ones that cannot.
	 */
	public static final int MAX_RESULT_CHARS = 60_000;
	private static final Logger logger = Logging.get(AgentToolbox.class);
	private final Map<String, AgentTool> tools = new LinkedHashMap<>();
	private final List<ToolCallListener> listeners = new CopyOnWriteArrayList<>();
	/**
	 * Tools can be called from several threads at once, for instance by an MCP client issuing parallel requests
	 * while the embedded agent is working. Reads may overlap, anything that modifies the workspace runs alone.
	 */
	private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
	private final ToolContext context;

	/**
	 * @param context
	 * 		Context the tools operate in.
	 */
	public AgentToolbox(@Nonnull ToolContext context) {
		this.context = context;
		context.attach(this);
	}

	/**
	 * Creates a toolbox with Recaf's standard tools.
	 *
	 * @param recaf
	 * 		Recaf instance to operate on.
	 * @param exportDirectory
	 * 		Directory that exported workspaces are written to.
	 * @param inputRoots
	 * 		Directories that files may be opened from. An empty list allows any readable path.
	 *
	 * @return Toolbox with the standard tools registered.
	 */
	@Nonnull
	public static AgentToolbox create(@Nonnull Recaf recaf, @Nonnull Path exportDirectory, @Nonnull List<Path> inputRoots) {
		ToolContext context = new ToolContext(recaf, new AgentState(), exportDirectory, inputRoots);
		AgentToolbox toolbox = new AgentToolbox(context);
		for (ToolProvider provider : StandardTools.providers(context))
			toolbox.register(provider);
		// Track whether the workspace holds modifications that an export has not captured yet.
		toolbox.addListener(new ToolCallListener() {
			@Override
			public void onToolResult(@Nonnull AgentTool tool, @Nonnull ToolArgs args, @Nonnull ToolResult result, long durationMillis) {
				if (result.error() || tool.readOnly())
					return;
				context.state().setExported(tool.name().equals("export_workspace"));
			}
		});
		return toolbox;
	}

	/**
	 * @param provider
	 * 		Group of tools to add.
	 */
	public void register(@Nonnull ToolProvider provider) {
		for (AgentTool tool : provider.tools())
			register(tool);
	}

	/**
	 * @param tool
	 * 		Tool to add.
	 */
	public void register(@Nonnull AgentTool tool) {
		if (tools.putIfAbsent(tool.name(), tool) != null)
			throw new IllegalArgumentException("Duplicate tool name: " + tool.name());
	}

	/**
	 * @return The registered tools, in registration order.
	 */
	@Nonnull
	public Collection<AgentTool> tools() {
		return Collections.unmodifiableCollection(new ArrayList<>(tools.values()));
	}

	/**
	 * @param name
	 * 		Tool name.
	 *
	 * @return The tool of that name, or {@code null} if there is none.
	 */
	@Nullable
	public AgentTool tool(@Nonnull String name) {
		return tools.get(name);
	}

	/**
	 * @return Context the tools operate in.
	 */
	@Nonnull
	public ToolContext context() {
		return context;
	}

	/**
	 * @param listener
	 * 		Listener to notify of tool calls.
	 */
	public void addListener(@Nonnull ToolCallListener listener) {
		listeners.add(listener);
	}

	/**
	 * @param listener
	 * 		Listener to remove.
	 */
	public void removeListener(@Nonnull ToolCallListener listener) {
		listeners.remove(listener);
	}

	/**
	 * Calls a tool. Never throws: every failure is reported through the result.
	 *
	 * @param name
	 * 		Name of the tool to call.
	 * @param arguments
	 * 		Arguments for the tool.
	 *
	 * @return Outcome of the call.
	 */
	@Nonnull
	public ToolResult call(@Nonnull String name, @Nullable JsonObject arguments) {
		AgentTool tool = tools.get(name);
		if (tool == null)
			return ToolResult.error("Unknown tool '" + name + "'. Available tools: " + String.join(", ", tools.keySet()));

		// Tools may call other tools (scripts do), which the reentrant lock allows as long as a read-only tool does
		// not try to start a mutating one. That case is reported instead of deadlocking.
		if (!tool.readOnly() && lock.getReadHoldCount() > 0 && !lock.isWriteLockedByCurrentThread())
			return ToolResult.error("The mutating tool '" + name + "' cannot be called from within a read-only tool call.");

		ToolArgs args = new ToolArgs(arguments);
		String invalid = ToolInputValidator.validate(tool.inputSchema(), args.json());
		if (invalid != null)
			return ToolResult.error("Invalid arguments for '" + name + "': " + invalid);
		for (ToolCallListener listener : listeners)
			notifyQuietly(() -> listener.onToolCall(tool, args));

		long start = System.currentTimeMillis();
		ToolResult result;
		Lock callLock = tool.readOnly() ? lock.readLock() : lock.writeLock();
		callLock.lock();
		try {
			result = ToolResult.ok(bound(tool.execute(args)));
		} catch (ToolException ex) {
			result = ToolResult.error(ex.getMessage());
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			result = ToolResult.error("The tool call was interrupted.");
		} catch (Throwable t) {
			// Unexpected failures still go back to the model, which can often route around them.
			logger.warn("Tool '{}' failed unexpectedly", name, t);
			result = ToolResult.error(describe(t));
		} finally {
			callLock.unlock();
		}

		long duration = System.currentTimeMillis() - start;
		ToolResult finalResult = result;
		for (ToolCallListener listener : listeners)
			notifyQuietly(() -> listener.onToolResult(tool, args, finalResult, duration));
		return result;
	}

	@Nonnull
	private static String bound(@Nonnull String text) {
		if (text.length() <= MAX_RESULT_CHARS)
			return text;
		int omitted = text.length() - MAX_RESULT_CHARS;
		return text.substring(0, MAX_RESULT_CHARS) + "\n\n[Result truncated: " + omitted +
				" more characters were omitted. Narrow the request or use paging arguments to see the rest.]";
	}

	@Nonnull
	private static String describe(@Nonnull Throwable t) {
		StringBuilder sb = new StringBuilder("The tool failed unexpectedly: ");
		Throwable current = t;
		int depth = 0;
		while (current != null && depth < 4) {
			if (depth > 0)
				sb.append(" <- caused by: ");
			sb.append(current.getClass().getSimpleName());
			if (current.getMessage() != null)
				sb.append(": ").append(current.getMessage());
			current = current.getCause() == current ? null : current.getCause();
			depth++;
		}
		return sb.toString();
	}

	private static void notifyQuietly(@Nonnull Runnable action) {
		try {
			action.run();
		} catch (Throwable t) {
			logger.warn("Tool call listener failed", t);
		}
	}
}
