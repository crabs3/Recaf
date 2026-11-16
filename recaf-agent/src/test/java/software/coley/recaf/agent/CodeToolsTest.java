package software.coley.recaf.agent;

import jakarta.annotation.Nonnull;
import me.darknet.assembler.error.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.coley.recaf.agent.tool.AgentTool;
import software.coley.recaf.agent.tool.impl.CodeTools;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.path.PathNode;
import software.coley.recaf.services.assembler.AssemblerPipeline;
import software.coley.recaf.services.assembler.AssemblerPipelineManager;
import software.coley.recaf.test.TestClassUtils;
import software.coley.recaf.test.dummy.ClassWithLambda;
import software.coley.recaf.test.dummy.ClassWithMultipleMethods;
import software.coley.recaf.test.dummy.Inheritance;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link CodeTools}.
 */
class CodeToolsTest extends AgentTestBase {
	private static final List<String> TOOL_NAMES = List.of("disassemble_class", "disassemble_method", "decompile_method", "get_inheritance", "class_diff");
	private static final String DUMMY = "software/coley/recaf/test/dummy/";

	@BeforeEach
	void registerTools() {
		// Registered here until the group is part of the standard toolbox.
		if (toolbox.tool(TOOL_NAMES.get(0)) == null)
			toolbox.register(new CodeTools(toolbox.context()));
	}

	@Test
	void toolsAreDefinedAndReadOnly() {
		for (String name : TOOL_NAMES) {
			AgentTool tool = toolbox.tool(name);
			assertNotNull(tool, "Missing tool: " + name);
			assertTrue(tool.readOnly(), name + " only reads and must not be marked as mutating");
			assertTrue(tool.description().length() > 40, "Description of " + name + " is too short to guide a model");
			tool.inputSchema().getAsJsonArray("required").forEach(required ->
					assertTrue(tool.inputSchema().getAsJsonObject("properties").has(required.getAsString()),
							name + " requires an undeclared property: " + required));
			Object[] arguments = name.endsWith("_method") ? new Object[]{"class", "sample/Hello", "method", "greet"} : new Object[]{"class", "sample/Hello"};
			assertTrue(callExpectingError(name, arguments).contains("No workspace is open"), name + " should report the missing workspace");
		}
	}

	@Test
	void disassemblesJvmClassAsTheAssemblerPrintsIt() {
		Workspace workspace = openSample("hello.jar");
		String jasm = call("disassemble_class", "class", "sample.Hello");
		assertTrue(jasm.contains(".class public super sample/Hello {"), jasm);
		assertTrue(jasm.contains(".field private static final SECRET Ljava/lang/String; {value: \"s3cr3t-token\"}"), jasm);
		assertTrue(jasm.contains(".method greet (Ljava/lang/String;I)Ljava/lang/String; {"), jasm);
		assertTrue(jasm.contains("invokestatic sample/util/Checks.isAdmin (Ljava/lang/String;)Z"), jasm);

		// The text is the assembler's own output, and the assembler takes it back.
		ClassPathNode path = workspace.findClass("sample/Hello");
		assertNotNull(path);
		AssemblerPipeline<?, ?, ?> pipeline = recaf.get(AssemblerPipelineManager.class).getPipeline(path);
		assertEquals(pipeline.disassemble(path).requireValue().stripTrailing(), jasm);
		assertAssembles(pipeline, jasm, path);
	}

	@Test
	void disassembleClassPagesAndReportsProblems() {
		openSample("hello.jar");
		String firstPage = call("disassemble_class", "class", "sample/Hello", "max_lines", 5);
		assertTrue(firstPage.contains(".class public super sample/Hello {") && !firstPage.contains(".method"), firstPage);
		assertTrue(firstPage.contains("[Lines 1-5 of ") && firstPage.contains("start_line=6"), firstPage);
		String secondPage = call("disassemble_class", "class", "sample/Hello", "start_line", 6, "max_lines", 5);
		assertTrue(secondPage.contains("SECRET") && !secondPage.contains(".class public"), secondPage);

		assertTrue(callExpectingError("disassemble_class", "class", "sample/Missing").contains("No class named 'sample/Missing'"));
		assertTrue(callExpectingError("disassemble_class", "class", "java/lang/String").contains("runtime library"));
		assertTrue(callExpectingError("disassemble_class").contains("class"), "Missing argument should be named");
	}

	@Test
	void disassemblesAndroidClassInDalvikInstructions() {
		Workspace workspace = openSample("sample.apk");
		String jasm = call("disassemble_class", "class", "sample.app.Keys");
		assertTrue(jasm.contains(".class final sample/app/Keys {"), jasm);
		assertTrue(jasm.contains(".method static apiKey ()Ljava/lang/String; {"), jasm);
		assertTrue(jasm.contains("fill-array-data v0") && jasm.contains("return-object v1"), jasm);

		ClassPathNode path = workspace.findClass("sample/app/Keys");
		assertNotNull(path);
		AssemblerPipeline<?, ?, ?> pipeline = recaf.get(AssemblerPipelineManager.class).getPipeline(path);
		assertEquals(pipeline.disassemble(path).requireValue().stripTrailing(), jasm);
		assertAssembles(pipeline, jasm, path);
	}

