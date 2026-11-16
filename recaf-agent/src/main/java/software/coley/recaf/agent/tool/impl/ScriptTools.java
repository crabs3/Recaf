package software.coley.recaf.agent.tool.impl;

import jakarta.annotation.Nonnull;
import software.coley.recaf.agent.script.ScriptRunner;
import software.coley.recaf.agent.tool.AgentTool;
import software.coley.recaf.agent.tool.ToolContext;
import software.coley.recaf.agent.tool.ToolException;
import software.coley.recaf.agent.tool.ToolProvider;
import software.coley.recaf.agent.tool.ToolSchema;
import software.coley.recaf.services.compile.CompilerDiagnostic;
import software.coley.recaf.services.script.ScriptEngine;
import software.coley.recaf.services.script.ScriptResult;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Tools for automating work on the workspace with scripts.
 *
 * @author Recaf agent tooling
 */
public final class ScriptTools implements ToolProvider {
	private static final int DEFAULT_TIMEOUT_SECONDS = 60;
	private static final int MAX_TIMEOUT_SECONDS = 900;
	private static final String HELP = """
			Scripts are JavaScript (ES6) run by an interpreter inside Recaf, with access to Java.

			Globals
			  recaf            Helper API, see below.
			  workspace        The open software.coley.recaf.workspace.model.Workspace.
			  tools.call(name, {args})   Calls another tool, returns its text. Throws if the tool reports an error.
			  print(a, b, ...) Appends a line to the output. console.log is the same.
			The value of the script's last expression is appended to the output after "=> ".

			recaf
			  recaf.classNames()                 java.util.List of all class names, sorted
			  recaf.classNames(regex)            ... of the names containing a match
			  recaf.jvmClass(name)               JvmClassInfo (DEX classes are translated)
			  recaf.classBytes(name)             byte[] class file
			  recaf.putClass(bytes)              add or replace a JVM class from class file bytes, returns its name
			  recaf.fileNames() / fileBytes(path) / fileText(path) / putFile(path, bytesOrText)
			  recaf.service(className)           any Recaf service by fully qualified class name
			  recaf.checkCancelled()             call in long loops so the user can stop the script

			Java interop
			  Java classes are reached by full name, for example java.util.HashMap, or bound to a short name:
			    var ClassReader = Packages.org.objectweb.asm.ClassReader;
			  Java lists and arrays are indexed with [i] or .get(i) and have .size() or .length, they are not JS arrays:
			    var names = recaf.classNames(); for (var i = 0; i < names.size(); i++) print(names.get(i));
			  Java strings are not JS strings, wrap with String(x) before using JS string methods.
			  A JS function can be passed where Java expects a single-method interface.
			  Available: java.lang, java.util, java.io, java.nio, java.math, java.text, java.time, java.security,
			  javax.crypto, org.objectweb.asm (ASM, including .tree and .commons), and Recaf's own classes.
			  Not available: networking, processes, reflection, class loading, java.lang.System.

			Example: count methods per class, largest first
			  var rows = [];
			  var names = recaf.classNames();
			  for (var i = 0; i < names.size(); i++) {
			    var info = recaf.jvmClass(names.get(i));
			    rows.push([info.getMethods().size(), String(info.getName())]);
			  }
			  rows.sort(function(a, b) { return b[0] - a[0]; });
			  rows.slice(0, 20).forEach(function(r) { print(r[0], r[1]); });

			Example: rewrite a string constant in one class with ASM
			  var asm = Packages.org.objectweb.asm;
			  var reader = new asm.ClassReader(recaf.classBytes("com/example/Config"));
			  var node = new asm.tree.ClassNode();
			  reader.accept(node, 0);
			  for (var m = 0; m < node.methods.size(); m++) {
			    var insns = node.methods.get(m).instructions;
			    for (var insn = insns.getFirst(); insn != null; insn = insn.getNext())
			      if (insn instanceof asm.tree.LdcInsnNode && "http://old.example" == insn.cst)
			        insn.cst = "http://new.example";
			  }
			  var writer = new asm.ClassWriter(0);
			  node.accept(writer);
			  recaf.putClass(writer.toByteArray());

			Example: decode strings with the program's own algorithm, reimplemented in the script
			  function decode(s) { var out = ""; for (var i = 0; i < s.length; i++) out += String.fromCharCode(s.charCodeAt(i) ^ 0x2a); return out; }
			  print(decode("some encoded text"));
			""";
	private final ToolContext context;
	private final ScriptRunner runner;

