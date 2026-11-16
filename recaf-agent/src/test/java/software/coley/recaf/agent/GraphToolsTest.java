package software.coley.recaf.agent;

import com.google.devrel.gmscore.tools.apk.arsc.BinaryResourceFile;
import com.google.devrel.gmscore.tools.apk.arsc.StringPoolChunk;
import com.google.devrel.gmscore.tools.apk.arsc.XmlAttribute;
import com.google.devrel.gmscore.tools.apk.arsc.XmlStartElementChunk;
import jakarta.annotation.Nonnull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import software.coley.recaf.agent.tool.AgentTool;
import software.coley.recaf.agent.tool.AgentToolbox;
import software.coley.recaf.agent.tool.ToolResult;
import software.coley.recaf.agent.tool.impl.GraphTools;
import software.coley.recaf.info.BinaryXmlFileInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.services.callgraph.CallGraph;
import software.coley.recaf.services.callgraph.CallGraphService;
import software.coley.recaf.services.workspace.WorkspaceOpenListener;
import software.coley.recaf.test.TestClassUtils;
import software.coley.recaf.test.TestDexUtils;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.resource.WorkspaceResourceBuilder;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.objectweb.asm.Opcodes.*;

/**
 * Tests for the call graph, entry point and anti-reversal tools.
 */
class GraphToolsTest extends AgentTestBase {
	private static final String IS_ADMIN = "sample/util/Checks.isAdmin(Ljava/lang/String;)Z";
	private static final String MAIN = "sample/Hello.main([Ljava/lang/String;)V";

	@BeforeEach
	void registerTools() {
		// The call graph service follows the workspaces opened after it was created, and services are created on
		// first use in tests. Create it up front so that the workspaces of the tests get their graph from it.
		recaf.get(CallGraphService.class).getServiceId();
		if (toolbox.tool("get_callers") == null)
			toolbox.register(new GraphTools(toolbox.context()));
	}

	@Test
	void toolsAreReadOnlyAndPaged() {
		for (String name : List.of("get_callers", "get_callees", "find_entry_points", "detect_anti_reversal")) {
			AgentTool tool = toolbox.tool(name);
			assertNotNull(tool, "Missing tool: " + name);
			assertTrue(tool.readOnly(), name + " only reads");
			assertTrue(tool.description().length() > 100, name + " needs a description that guides a model");
			assertTrue(tool.inputSchema().getAsJsonObject("properties").has("offset"), name + " must be paged");
			assertTrue(tool.inputSchema().getAsJsonObject("properties").has("limit"), name + " must be paged");
		}
	}

	@Test
	void everyToolNeedsAWorkspace() {
		assertTrue(callExpectingError("get_callers", "class", "sample/Hello", "method", "run").contains("No workspace is open"));
		assertTrue(callExpectingError("get_callees", "class", "sample/Hello", "method", "run").contains("No workspace is open"));
		assertTrue(callExpectingError("find_entry_points").contains("No workspace is open"));
		assertTrue(callExpectingError("detect_anti_reversal").contains("No workspace is open"));
	}

	@Test
	void callersAreListedAndExpandedByDepth() {
		openSample("hello.jar");
		String direct = call("get_callers", "class", "sample.util.Checks", "method", "isAdmin");
		assertEquals("Callers of " + IS_ADMIN + ": 1 direct caller.\nsample/Hello.run()V", direct);

		String deep = call("get_callers", "class", "sample.util.Checks", "method", "isAdmin", "depth", 3);
		assertTrue(deep.contains("3 levels deep"), deep);
		assertTrue(deep.endsWith("\nsample/Hello.run()V\n  " + MAIN), deep);
		// A depth beyond the maximum is capped, not rejected.
		assertTrue(call("get_callers", "class", "sample.util.Checks", "method", "isAdmin", "depth", 50).contains("4 levels deep"));

		String none = call("get_callers", "class", "sample/Hello", "method", "main");
		assertTrue(none.startsWith("No callers of " + MAIN + " were found"), none);
		assertTrue(none.contains("find_entry_points"), none);
	}