	@Test
	void disassemblesSingleMethods() {
		Workspace workspace = openSample("hello.jar");
		String greet = call("disassemble_method", "class", "sample/Hello", "method", "greet");
		assertTrue(greet.startsWith(".method greet (Ljava/lang/String;I)Ljava/lang/String; {"), greet);
		assertTrue(greet.contains("ldc \"Hello \"") && greet.contains("areturn"), greet);
		assertFalse(greet.contains("admin mode") || greet.contains(".class"), "Only the one method is shown: " + greet);
		assertEquals(greet, call("disassemble_method", "class", "sample.Hello", "method", "greet(Ljava/lang/String;I)Ljava/lang/String;"));
		assertEquals(greet, call("disassemble_method", "class", "sample.Hello", "method", "greet", "descriptor", "(Ljava/lang/String;I)Ljava/lang/String;"));

		// The text of a method is accepted back by the assembler for that method.
		ClassPathNode classPath = workspace.findClass("sample/Hello");
		assertNotNull(classPath);
		assertAssembles(recaf.get(AssemblerPipelineManager.class).getPipeline(classPath), greet,
				classPath.child("greet", "(Ljava/lang/String;I)Ljava/lang/String;"));

		String paged = call("disassemble_method", "class", "sample/Hello", "method", "run", "max_lines", 4);
		assertTrue(paged.startsWith(".method public run ()V {") && paged.contains("start_line=5"), paged);

		openSample("sample.apk");
		String onCreate = call("disassemble_method", "class", "sample/app/MainActivity", "method", "onCreate");
		assertTrue(onCreate.startsWith(".method protected onCreate (Landroid/os/Bundle;)V {"), onCreate);
		assertTrue(onCreate.contains("invoke-super { this, p0 } android/app/Activity.onCreate (Landroid/os/Bundle;)V"), onCreate);
		assertTrue(onCreate.contains("const-string v1 \"Reporting to https://example.invalid/collect with key \""), onCreate);
	}

	@Test
	void disassembleMethodReportsUnknownAndAmbiguousMethods() throws IOException {
		openClasses(ClassWithMultipleMethods.class);
		String type = DUMMY + "ClassWithMultipleMethods";
		String ambiguous = callExpectingError("disassemble_method", "class", type, "method", "add");
		assertTrue(ambiguous.contains("declares 2 methods named 'add'") && ambiguous.contains("(II)I, (III)I"), ambiguous);
		String picked = call("disassemble_method", "class", type, "method", "add", "descriptor", "(III)I");
		assertTrue(picked.startsWith(".method public static add (III)I {"), picked);

		String unknown = callExpectingError("disassemble_method", "class", type, "method", "subtract");
		assertTrue(unknown.contains("declares no method named 'subtract'") && unknown.contains("add(II)I"), unknown);
		assertTrue(callExpectingError("disassemble_method", "class", type).contains("method"), "Missing argument should be named");
	}

	@Test
	void decompilesSingleMethodWithoutTheRestOfTheClass() {
		openSample("hello.jar");
		for (String decompiler : new String[]{"CFR", "Procyon", "Vineflower"}) {
			String source = call("decompile_method", "class", "sample/Hello", "method", "greet", "decompiler", decompiler);
			assertTrue(source.startsWith("// sample/Hello.greet(Ljava/lang/String;I)Ljava/lang/String; decompiled with " + decompiler +
					". The other 3 methods of the class are left out.\n"), source);
			assertTrue(source.contains("class Hello") && source.contains("greet(") && source.contains("\"Hello \""), decompiler + ": " + source);
			// Fields stay for context, the other methods are gone.
			assertTrue(source.contains("SECRET"), decompiler + ": " + source);
			assertFalse(source.contains("admin mode") || source.contains("main(") || source.contains("run()"), decompiler + ": " + source);
		}

		String whole = call("decompile_class", "class", "sample/Hello");
		String method = call("decompile_method", "class", "sample/Hello", "method", "greet");
		assertTrue(method.length() < whole.length(), "A single method is cheaper than the whole class");
		String paged = call("decompile_method", "class", "sample/Hello", "method", "greet", "max_lines", 3);
		assertTrue(paged.contains("start_line=4"), paged);
	}