	/**
	 * @param context
	 * 		Context the tools operate in.
	 */
	public ScriptTools(@Nonnull ToolContext context) {
		this.context = context;
		this.runner = new ScriptRunner(context);
	}

	@Nonnull
	@Override
	public List<AgentTool> tools() {
		return List.of(runScript(), scriptHelp());
	}

	@Nonnull
	private AgentTool runScript() {
		return AgentTool.builder("run_script")
				.description("Run a JavaScript script against the open workspace. Use it for work that would take many " +
						"individual tool calls or that no tool offers: computing statistics over all classes, bulk bytecode " +
						"edits with ASM, decoding or decrypting data by reimplementing the program's algorithm, or chaining " +
						"other tools with tools.call(name, {args}). Scripts have the globals recaf, workspace, tools and " +
						"print, and limited access to Java classes. Call script_help once before writing your first script, " +
						"it documents the API and the Java interop rules. Returns what the script printed and the value of " +
						"its last expression. The script can modify the workspace.")
				.schema(ToolSchema.object()
						.string("code", "JavaScript source to run.")
						.optChoice("language", "Script language. 'javascript' (default) works everywhere. 'java' runs a native " +
								"Recaf Java script, which needs a desktop JDK and must be enabled by the user.", "javascript", "java")
						.optInteger("timeout_seconds", "Time limit for the script. Default " + DEFAULT_TIMEOUT_SECONDS +
								", maximum " + MAX_TIMEOUT_SECONDS + "."))
				.mutating()
				.handler(args -> {
					// Fail early with the standard message when there is nothing to script against.
					context.workspace();
					String code = args.string("code");
					int timeout = Math.max(1, Math.min(MAX_TIMEOUT_SECONDS, args.optInt("timeout_seconds", DEFAULT_TIMEOUT_SECONDS)));
					String language = args.optString("language", "javascript");
					if ("java".equalsIgnoreCase(language))
						return runJava(code, timeout);
					return runner.run(code, timeout * 1000L);
				})
				.build();
	}

	@Nonnull
	private AgentTool scriptHelp() {
		return AgentTool.builder("script_help")
				.description("Show the reference for run_script: the globals available to scripts, the helper API, " +
						"the rules for using Java classes from JavaScript, and worked examples. Read it before writing a script.")
				.handler(args -> HELP)
				.build();
	}

	/**
	 * Runs a native Recaf script: Java source compiled with the JDK's compiler and executed with the full privileges
	 * of the Recaf process.
	 */
	@Nonnull
	private String runJava(@Nonnull String code, int timeoutSeconds) throws Exception {
		if (!context.unrestrictedScripts())
			throw new ToolException("Java scripts run without a sandbox and are turned off. " +
					"Use a JavaScript script, or ask the user to enable unrestricted scripts.");
		ScriptResult result;
		try {
			result = context.service(ScriptEngine.class).run(code).get(timeoutSeconds, TimeUnit.SECONDS);
		} catch (TimeoutException ex) {
			throw new ToolException("The Java script did not finish within " + timeoutSeconds + " seconds.");
		}
		if (result.wasCompileFailure())
			throw new ToolException("The Java script did not compile:\n" + result.getCompileDiagnostics().stream()
					.map(ScriptTools::format)
					.collect(Collectors.joining("\n")));
		if (result.wasCancelled())
			throw new ToolException("The Java script was cancelled.");
		if (result.wasRuntimeError())
			throw new ToolException("The Java script threw: " + result.getRuntimeThrowable());
		return "The Java script ran successfully. Java scripts return no output, use tools to inspect what it changed.";
	}

	@Nonnull
	private static String format(@Nonnull CompilerDiagnostic diagnostic) {
		return (diagnostic.line() > 0 ? "line " + diagnostic.line() + ": " : "") + diagnostic.message();
	}
}