	@Test
	void callersOfRuntimeLibraryMethodsAreFound() {
		openSample("hello.jar");
		String getProperty = "(Ljava/lang/String;)Ljava/lang/String;";
		String callers = call("get_callers", "class", "java.lang.System", "method", "getProperty", "descriptor", getProperty);
		assertEquals("Callers of java/lang/System.getProperty" + getProperty + ": 1 direct caller.\nsample/Hello.run()V", callers);
		// The descriptor may be given along with the name.
		assertEquals(callers, call("get_callers", "class", "java/lang/System", "method", "getProperty" + getProperty));

		String unused = call("get_callers", "class", "java/lang/Runtime", "method", "exit");
		assertTrue(unused.startsWith("No callers of java/lang/Runtime.exit(I)V were found"), unused);
	}

	@Test
	void callersNameTheDeclarationsAMethodOverrides() {
		openSample("hello.jar");
		String overriding = call("get_callers", "class", "sample/Hello", "method", "run");
		assertTrue(overriding.contains("\n" + MAIN + "\n"), overriding);
		assertTrue(overriding.contains("Note: this method overrides or implements java/lang/Runnable.run()V."), overriding);
		assertFalse(call("get_callers", "class", "sample/util/Checks", "method", "isAdmin").contains("overrides"));
	}

	@Test
	void callLookupsRejectUnknownTargets() {
		openSample("hello.jar");
		String unknownClass = callExpectingError("get_callers", "class", "com.other.Hello", "method", "run");
		assertTrue(unknownClass.contains("Did you mean: sample/Hello") && unknownClass.contains("search_references"), unknownClass);
		assertTrue(callExpectingError("get_callers", "class", "sample/Hello", "method", "nope").contains("declares no method named 'nope'"));
		assertTrue(callExpectingError("get_callers", "class", "java/lang/System", "method", "getProperty").contains("Pass the descriptor"));
		assertTrue(callExpectingError("get_callers", "class", "sample/Hello").contains("method"));
		assertTrue(callExpectingError("get_callees", "method", "run").contains("class"));
		assertTrue(callExpectingError("get_callees", "class", "sample/Hello", "method", "run", "descriptor", "(I)V").contains("Pass the descriptor"));
		// Runtime library methods can be looked up as callees of others, but their own code is not in the workspace.
		assertTrue(callExpectingError("get_callees", "class", "java/lang/System", "method", "exit").contains("runtime library"));
	}

	@Test
	void calleesListProgramMethodsFirstAndMarkTheRuntimeLibrary() {
		openSample("hello.jar");
		String callees = call("get_callees", "class", "sample.Hello", "method", "run");
		assertEquals(String.join("\n",
				"Callees of sample/Hello.run()V: 5 direct callees.",
				"sample/Hello.greet(Ljava/lang/String;I)Ljava/lang/String;",
				IS_ADMIN,
				"java/io/PrintStream.println(Ljava/lang/String;)V  [runtime library]",
				"java/lang/System.getProperty(Ljava/lang/String;)Ljava/lang/String;  [runtime library]",
				"java/util/List.add(Ljava/lang/Object;)Z  [runtime library]"), callees);

		assertEquals("sample/util/Checks.magic()I calls no methods.",
				call("get_callees", "class", "sample/util/Checks", "method", "magic"));
	}