	@Test
	void decompilesAndroidMethods() {
		openSample("sample.apk");
		String apiKey = call("decompile_method", "class", "sample.app.Keys", "method", "apiKey");
		assertTrue(apiKey.contains("The other method of the class is left out."), apiKey);
		assertTrue(apiKey.contains("static String apiKey()") && apiKey.contains("new String("), apiKey);
		String onCreate = call("decompile_method", "class", "sample/app/MainActivity", "method", "onCreate");
		assertTrue(onCreate.contains("https://example.invalid/collect") && onCreate.contains("super.onCreate("), onCreate);
	}

	@Test
	void decompileMethodKeepsLambdaBodies() throws IOException {
		openClasses(ClassWithLambda.class);
		String type = DUMMY + "ClassWithLambda";
		for (String decompiler : new String[]{"CFR", "Procyon", "Vineflower"}) {
			String source = call("decompile_method", "class", type, "method", "runnable", "decompiler", decompiler);
			// The lambda is shown in place, which needs the synthetic method holding its body.
			assertTrue(source.contains("new Thread(() ->") && source.contains("System.out.println(\"foo\")"), decompiler + ": " + source);
			// Lambdas of the other methods are not dragged along.
			assertFalse(source.contains("isBlank") || source.contains("consumer"), decompiler + ": " + source);
		}
	}

	@Test
	void decompileMethodReportsProblems() {
		openSample("hello.jar");
		String unknown = callExpectingError("decompile_method", "class", "sample/Hello", "method", "farewell");
		assertTrue(unknown.contains("declares no method named 'farewell'") && unknown.contains("greet(Ljava/lang/String;I)Ljava/lang/String;"), unknown);
		assertTrue(callExpectingError("decompile_method", "class", "sample/Hello", "method", "greet", "decompiler", "nope").contains("Available"));
		assertTrue(callExpectingError("decompile_method", "class", "sample/Goodbye", "method", "greet").contains("No class named"));
	}

	@Test
	void inheritanceOfWorkspaceAndRuntimeClasses() {
		openSample("hello.jar");
		assertEquals("""
				class sample/Hello
				Super classes: java/lang/Object
				Interfaces, direct: java/lang/Runnable
				Interfaces, through super types: none
				No direct subtypes found.""", call("get_inheritance", "class", "sample.Hello"));

		// "Who implements Runnable" works although Runnable is not part of the program.
		String runnable = call("get_inheritance", "class", "java.lang.Runnable");
		assertTrue(runnable.startsWith("interface java/lang/Runnable  (runtime library)\n"), runnable);
		assertTrue(runnable.contains("Showing direct subtypes 1-1 of 1:\nsample/Hello  [class]"), runnable);

		openSample("sample.apk");
		String activity = call("get_inheritance", "class", "sample/app/MainActivity");
		assertTrue(activity.contains("Super classes: android/app/Activity -> "), activity);
		assertTrue(activity.contains("-> java/lang/Object"), activity);
		assertTrue(call("get_inheritance", "class", "android.app.Activity").contains("sample/app/MainActivity  [class]"));
	}

	@Test
	void inheritanceListsDirectAndTransitiveSubtypes() throws IOException {
		openClasses(Inheritance.Edible.class, Inheritance.Red.class, Inheritance.Apple.class,
				Inheritance.AppleWithWorm.class, Inheritance.Grape.class, Inheritance.NotFoodException.class);
		String base = DUMMY + "Inheritance$";

		// Inner classes can be named with dots.
		String edible = call("get_inheritance", "class", "software.coley.recaf.test.dummy.Inheritance.Edible");
		assertTrue(edible.startsWith("interface " + base + "Edible\n"), edible);
		assertTrue(edible.contains("Showing direct subtypes 1-2 of 2:\n" + base + "Apple  [class]\n" + base + "Grape  [class]"), edible);
		assertFalse(edible.contains("AppleWithWorm"), edible);
		String allEdible = call("get_inheritance", "class", base + "Edible", "transitive", true);
		assertTrue(allEdible.contains("Showing subtypes at any distance 1-3 of 3:") && allEdible.contains(base + "AppleWithWorm  [class]"), allEdible);

		String worm = call("get_inheritance", "class", base + "AppleWithWorm");
		assertTrue(worm.contains("Super classes: " + base + "Apple -> java/lang/Object\n"), worm);
		assertTrue(worm.contains("Interfaces, direct: none\n"), worm);
		assertTrue(worm.contains("Interfaces, through super types: " + base + "Edible, " + base + "Red\n"), worm);

		String exception = call("get_inheritance", "class", base + "NotFoodException");
		assertTrue(exception.contains("Super classes: java/lang/Exception -> java/lang/Throwable -> java/lang/Object\n"), exception);
		assertTrue(exception.contains("Interfaces, through super types: java/io/Serializable\n"), exception);

		// Runtime classes between a queried runtime class and the program are shown and marked.
		String throwable = call("get_inheritance", "class", "java/lang/Throwable");
		assertTrue(throwable.contains("java/lang/Exception  [class]  (runtime library)") && !throwable.contains("NotFoodException"), throwable);
		assertTrue(call("get_inheritance", "class", "java/lang/Throwable", "transitive", true).contains(base + "NotFoodException  [class]"));

		String firstPage = call("get_inheritance", "class", base + "Edible", "limit", 1);
		assertTrue(firstPage.contains("Showing direct subtypes 1-1 of 2") && firstPage.contains("offset=1"), firstPage);
		String secondPage = call("get_inheritance", "class", base + "Edible", "offset", 1, "limit", 1);
		assertTrue(secondPage.startsWith("Showing direct subtypes 2-2 of 2:\n" + base + "Grape"), "Only the first page repeats the general part: " + secondPage);
	}

