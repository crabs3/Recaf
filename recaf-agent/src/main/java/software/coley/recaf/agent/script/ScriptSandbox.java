package software.coley.recaf.agent.script;

import jakarta.annotation.Nonnull;
import org.mozilla.javascript.ClassShutter;

import java.util.List;

/**
 * Decides which Java classes a script may touch.
 * <p>
 * Scripts exist so that an agent can automate work on the open workspace: walk classes, rewrite bytecode with ASM,
 * decode data. They are written by a model that has been reading an untrusted program, so they get what that work
 * needs and nothing that would let a misled agent reach beyond it: no network, no processes, no reflection or
 * class loading, no access to the host application's own classes.
 * <p>
 * This is a policy on what the scripting bridge exposes, not a security boundary against a determined attacker
 * with arbitrary code execution. Its job is to make sure there is no such execution to begin with.
 *
 * @author Recaf agent tooling
 */
public final class ScriptSandbox implements ClassShutter {
	private static final List<String> ALLOWED_PREFIXES = List.of(
			// Core language and utilities
			"java.lang.", "java.util.", "java.math.", "java.text.", "java.time.", "java.io.", "java.nio.",
			// Decoding and decrypting data found in programs
			"java.security.", "javax.crypto.",
			// Bytecode and Recaf itself
			"org.objectweb.asm.", "software.coley.", "me.darknet.", "com.google.gson.",
			// Implementation classes behind java.nio.file and java.nio.charset interfaces
			"sun.nio.fs.", "sun.nio.cs."
	);
	private static final List<String> DENIED_PREFIXES = List.of(
			// Processes, class loading, reflection, native access
			"java.lang.Runtime", "java.lang.Process", "java.lang.ClassLoader", "java.lang.System",
			"java.lang.reflect.", "java.lang.invoke.", "java.lang.foreign.", "java.lang.instrument.", "java.lang.management.",
			"java.lang.Compiler", "java.lang.SecurityManager", "java.lang.Shutdown", "java.lang.Module",
			// Network capable channels
			"java.nio.channels.",
			// The host application and the agent machinery, which hold credentials
			"software.coley.recaf.agent.", "software.coley.recaf.android.",
			// The scripting engine's own internals
			"org.mozilla."
	);

	@Override
	public boolean visibleToScripts(@Nonnull String className) {
		// Arrays are as visible as their element type: '[B', '[[I', '[Ljava.lang.String;'
		if (className.startsWith("[")) {
			int dimensions = className.lastIndexOf('[') + 1;
			if (className.length() == dimensions + 1)
				return true; // Primitive element type
			if (className.charAt(dimensions) == 'L' && className.endsWith(";"))
				return visibleToScripts(className.substring(dimensions + 1, className.length() - 1));
			return false;
		}
		// The one piece of the agent machinery scripts are meant to hold: their own API object.
		if (className.equals(ScriptApi.class.getName()))
			return true;
		for (String denied : DENIED_PREFIXES)
			if (className.startsWith(denied))
				return false;
		for (String allowed : ALLOWED_PREFIXES)
			if (className.startsWith(allowed))
				return true;
		return false;
	}
}