	@Test
	void calleeTreesAreIndentedAndPaged() {
		openSample("hello.jar");
		String tree = call("get_callees", "class", "sample/Hello", "method", "main", "depth", 4);
		assertTrue(tree.startsWith("Callees of " + MAIN + ": 2 direct callees. Shown 4 levels deep"), tree);
		assertTrue(tree.contains(String.join("\n",
				"sample/Hello.run()V",
				"  sample/Hello.greet(Ljava/lang/String;I)Ljava/lang/String;",
				"    java/lang/StringBuilder.<init>()V  [runtime library]")), tree);
		assertTrue(tree.contains("\n  " + IS_ADMIN + "\n    java/lang/String.equals(Ljava/lang/Object;)Z  [runtime library]"), tree);
		assertFalse(tree.contains("More entries follow"), tree);
		long entries = tree.lines().count() - 1;

		String first = call("get_callees", "class", "sample/Hello", "method", "main", "depth", 4, "limit", 3);
		assertEquals(5, first.lines().count(), first);
		assertTrue(first.endsWith("[More entries follow. Call again with offset=3 to continue.]"), first);
		String second = call("get_callees", "class", "sample/Hello", "method", "main", "depth", 4, "limit", 3, "offset", 3);
		assertTrue(second.contains("\nsample/Hello.run()V\n") && second.contains("offset=6"), second);
		assertEquals(tree.lines().skip(4).limit(3).toList(), second.lines().skip(1).limit(3).toList());

		String last = call("get_callees", "class", "sample/Hello", "method", "main", "depth", 4, "offset", (int) entries - 1);
		assertTrue(last.endsWith("[Entries " + entries + "-" + entries + ", the last ones.]"), last);
		String beyond = call("get_callees", "class", "sample/Hello", "method", "main", "depth", 4, "offset", 500);
		assertEquals("No entries at offset 500 (there are " + entries + " in total).", beyond);
	}

	@Test
	void callTreesCutRecursionAndMarkRepeatsAndUnresolvedCalls() {
		JvmClassInfo graph = TestClassUtils.createClass("demo/G", node -> {
			addMethod(node, "a", "demo/G.b", "demo/G.c");
			addMethod(node, "b", "demo/G.d");
			addMethod(node, "c", "demo/G.d");
			addMethod(node, "d", "demo/G.e", "missing/Lib.run");
			addMethod(node, "e", "demo/G.a");
			addMethod(node, "self", "demo/G.self");
			addMethod(node, "viaSub", "demo/Sub.foo");
			node.methods.add(new MethodNode(ACC_PUBLIC | ACC_ABSTRACT, "todo", "()V", null, null));
		});
		JvmClassInfo base = TestClassUtils.createClass("demo/Base", node -> addMethod(node, "foo"));
		JvmClassInfo sub = TestClassUtils.createClass("demo/Sub", node -> node.superName = "demo/Base");
		open(TestClassUtils.fromBundle(TestClassUtils.fromClasses(graph, base, sub)));

		String callees = call("get_callees", "class", "demo/G", "method", "a", "depth", 4);
		assertTrue(callees.endsWith(String.join("\n",
				"demo/G.b()V",
				"  demo/G.d()V",
				"    demo/G.e()V",
				"      demo/G.a()V  [recursion]",
				"    missing/Lib.run()V  [unresolved]",
				"demo/G.c()V",
				"  demo/G.d()V  [expanded above]")), callees);

		String callers = call("get_callers", "class", "demo/G", "method", "d", "depth", 3);
		assertTrue(callers.endsWith(String.join("\n",
				"demo/G.b()V",
				"  demo/G.a()V",
				"    demo/G.e()V",
				"demo/G.c()V",
				"  demo/G.a()V  [expanded above]")), callers);

		assertTrue(call("get_callees", "class", "demo/G", "method", "self").endsWith("\ndemo/G.self()V  [recursion]"));

		// A call naming a subclass is a call of the method the subclass inherits.
		assertTrue(call("get_callees", "class", "demo/G", "method", "viaSub").endsWith("\ndemo/Base.foo()V  [called via demo/Sub]"));
		assertTrue(call("get_callers", "class", "demo/Base", "method", "foo").endsWith("\ndemo/G.viaSub()V  [calls it via demo/Sub]"));

		assertTrue(call("get_callees", "class", "demo/G", "method", "todo").startsWith("demo/G.todo()V is abstract"));
	}

