package software.coley.recaf.agent.script;

import jakarta.annotation.Nonnull;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.EcmaError;
import org.mozilla.javascript.EvaluatorException;
import org.mozilla.javascript.JavaScriptException;
import org.mozilla.javascript.NativeJSON;
import org.mozilla.javascript.RhinoException;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.mozilla.javascript.WrappedException;
import software.coley.recaf.agent.tool.ToolContext;
import software.coley.recaf.agent.tool.ToolException;

/**
 * Runs JavaScript against the open workspace.
 * <p>
 * Recaf's native scripts are Java sources compiled with {@code javac}, which only works on a JDK. The agent tooling
 * needs scripting that behaves the same on a phone, so it embeds the Rhino JavaScript engine in interpreted mode:
 * no bytecode is generated, which Android could not load anyway, and every interpreted instruction can be counted,
 * which gives reliable timeouts and cancellation.
 * <p>
 * Scripts see these globals:
 * <ul>
 *     <li>{@code recaf}: the {@link ScriptApi}</li>
 *     <li>{@code workspace}: the open workspace</li>
 *     <li>{@code tools.call(name, {arguments})}: calls another tool and returns its text result</li>
 *     <li>{@code print(...)} and {@code console.log(...)}: append to the output returned to the caller</li>
 *     <li>{@code Packages}, {@code java}, {@code importPackage}: Java interop, limited by {@link ScriptSandbox}</li>
 * </ul>
 *
 * @author Recaf agent tooling
 */
public final class ScriptRunner {
	/** Upper bound of the output a script can produce. */
	static final int MAX_OUTPUT_CHARS = 40_000;
	private static final int INSTRUCTIONS_PER_CHECK = 20_000;
	private static final String BOOTSTRAP = """
			var console = { log: print, info: print, warn: print, error: print };
			var tools = { call: function(name, args) { return String(recaf.callTool(name, args == null ? null : JSON.stringify(args))); } };
			""";
	private final ToolContext context;

	/**
	 * @param context
	 * 		Context scripts operate in.
	 */
	public ScriptRunner(@Nonnull ToolContext context) {
		this.context = context;
	}

	/**
	 * Runs a script on the calling thread.
	 *
	 * @param code
	 * 		JavaScript source.
	 * @param timeoutMillis
	 * 		Time after which the script is stopped.
	 *
	 * @return What the script printed, followed by the value of its last expression if it has one.
	 *
	 * @throws ToolException
	 * 		When the script fails, times out or is cancelled. The message includes what it printed until then.
	 */
	@Nonnull
	public String run(@Nonnull String code, long timeoutMillis) {
		StringBuilder output = new StringBuilder();
		long deadline = System.currentTimeMillis() + timeoutMillis;
		ContextFactory factory = new ContextFactory() {
			@Override
			protected Context makeContext() {
				Context cx = super.makeContext();
				cx.setInterpretedMode(true);
				cx.setLanguageVersion(Context.VERSION_ES6);
				cx.setInstructionObserverThreshold(INSTRUCTIONS_PER_CHECK);
				cx.setMaximumInterpreterStackDepth(2_000);
				cx.setClassShutter(new ScriptSandbox());
				return cx;
			}

			@Override
			protected void observeInstructionCount(Context cx, int instructionCount) {
				// Errors, unlike exceptions, cannot be caught by the script.
				if (context.isCancelled())
					throw new ScriptStopped("cancelled by the user");
				if (System.currentTimeMillis() > deadline)
					throw new ScriptStopped("stopped after exceeding its time limit of " + (timeoutMillis / 1000) + " seconds");
			}
		};

		try {
			String result = factory.call(cx -> {
				ScriptableObject scope = cx.initStandardObjects();
				ScriptApi api = new ScriptApi(context, output);
				ScriptableObject.putProperty(scope, "recaf", Context.javaToJS(api, scope));
				ScriptableObject.putProperty(scope, "workspace", Context.javaToJS(context.workspace(), scope));
				ScriptableObject.putProperty(scope, "print", new PrintFunction(output));
				cx.evaluateString(scope, BOOTSTRAP, "<bootstrap>", 1, null);
				Object value = cx.evaluateString(scope, code, "script", 1, null);
				return describe(cx, scope, value);
			});
			if (result != null) {
				if (output.length() > 0 && output.charAt(output.length() - 1) != '\n')
					output.append('\n');
				output.append("=> ").append(result);
			}
			if (output.length() == 0)
				return "The script finished without output. Use print(...) to report results.";
			return finish(output);
		} catch (ScriptStopped stopped) {
			throw new ToolException("The script was " + stopped.getMessage() + "." + printedSoFar(output));
		} catch (JavaScriptException ex) {
			// A value thrown by the script itself.
			throw new ToolException("The script threw: " + ex.details() + location(ex) + printedSoFar(output));
		} catch (WrappedException ex) {
			Throwable cause = ex.getWrappedException();
			if (cause instanceof ToolException toolException)
				throw new ToolException(toolException.getMessage() + location(ex) + printedSoFar(output));
			throw new ToolException("A Java call made by the script failed: " + cause + location(ex) + printedSoFar(output));
		} catch (EcmaError | EvaluatorException ex) {
			throw new ToolException("Script error: " + ex.details() + location(ex) + printedSoFar(output));
		}
	}

	/**
	 * @return Readable form of the script's completion value, or {@code null} when it has none worth showing.
	 */
	private static String describe(@Nonnull Context cx, @Nonnull Scriptable scope, Object value) {
		if (value == null || value instanceof Undefined)
			return null;
		// Plain objects and arrays read best as JSON, everything else through string conversion.
		if (value instanceof Scriptable scriptable && !(value instanceof BaseFunction)
				&& ("Object".equals(scriptable.getClassName()) || "Array".equals(scriptable.getClassName()))) {
			Object json = NativeJSON.stringify(cx, scope, value, null, 2);
			if (json instanceof CharSequence)
				return json.toString();
		}
		return Context.toString(value);
	}

	@Nonnull
	private static String finish(@Nonnull StringBuilder output) {
		if (output.length() <= MAX_OUTPUT_CHARS)
			return output.toString().stripTrailing();
		return output.substring(0, MAX_OUTPUT_CHARS) + "\n[Output truncated at " + MAX_OUTPUT_CHARS + " characters. Print less, or summarize in the script.]";
	}

	@Nonnull
	private static String printedSoFar(@Nonnull StringBuilder output) {
		if (output.length() == 0)
			return "";
		return "\n\nOutput before the failure:\n" + finish(output);
	}

	@Nonnull
	private static String location(@Nonnull RhinoException ex) {
		return ex.lineNumber() > 0 ? " (script line " + ex.lineNumber() + ")" : "";
	}

	/**
	 * The {@code print} global: joins its arguments with spaces and appends them to the output as a line.
	 */
	private static final class PrintFunction extends BaseFunction {
		private final transient StringBuilder output;

		private PrintFunction(@Nonnull StringBuilder output) {
			this.output = output;
		}

		@Override
		public Object call(Context cx, Scriptable scope, Scriptable thisObj, Object[] args) {
			if (output.length() >= MAX_OUTPUT_CHARS)
				return Undefined.instance;
			for (int i = 0; i < args.length; i++) {
				if (i > 0)
					output.append(' ');
				output.append(Context.toString(args[i]));
			}
			output.append('\n');
			return Undefined.instance;
		}
	}

	/**
	 * Raised from the instruction observer to stop a script. An {@link Error} so that scripts cannot catch it.
	 */
	private static final class ScriptStopped extends Error {
		private ScriptStopped(@Nonnull String message) {
			super(message, null, false, false);
		}
	}
}
