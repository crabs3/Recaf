package software.coley.recaf.agent;

import org.junit.jupiter.api.Test;
import software.coley.recaf.agent.tool.AgentTool;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the workspace, class and finding tools, and for the behavior of the toolbox itself.
 */
class CoreToolsTest extends AgentTestBase {
	@Test
	void toolDefinitionsAreWellFormed() {
		Set<String> names = new HashSet<>();
		for (AgentTool tool : toolbox.tools()) {
			assertTrue(tool.name().matches("[a-z][a-z0-9_]{2,63}"), "Bad tool name: " + tool.name());
			assertTrue(names.add(tool.name()), "Duplicate tool name: " + tool.name());
			assertTrue(tool.description().length() > 40, "Description of " + tool.name() + " is too short to guide a model");
			assertEquals("object", tool.inputSchema().get("type").getAsString());
			assertTrue(tool.inputSchema().has("properties"));
			tool.inputSchema().getAsJsonArray("required").forEach(required ->
					assertTrue(tool.inputSchema().getAsJsonObject("properties").has(required.getAsString()),
							tool.name() + " requires an undeclared property: " + required));
		}
		assertFalse(names.isEmpty());
	}

	@Test
	void toolsReportMissingWorkspace() {
		String error = callExpectingError("workspace_info");
		assertTrue(error.contains("No workspace is open"), error);
	}

	@Test
	void unknownToolAndBadArgumentsAreReportedNotThrown() {
		assertTrue(toolbox.call("no_such_tool", null).error());
		openSample("hello.jar");
		assertTrue(callExpectingError("get_class_info").contains("class"), "Missing argument should be named");
	}

	@Test
	void workspaceInfoSummarizesJar() {
		openSample("hello.jar");
		String info = call("workspace_info");
		assertTrue(info.contains("hello.jar"), info);
		assertTrue(info.contains("JVM classes: 2"), info);
		assertTrue(info.contains("sample/util"), info);
	}

	@Test
	void workspaceInfoRecognizesAndroid() {
		openSample("sample.apk");
		String info = call("workspace_info");
		assertTrue(info.contains("Android (DEX) classes: 2"), info);
		assertTrue(info.contains("get_android_manifest"), info);
	}

	@Test
	void openWorkspaceHonorsInputRoots() {
		String opened = call("open_workspace", "path", sample("hello.jar").toString());
		assertTrue(opened.contains("2 JVM classes"), opened);
		// Relative paths resolve against the allowed directory.
		assertTrue(call("open_workspace", "path", "hello.jar").contains("2 JVM classes"));
		assertTrue(callExpectingError("open_workspace", "path", "/etc/hostname").contains("outside"));
	}

	@Test
	void listClassesFiltersAndPages() {
		openSample("hello.jar");
		String all = call("list_classes");
		assertTrue(all.contains("sample/Hello") && all.contains("sample/util/Checks"), all);
		String filtered = call("list_classes", "pattern", "check");
		assertTrue(filtered.contains("sample/util/Checks") && !filtered.contains("sample/Hello"), filtered);
		String nonRecursive = call("list_classes", "package", "sample", "recursive", false);
		assertTrue(nonRecursive.contains("sample/Hello") && !nonRecursive.contains("Checks"), nonRecursive);
		String paged = call("list_classes", "limit", 1);
		assertTrue(paged.contains("offset=1"), paged);
		assertTrue(callExpectingError("list_classes", "pattern", "(").contains("Invalid regular expression"));
	}

	@Test
	void classInfoAcceptsAnyNameFormAndSuggests() {
		openSample("hello.jar");
		String info = call("get_class_info", "class", "sample.Hello");
		assertTrue(info.contains("implements java/lang/Runnable"), info);
		assertTrue(info.contains("greet(Ljava/lang/String;I)Ljava/lang/String;"), info);
		assertTrue(info.contains("SECRET Ljava/lang/String; = \"s3cr3t-token\""), info);
		assertEquals(info, call("get_class_info", "class", "Lsample/Hello;"));
		String error = callExpectingError("get_class_info", "class", "com.other.Hello");
		assertTrue(error.contains("Did you mean: sample/Hello"), error);
	}

	@Test
	void decompilesJvmAndAndroidClasses() {
		openSample("hello.jar");
		for (String decompiler : new String[]{"CFR", "Procyon", "Vineflower"}) {
			String source = call("decompile_class", "class", "sample/Hello", "decompiler", decompiler);
			assertTrue(source.contains("class Hello"), decompiler + ": " + source);
		}
		String paged = call("decompile_class", "class", "sample/Hello", "max_lines", 5);
		assertTrue(paged.contains("start_line=6"), paged);
		assertTrue(callExpectingError("decompile_class", "class", "sample/Hello", "decompiler", "nope").contains("Available"));

		openSample("sample.apk");
		String activity = call("decompile_class", "class", "sample.app.MainActivity");
		assertTrue(activity.contains("https://example.invalid/collect"), activity);
	}

	@Test
	void findingsAreRecordedListedAndRemoved() {
		assertTrue(call("list_findings").contains("No findings"));
		call("record_finding", "severity", "high", "title", "Hardcoded secret",
				"detail", "sample/Hello keeps a token in SECRET", "class", "sample.Hello", "member", "SECRET Ljava/lang/String;");
		call("record_finding", "severity", "info", "title", "Entry point", "detail", "main in sample/Hello");
		String listed = call("list_findings");
		assertTrue(listed.indexOf("Hardcoded secret") < listed.indexOf("Entry point"), "Most severe first: " + listed);
		assertTrue(listed.contains("at sample/Hello.SECRET"), listed);
		assertEquals(2, toolbox.context().state().findings().size());
		call("remove_finding", "id", 1);
		assertFalse(call("list_findings").contains("Hardcoded secret"));
		assertTrue(callExpectingError("remove_finding", "id", 99).contains("no finding"));
	}
}