	@Test
	void callGraphIsBuiltForAWorkspaceTheServiceMissed() {
		// The service creates the graph of a workspace when it opens. Open one behind its back, which is the state
		// things are in when the service is first created while a workspace is already open.
		List<WorkspaceOpenListener> serviceListeners = workspaceManager.getWorkspaceOpenListeners().stream()
				.filter(listener -> listener.getClass().getName().startsWith(CallGraphService.class.getName() + "$"))
				.toList();
		assertFalse(serviceListeners.isEmpty(), "The call graph service is expected to listen for opened workspaces");
		serviceListeners.forEach(workspaceManager::removeWorkspaceOpenListener);
		try {
			openSample("hello.jar");
		} finally {
			serviceListeners.forEach(workspaceManager::addWorkspaceOpenListener);
		}
		assertNull(recaf.get(CallGraphService.class).getCurrentWorkspaceCallGraph(), "The service should not know the workspace");

		assertEquals("Callers of " + IS_ADMIN + ": 1 direct caller.\nsample/Hello.run()V",
				call("get_callers", "class", "sample/util/Checks", "method", "isAdmin"));
		assertTrue(call("get_callees", "class", "sample/Hello", "method", "main").contains("sample/Hello.run()V"));
	}

	@Test
	void callGraphThatIsNotReadyIsReportedAfterABoundedWait() {
		openSample("hello.jar");
		call("get_callers", "class", "sample/util/Checks", "method", "isAdmin");
		CallGraph graph = recaf.get(CallGraphService.class).getCurrentWorkspaceCallGraph();
		assertNotNull(graph, "The service should hold the graph of the opened workspace");

		// Same tools, but willing to wait for the graph for one second only.
		AgentToolbox impatient = new AgentToolbox(toolbox.context());
		impatient.register(new GraphTools(toolbox.context(), 1));
		var lookup = args("class", "sample/util/Checks", "method", "isAdmin");
		graph.isReady().setValue(false);
		try {
			ToolResult first = impatient.call("get_callers", lookup);
			assertTrue(first.error(), first.text());
			assertTrue(first.text().contains("not ready after waiting 1 seconds"), first.text());
			assertTrue(first.text().contains("search_references"), first.text());

			ToolResult second = impatient.call("get_callees", lookup);
			assertTrue(second.error() && second.text().contains("after several waits"), second.text());

			toolbox.context().setCancelled(true);
			ToolResult cancelled = impatient.call("get_callers", lookup);
			assertTrue(cancelled.error() && cancelled.text().contains("Cancelled"), cancelled.text());
		} finally {
			toolbox.context().setCancelled(false);
			graph.isReady().setValue(true);
		}
		ToolResult ready = impatient.call("get_callers", lookup);
		assertFalse(ready.error(), ready.text());
		assertTrue(ready.text().endsWith("\nsample/Hello.run()V"), ready.text());
	}

	@Test
	void callGraphCoversAndroidClasses() {
		openSample("sample.apk");
		String callers = call("get_callers", "class", "sample.app.Keys", "method", "apiKey");
		assertEquals("Callers of sample/app/Keys.apiKey()Ljava/lang/String;: 1 direct caller.\n" +
				"sample/app/MainActivity.onCreate(Landroid/os/Bundle;)V", callers);

		String callees = call("get_callees", "class", "sample.app.MainActivity", "method", "onCreate");
		assertTrue(callees.startsWith("Callees of sample/app/MainActivity.onCreate(Landroid/os/Bundle;)V: 6 direct callees.\n" +
				"sample/app/Keys.apiKey()Ljava/lang/String;\n"), callees);
		// Framework methods are part of the runtime library where Recaf knows the framework, and unresolved where it does not.
		assertTrue(callees.contains("\nandroid/util/Log.i(Ljava/lang/String;Ljava/lang/String;)I  ["), callees);
		assertTrue(callees.contains("\njava/lang/StringBuilder.toString()Ljava/lang/String;  [runtime library]"), callees);
	}