	@Test
	void inheritanceReportsUnknownClass() {
		openSample("hello.jar");
		String error = callExpectingError("get_inheritance", "class", "com/other/Hello");
		assertTrue(error.contains("Did you mean: sample/Hello"), error);
		assertTrue(callExpectingError("get_inheritance").contains("class"), "Missing argument should be named");
	}

	@Test
	void classDiffShowsChangesInBothViews() {
		Workspace workspace = openSample("hello.jar");
		NavigationToolsTest.replaceString(workspace, "sample/Hello", "Hello ", "Howdy ");

		String source = call("class_diff", "class", "sample.Hello");
		assertTrue(source.startsWith("Changes to the source decompiled with "), source);
		assertTrue(source.contains("--- sample/Hello (original)\n+++ sample/Hello (current)\n@@ "), source);
		assertTrue(source.lines().anyMatch(line -> line.startsWith("-") && line.contains("\"Hello \"")), source);
		assertTrue(source.lines().anyMatch(line -> line.startsWith("+") && line.contains("\"Howdy \"")), source);
		assertFalse(source.contains("admin mode"), "Only the surroundings of the change are shown: " + source);
		assertTrue(call("class_diff", "class", "sample/Hello", "decompiler", "Procyon").startsWith("Changes to the source decompiled with Procyon of 'sample/Hello'"));

		String bytecode = call("class_diff", "class", "sample/Hello", "view", "disassemble");
		assertTrue(bytecode.startsWith("Changes to the disassembled bytecode of 'sample/Hello':\n--- sample/Hello (original)"), bytecode);
		assertTrue(bytecode.lines().anyMatch(line -> line.startsWith("-") && line.contains("ldc \"Hello \"")), bytecode);
		assertTrue(bytecode.lines().anyMatch(line -> line.startsWith("+") && line.contains("ldc \"Howdy \"")), bytecode);
		assertEquals(1, bytecode.lines().filter(line -> line.startsWith("@@")).count(), "One changed instruction, one hunk: " + bytecode);

		String paged = call("class_diff", "class", "sample/Hello", "view", "disassemble", "max_lines", 3);
		assertTrue(paged.contains("start_line=4"), paged);
	}

	@Test
	void classDiffReportsUnmodifiedClassesAndIdenticalViews() {
		Workspace workspace = openSample("hello.jar");
		String unmodified = callExpectingError("class_diff", "class", "sample/util/Checks");
		assertTrue(unmodified.contains("'sample/util/Checks' was not modified in this session"), unmodified);
		assertTrue(callExpectingError("class_diff", "class", "sample/Nope").contains("No class named"));

		// An edit that leaves the class as it was is not an error, and is not shown as an empty diff either.
		JvmClassBundle bundle = workspace.getPrimaryResource().getJvmClassBundle();
		JvmClassInfo checks = bundle.get("sample/util/Checks");
		bundle.put(checks.toJvmClassBuilder().build());
		assertTrue(call("class_diff", "class", "sample/util/Checks").contains("is the same before and after"));
		assertTrue(call("class_diff", "class", "sample/util/Checks", "view", "disassemble").contains("is the same before and after"));
		assertTrue(callExpectingError("class_diff", "class", "sample/util/Checks", "view", "smali").contains("view"));
	}

	/**
	 * Opens a workspace of compiled test classes.
	 *
	 * @param classes
	 * 		Classes to put into the workspace.
	 */
	private static void openClasses(@Nonnull Class<?>... classes) throws IOException {
		workspaceManager.setCurrentIgnoringConditions(TestClassUtils.fromBundle(TestClassUtils.fromClasses(classes)));
	}

	private static void assertAssembles(@Nonnull AssemblerPipeline<?, ?, ?> pipeline, @Nonnull String jasm, @Nonnull PathNode<?> path) {
		Outcome<?> assembled = pipeline.assembleSource(jasm, "test", path);
		assertTrue(assembled.hasValue() && !assembled.hasErrors(), "The assembler rejected what it printed: " + assembled.diagnostics());
	}
}
