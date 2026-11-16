package software.coley.recaf.agent;

import org.junit.jupiter.api.Test;
import software.coley.recaf.agent.script.ScriptSandbox;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the scripting tools and the sandbox scripts run in.
 */
class ScriptToolsTest extends AgentTestBase {
	@Test
	void scriptsSeeTheWorkspaceAndReturnOutput() {
		openSample("hello.jar");
		String result = call("run_script", "code", """
				var names = recaf.classNames();
				for (var i = 0; i < names.size(); i++) print(names.get(i));
				names.size()
				""");
		assertTrue(result.contains("sample/Hello\nsample/util/Checks"), result);
		assertTrue(result.endsWith("=> 2"), result);
		assertTrue(call("run_script", "code", "({classes: 2, ok: true})").contains("\"classes\": 2"));
		assertTrue(call("run_script", "code", "var x = 1;").contains("without output"));
		assertTrue(call("run_script", "code", "String(workspace.getPrimaryResource().getJvmClassBundle().size())").contains("=> 2"));
	}

	@Test
	void scriptsCanCallOtherTools() {
		openSample("hello.jar");
		String result = call("run_script", "code", """
				var info = tools.call("get_class_info", {"class": "sample.util.Checks"});
				print(info.indexOf("isAdmin") >= 0 ? "found isAdmin" : "missing");
				""");
		assertTrue(result.contains("found isAdmin"), result);
		String error = callExpectingError("run_script", "code", "tools.call('get_class_info', {'class': 'nope/Missing'})");
		assertTrue(error.contains("No class named 'nope/Missing'"), error);
		assertTrue(callExpectingError("run_script", "code", "tools.call('run_script', {code: '1'})").contains("cannot start other scripts"));
	}

	@Test
	void scriptsCanRewriteBytecodeWithAsm() {
		openSample("hello.jar");
		String result = call("run_script", "code", """
				var asm = Packages.org.objectweb.asm;
				var node = new asm.tree.ClassNode();
				new asm.ClassReader(recaf.classBytes("sample/Hello")).accept(node, 0);
				var changed = 0;
				for (var m = 0; m < node.methods.size(); m++) {
				  var insns = node.methods.get(m).instructions;
				  for (var insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
				    if (insn instanceof asm.tree.LdcInsnNode && "Hello " == insn.cst) { insn.cst = "Goodbye "; changed++; }
				  }
				}
				var writer = new asm.ClassWriter(0);
				node.accept(writer);
				print("wrote " + recaf.putClass(writer.toByteArray()) + ", changed " + changed);
				""");
		assertTrue(result.contains("wrote sample/Hello, changed 1"), result);
		String source = call("decompile_class", "class", "sample/Hello");
		assertTrue(source.contains("Goodbye ") && !source.contains("\"Hello \""), source);
		assertTrue(call("list_classes").contains("sample/Hello  [class, 2 fields, 4 methods, modified]"));
	}

	@Test
	void scriptsCanReadAndWriteFiles() {
		openSample("hello.jar");
		String result = call("run_script", "code", """
				var text = String(recaf.fileText("config/app.properties"));
				recaf.putFile("config/app.properties", text.replace("mode=debug", "mode=release"));
				recaf.putFile("notes/new.txt", "added");
				print(recaf.fileText("config/app.properties"));
				recaf.fileNames().size()
				""");
		assertTrue(result.contains("mode=release"), result);
		assertTrue(result.contains("=> 3"), result);
	}

	@Test
	void scriptFailuresAreReportedWithLineAndOutput() {
		openSample("hello.jar");
		String syntax = callExpectingError("run_script", "code", "var x = ;");
		assertTrue(syntax.contains("Script error"), syntax);
		String thrown = callExpectingError("run_script", "code", "print('before');\nthrow new Error('boom');");
		assertTrue(thrown.contains("boom") && thrown.contains("before") && thrown.contains("line 2"), thrown);
		String javaFailure = callExpectingError("run_script", "code", "recaf.classBytes('missing/Type')");
		assertTrue(javaFailure.contains("No class named 'missing/Type'"), javaFailure);
	}

	@Test
	void runawayScriptsAreStopped() {
		openSample("hello.jar");
		long start = System.currentTimeMillis();
		String error = callExpectingError("run_script", "code", "try { while (true) {} } catch (e) { print('caught'); }", "timeout_seconds", 1);
		assertTrue(error.contains("time limit"), error);
		assertFalse(error.contains("caught"), "Scripts must not be able to swallow the stop: " + error);
		assertTrue(System.currentTimeMillis() - start < 15_000);
	}

	@Test
	void sandboxKeepsScriptsAwayFromTheHost() {
		openSample("hello.jar");
		for (String code : new String[]{
				"java.lang.Runtime.getRuntime().exec('id')",
				"new java.net.URL('http://example.invalid').openStream()",
				"java.lang.System.getenv('HOME')",
				"java.lang.Class.forName('java.lang.Runtime').getMethods()",
				"new java.lang.ProcessBuilder(['id']).start()",
				"Packages.software.coley.recaf.agent.tool.AgentToolbox.MAX_RESULT_CHARS",
				"recaf.service('software.coley.recaf.agent.tool.AgentToolbox')",
				"Packages.org.mozilla.javascript.Context.getCurrentContext()",
				"new java.io.File('.').toURI().toURL().openConnection()",
		}) {
			// Hidden classes either raise an error or resolve to an inert package placeholder, never to the class.
			String result = toolbox.call("run_script", args("code", code)).text();
			assertTrue(!result.contains("=>") || result.contains("[JavaPackage "),
					"Script escaped the sandbox: " + code + " -> " + result);
		}
		assertTrue(call("run_script", "code", "recaf.classBytes('sample/Hello').length > 100").contains("=> true"), "Arrays are usable");
		ScriptSandbox sandbox = new ScriptSandbox();
		assertTrue(sandbox.visibleToScripts("java.util.ArrayList"));
		assertTrue(sandbox.visibleToScripts("org.objectweb.asm.tree.ClassNode"));
		assertTrue(sandbox.visibleToScripts("javax.crypto.Cipher"));
		assertFalse(sandbox.visibleToScripts("java.net.Socket"));
		assertFalse(sandbox.visibleToScripts("java.lang.reflect.Method"));
		assertFalse(sandbox.visibleToScripts("okhttp3.OkHttpClient"));
		assertFalse(sandbox.visibleToScripts("android.content.Context"));
	}

	@Test
	void javaScriptsAreOffByDefault() {
		openSample("hello.jar");
		String error = callExpectingError("run_script", "language", "java", "code", "System.out.println(1);");
		assertTrue(error.contains("turned off"), error);
		assertTrue(call("script_help").contains("recaf.classNames()"));
	}
}