	@Test
	void entryPointsListMainMethods() {
		openSample("hello.jar");
		assertEquals("Showing entry points 1-1 of 1:\n" + MAIN + "  [Java main(String[])]", call("find_entry_points"));

		JvmClassInfo first = TestClassUtils.createClass("demo/First", GraphToolsTest::addMain);
		JvmClassInfo second = TestClassUtils.createClass("demo/Second", GraphToolsTest::addMain);
		open(TestClassUtils.fromBundle(TestClassUtils.fromClasses(first, second)));
		String page = call("find_entry_points", "limit", 1);
		assertTrue(page.contains("demo/First.main([Ljava/lang/String;)V  [Java main(String[])]"), page);
		assertTrue(page.contains("offset=1") && !page.contains("demo/Second"), page);

		open(TestClassUtils.fromBundle(TestClassUtils.fromClasses(TestClassUtils.createEmptyClass("demo/Library"))));
		String none = call("find_entry_points");
		assertTrue(none.startsWith("No entry points were detected. Detection covers Java main methods"), none);
	}

	@Test
	void entryPointsCoverAndroidActivities() {
		open(new BasicWorkspace(new WorkspaceResourceBuilder()
				.withAndroidClassBundles(Map.of("classes.dex", TestClassUtils.fromClasses(TestDexUtils.newAndroidClass("demo/app/MainActivity"))))
				.withFileBundle(TestClassUtils.fromFiles(manifestDeclaringActivity("demo.app.MainActivity")))
				.build()));
		String entries = call("find_entry_points");
		assertTrue(entries.startsWith("Showing entry points 1-1 of 1:\ndemo/app/MainActivity  [Android activity]"), entries);
		assertTrue(entries.contains("get_android_manifest"), "Android applications have more entry points than are detected: " + entries);
	}

	@Test
	void entryPointsOfTheSampleApkComeWithAHintAtTheManifest() {
		// The sample names its activity relative to its package ('.MainActivity'), which Recaf does not resolve yet.
		// Whether or not the activity is found, the model is pointed at the manifest for the other components.
		openSample("sample.apk");
		String entries = call("find_entry_points");
		assertTrue(entries.contains("sample/app/MainActivity  [Android activity]") ||
				entries.startsWith("No entry points were detected. This is an Android application"), entries);
		assertTrue(entries.contains("fully qualified class name") && entries.contains("get_android_manifest"), entries);
	}

	@Test
	void antiReversalReportsACleanProgramInOneLine() {
		openSample("hello.jar");
		String report = call("detect_anti_reversal");
		assertTrue(report.startsWith("No anti-reversal measures were found in 2 JVM classes. Checked for: illegal names, cyclic class hierarchy"), report);
		assertEquals(1, report.lines().count(), report);
	}

	@Test
	void antiReversalFindsIllegalNamesAndStructuralTraps() {
		// class Loop extends Loop {}
		ClassWriter writer = new ClassWriter(0);
		writer.visit(V1_8, 0, "Loop", null, "Loop", null);
		writer.visitEnd();
		JvmClassInfo cycle = new JvmClassInfoBuilder(writer.toByteArray()).build();
		// A field whose generic signature is not valid: List<int>
		JvmClassInfo signature = TestClassUtils.createClass("demo/Signature", node ->
				node.visitField(ACC_PRIVATE | ACC_STATIC, "foo", "Ljava/util/List;", "Ljava/util/List<I>;", null).visitEnd());
		JvmClassInfo keyword = TestClassUtils.createClass("void/Test", node -> node.visitField(ACC_PUBLIC, "int", "I", null, null).visitEnd());
		JvmClassInfo spaced = TestClassUtils.createEmptyClass("bad name/Test");
		JvmClassInfo clean = TestClassUtils.createEmptyClass("demo/Clean");
		open(TestClassUtils.fromBundle(TestClassUtils.fromClasses(cycle, signature, keyword, spaced, clean)));

		String report = call("detect_anti_reversal");
		assertTrue(report.startsWith("4 of 5 JVM classes show anti-reversal measures:"), report);
		assertTrue(report.contains("\n  illegal names: 2 classes\n"), report);
		assertTrue(report.contains("\n  cyclic class hierarchy: 1 class, repaired by CycleClassRemovingTransformer\n"), report);
		assertTrue(report.contains("\n  malformed generic signatures: 1 class, repaired by IllegalSignatureRemovingTransformer\n"), report);
		assertFalse(report.contains("duplicated annotations"), "Kinds without findings are left out: " + report);
		assertTrue(report.contains("\nLoop  [cyclic class hierarchy (the transformer would remove the class)]"), report);
		assertTrue(report.contains("\ndemo/Signature  [malformed generic signatures]"), report);
		assertTrue(report.contains("\n\"bad name/Test\"  [illegal names: class name]"), report);
		assertTrue(report.contains("\nvoid/Test  [illegal names: class name, 1 field]"), report);
		assertFalse(report.contains("demo/Clean"), report);

		String page = call("detect_anti_reversal", "limit", 1);
		assertTrue(page.contains("affected classes 1-1 of 4") && page.contains("offset=1"), page);
	}

	@Test
	void antiReversalDoesNotCoverAndroidClasses() {
		openSample("sample.apk");
		String error = callExpectingError("detect_anti_reversal");
		assertTrue(error.contains("only covers JVM classes") && error.contains("2 Android classes"), error);
	}

	private static void open(@Nonnull Workspace workspace) {
		workspaceManager.setCurrentIgnoringConditions(workspace);
	}

	/**
	 * Adds a static method without parameters that calls other such methods.
	 *
	 * @param node
	 * 		Class to add the method to.
	 * @param name
	 * 		Name of the method.
	 * @param calls
	 * 		Methods to call, each as {@code owner.name}.
	 */
	private static void addMethod(@Nonnull ClassNode node, @Nonnull String name, @Nonnull String... calls) {
		MethodNode method = new MethodNode(ACC_PUBLIC | ACC_STATIC, name, "()V", null, null);
		for (String call : calls) {
			int dot = call.lastIndexOf('.');
			method.visitMethodInsn(INVOKESTATIC, call.substring(0, dot), call.substring(dot + 1), "()V", false);
		}
		method.visitInsn(RETURN);
		node.methods.add(method);
	}

	private static void addMain(@Nonnull ClassNode node) {
		MethodNode method = new MethodNode(ACC_PUBLIC | ACC_STATIC, "main", "([Ljava/lang/String;)V", null, null);
		method.visitInsn(RETURN);
		node.methods.add(method);
	}

	/**
	 * @param activityName
	 * 		Class name of an activity, as written in a manifest.
	 *
	 * @return A compiled {@code AndroidManifest.xml} declaring that activity.
	 */
	@Nonnull
	private static BinaryXmlFileInfo manifestDeclaringActivity(@Nonnull String activityName) {
		StringPoolChunk strings = mock(StringPoolChunk.class);
		when(strings.getStringCount()).thenReturn(2);
		when(strings.getString(0)).thenReturn("name");
		when(strings.getString(1)).thenReturn(activityName);
		XmlAttribute attribute = mock(XmlAttribute.class);
		when(attribute.nameIndex()).thenReturn(0);
		when(attribute.rawValueIndex()).thenReturn(1);
		XmlStartElementChunk activity = mock(XmlStartElementChunk.class);
		when(activity.getName()).thenReturn("activity");
		when(activity.getAttributes()).thenReturn(List.of(attribute));
		BinaryResourceFile chunks = mock(BinaryResourceFile.class);
		when(chunks.getChunks()).thenReturn(List.of(activity));
		BinaryXmlFileInfo manifest = mock(BinaryXmlFileInfo.class);
		when(manifest.getName()).thenReturn("AndroidManifest.xml");
		when(manifest.getRawContent()).thenReturn(new byte[0]);
		when(manifest.getChunkModel()).thenReturn(chunks);
		when(manifest.getStringPoolChunk()).thenReturn(strings);
		return manifest;
	}
}
