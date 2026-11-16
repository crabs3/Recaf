package software.coley.recaf.agent;

import jakarta.annotation.Nonnull;
import me.darknet.dex.tree.definitions.code.Code;
import me.darknet.dex.tree.definitions.instructions.ConstInstruction;
import me.darknet.dex.tree.definitions.instructions.ReturnInstruction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.MethodNode;
import software.coley.recaf.agent.tool.AgentTool;
import software.coley.recaf.agent.tool.impl.SearchTools;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.test.TestClassUtils;
import software.coley.recaf.test.TestDexUtils;
import software.coley.recaf.test.dummy.AnnotationImpl;
import software.coley.recaf.test.dummy.ClassWithAnnotation;
import software.coley.recaf.test.dummy.ClassWithExceptions;
import software.coley.recaf.test.dummy.ClassWithLambda;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.BasicVersionedJvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResourceBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.objectweb.asm.Opcodes.*;

/**
 * Tests for the search tools.
 */
class SearchToolsTest extends AgentTestBase {
	private static final List<String> TOOLS = List.of("search_strings", "search_numbers", "search_references",
			"search_declarations", "search_instructions", "search_files");

	@BeforeEach
	void registerTools() {
		if (toolbox.tool("search_strings") == null)
			toolbox.register(new SearchTools(toolbox.context()));
	}

	@Test
	void toolsAreReadOnlyPagedAndScoped() {
		for (String name : TOOLS) {
			AgentTool tool = toolbox.tool(name);
			assertNotNull(tool, "Missing tool: " + name);
			assertTrue(tool.readOnly(), name + " only reads");
			assertTrue(tool.description().length() > 100, name + " needs a description that guides a model");
			assertTrue(tool.inputSchema().getAsJsonObject("properties").has("offset"), name + " must be paged");
			assertTrue(tool.inputSchema().getAsJsonObject("properties").has("limit"), name + " must be paged");
			if (!name.equals("search_files"))
				assertTrue(tool.inputSchema().getAsJsonObject("properties").has("package"), name + " must take a package scope");
		}
	}

	@Test
	void everySearchNeedsAWorkspace() {
		assertTrue(callExpectingError("search_strings", "query", "x").contains("No workspace is open"));
		assertTrue(callExpectingError("search_numbers", "value", "1").contains("No workspace is open"));
		assertTrue(callExpectingError("search_references", "owner", "java/lang/Runtime").contains("No workspace is open"));
		assertTrue(callExpectingError("search_declarations", "pattern", "x").contains("No workspace is open"));
		assertTrue(callExpectingError("search_instructions", "instructions", List.of("x")).contains("No workspace is open"));
		assertTrue(callExpectingError("search_files", "pattern", "x").contains("No workspace is open"));
	}

	@Test
	void stringsAreFoundInFieldsCodeAndFiles() {
		openSample("hello.jar");
		String found = call("search_strings", "query", "S3CR3T");
		assertTrue(found.contains("sample/Hello.SECRET Ljava/lang/String;  \"s3cr3t-token\""), found);
		assertTrue(found.contains("sample/Hello.run()V  \"s3cr3t-token\""), found);
		assertTrue(found.contains("strings 1-2 of 2"), found);

		String inFile = call("search_strings", "query", "example.invalid");
		assertTrue(inFile.contains("config/app.properties:1  \"endpoint=https://example.invalid/api\""), inFile);

		String caseSensitive = call("search_strings", "query", "S3CR3T", "case_sensitive", true);
		assertTrue(caseSensitive.startsWith("No string constant"), caseSensitive);
		assertTrue(caseSensitive.contains("2 classes and 2 files"), caseSensitive);
	}

	@Test
	void stringsCanBeMatchedInEveryMode() {
		openSample("hello.jar");
		String equal = call("search_strings", "query", "ROOT", "mode", "equals");
		assertTrue(equal.contains("sample/util/Checks.isAdmin(Ljava/lang/String;)Z  \"root\""), equal);
		assertTrue(call("search_strings", "query", "roo", "mode", "equals").startsWith("No string constant"));

		String prefix = call("search_strings", "query", "hello", "mode", "starts_with");
		assertTrue(prefix.contains("sample/Hello.greet(Ljava/lang/String;I)Ljava/lang/String;  \"Hello \""), prefix);
		assertFalse(prefix.contains("s3cr3t"), prefix);

		String suffix = call("search_strings", "query", "-token", "mode", "ends_with");
		assertTrue(suffix.contains("\"s3cr3t-token\"") && !suffix.contains("\"Hello \""), suffix);

		String regex = call("search_strings", "query", "^s\\dcr\\dt-\\w+$", "mode", "regex");
		assertTrue(regex.contains("\"s3cr3t-token\"") && !regex.contains("app.properties"), regex);
		assertTrue(call("search_strings", "query", "^S3", "mode", "regex", "case_sensitive", true).startsWith("No string constant"));

		assertTrue(callExpectingError("search_strings", "query", "(", "mode", "regex").contains("Invalid regular expression"));
		assertTrue(callExpectingError("search_strings", "query", "x", "mode", "sounds_like").contains("mode"));
		assertTrue(callExpectingError("search_strings").contains("query"));
	}

	@Test
	void stringSearchHonorsPackageAndPaging() {
		openSample("hello.jar");
		String everywhere = call("search_strings", "query", "o");
		assertTrue(everywhere.contains("sample/Hello.") && everywhere.contains("sample/util/Checks.") &&
				everywhere.contains("config/app.properties"), everywhere);

		String inPackage = call("search_strings", "query", "o", "package", "sample.util");
		assertTrue(inPackage.contains("sample/util/Checks.isAdmin"), inPackage);
		assertFalse(inPackage.contains("sample/Hello") || inPackage.contains("app.properties"), inPackage);

		// A class name scopes the search to that class.
		String inClass = call("search_strings", "query", "o", "package", "sample/Hello");
		assertTrue(inClass.contains("sample/Hello.run()V") && !inClass.contains("Checks"), inClass);

		String firstPage = call("search_strings", "query", "o", "limit", 1);
		assertTrue(firstPage.contains("offset=1"), firstPage);
		String secondPage = call("search_strings", "query", "o", "limit", 1, "offset", 1);
		assertNotEquals(firstPage.lines().skip(1).findFirst().orElseThrow(), secondPage.lines().skip(1).findFirst().orElseThrow());
	}

	@Test
	void longStringsAreShortenedAroundTheMatchAndAnnotationsAreNamed() throws IOException {
		String longText = "a".repeat(1000) + "NEEDLE" + "b".repeat(1000);
		JvmClassInfo holder = TestClassUtils.createClass("demo/Holder", node ->
				node.visitField(ACC_STATIC | ACC_FINAL, "BLOB", "Ljava/lang/String;", null, longText).visitEnd());
		open(TestClassUtils.fromBundle(TestClassUtils.fromClasses(holder, TestClassUtils.fromRuntimeClass(ClassWithAnnotation.class))));

		String blob = call("search_strings", "query", "needle");
		assertTrue(blob.contains("demo/Holder.BLOB Ljava/lang/String;"), blob);
		assertTrue(blob.contains("NEEDLE"), "The shortened string must still show the match: " + blob);
		assertTrue(blob.contains("(2006 chars)"), blob);
		assertTrue(blob.length() < 500, "The string was not shortened: " + blob);

		String annotated = call("search_strings", "query", "Hello", "mode", "equals", "case_sensitive", true);
		assertTrue(annotated.contains(ClassWithAnnotation.class.getName().replace('.', '/') + "  \"Hello\" in annotation L"), annotated);
	}

	@Test
	void variantsOfAClassInAMultiReleaseJarAreToldApart() {
		// The same class twice: as usual, and as the variant for Java 11 and later with a different constant.
		JvmClassInfo base = TestClassUtils.createClass("demo/Config", node ->
				node.visitField(ACC_STATIC | ACC_FINAL, "MODE", "Ljava/lang/String;", null, "plain mode").visitEnd());
		JvmClassInfo variant = TestClassUtils.createClass("demo/Config", node ->
				node.visitField(ACC_STATIC | ACC_FINAL, "MODE", "Ljava/lang/String;", null, "hidden mode").visitEnd());
		BasicVersionedJvmClassBundle versioned = new BasicVersionedJvmClassBundle(11);
		versioned.initialPut(variant);
		open(new BasicWorkspace(new WorkspaceResourceBuilder()
				.withJvmClassBundle(TestClassUtils.fromClasses(base))
				.withVersionedJvmClassBundles(new TreeMap<>(Map.of(11, versioned)))
				.build()));

		List<String> strings = call("search_strings", "query", "mode").lines().toList();
		assertTrue(strings.contains("demo/Config.MODE Ljava/lang/String;  \"plain mode\""), strings.toString());
		assertTrue(strings.contains("demo/Config.MODE Ljava/lang/String;  \"hidden mode\"  [variant in META-INF/versions/11]"), strings.toString());

		List<String> declarations = call("search_declarations", "pattern", "config", "kind", "class").lines().toList();
		assertTrue(declarations.contains("demo/Config  [class]"), declarations.toString());
		assertTrue(declarations.contains("demo/Config  [class]  [variant in META-INF/versions/11]"), declarations.toString());
	}

	@Test
	void numbersAreFoundByValueAndRange() {
		openSample("hello.jar");
		String decimal = call("search_numbers", "value", "1337");
		assertTrue(decimal.contains("sample/util/Checks.magic()I  1337 (0x539)"), decimal);
		assertEquals(decimal, call("search_numbers", "value", "0x539"));

		String range = call("search_numbers", "min", "1000", "max", "2000");
		assertTrue(range.contains("sample/util/Checks.magic()I  1337") && range.contains("numbers 1-1 of 1"), range);
		assertTrue(call("search_numbers", "min", "1000").contains("sample/util/Checks.magic()I"));
		assertTrue(call("search_numbers", "max", "-2").startsWith("No numeric constant of at most -2"));
		assertTrue(call("search_numbers", "value", "1337", "package", "sample/Hello").startsWith("No numeric constant"));

		// Numbers in text files are left to search_files.
		assertFalse(call("search_numbers", "min", "0").contains("MANIFEST"));

		assertTrue(callExpectingError("search_numbers").contains("'value'"));
		assertTrue(callExpectingError("search_numbers", "value", "1", "min", "0").contains("not both"));
		assertTrue(callExpectingError("search_numbers", "min", "5", "max", "2").contains("larger than"));
		assertTrue(callExpectingError("search_numbers", "value", "12abc").contains("not a number"));
	}

	@Test
	void numbersMatchAcrossTypesAndHexMatchesBitPatterns() {
		JvmClassInfo constants = TestClassUtils.createClass("demo/Constants", node -> {
			MethodNode method = new MethodNode(ACC_PUBLIC | ACC_STATIC, "load", "()V", null, null);
			method.visitLdcInsn(0xCAFEBABE);
			method.visitLdcInsn(0x5DEECE66DL);
			method.visitLdcInsn(2.5F);
			method.visitLdcInsn(2.5D);
			method.visitInsn(RETURN);
			node.methods.add(method);
		});
		open(TestClassUtils.fromBundle(TestClassUtils.fromClasses(constants)));

		String bits = call("search_numbers", "value", "0xCAFEBABE");
		assertTrue(bits.contains("demo/Constants.load()V  -889275714 (0xCAFEBABE)"), bits);
		String wide = call("search_numbers", "value", "0x5DEECE66D");
		assertTrue(wide.contains("demo/Constants.load()V  25214903917L (0x5DEECE66D)"), wide);
		// A decimal finds the float and the double of that value, and nothing else.
		String floating = call("search_numbers", "value", "2.5");
		assertTrue(floating.contains("demo/Constants.load()V  2.5\n") && floating.endsWith("demo/Constants.load()V  2.5F"), floating);
		assertTrue(floating.contains("numbers 1-2 of 2"), floating);
	}

	@Test
	void floatsInDexAreFoundByTheirRawBits() {
		Code code = new Code(0, 0, 1);
		code.addInstruction(new ConstInstruction(0, Float.floatToRawIntBits(2.5F)));
		code.addInstruction(new ReturnInstruction());
		open(TestClassUtils.fromBundle(TestClassUtils.fromClasses(TestDexUtils.newAndroidClass("demo/Floats",
				TestDexUtils.newDexMethod("load", "()V", ACC_PUBLIC | ACC_STATIC, code)))));

		String found = call("search_numbers", "value", "2.5");
		assertTrue(found.contains("demo/Floats.load()V  1075838976 (0x40200000) = raw bits of float 2.5"), found);
	}

	@Test
	void referencesToMembersAreFoundIncludingRuntimeLibraryOnes() {
		openSample("hello.jar");
		String runtime = call("search_references", "owner", "java.lang.System", "name", "getProperty");
		assertTrue(runtime.startsWith("References to java/lang/System.getProperty."), runtime);
		assertTrue(runtime.contains("sample/Hello.run()V  invokestatic java/lang/System.getProperty (Ljava/lang/String;)Ljava/lang/String;"), runtime);
		assertTrue(runtime.contains("uses 1-1 of 1"), runtime);

		String program = call("search_references", "owner", "sample/util/Checks", "name", "isAdmin", "descriptor", "(Ljava/lang/String;)Z");
		assertTrue(program.contains("sample/Hello.run()V  invokestatic sample/util/Checks.isAdmin"), program);
		// The name may carry the descriptor, in the form members are printed in.
		assertEquals(program, call("search_references", "owner", "sample/util/Checks", "name", "isAdmin(Ljava/lang/String;)Z"));
		assertTrue(call("search_references", "owner", "sample/util/Checks", "name", "isAdmin", "descriptor", "()V")
				.startsWith("No references to sample/util/Checks.isAdmin()V"));

		String field = call("search_references", "owner", "java/lang/System", "name", "out");
		assertTrue(field.contains("sample/Hello.run()V  getstatic java/lang/System.out Ljava/io/PrintStream;  (x2)"), field);
		assertTrue(call("search_references", "owner", "java/lang/System", "name", "out Ljava/io/PrintStream;")
				.contains("sample/Hello.run()V  getstatic java/lang/System.out Ljava/io/PrintStream;  (x2)"));

		String none = call("search_references", "owner", "java/lang/Runtime", "name", "exec");
		assertTrue(none.startsWith("No references to java/lang/Runtime.exec in 2 classes."), none);
		assertTrue(none.contains("get_callers"), none);
		String misspelled = call("search_references", "owner", "java/lang/Runtime", "name", "exce");
		assertTrue(misspelled.contains("java/lang/Runtime itself declares no field or method named 'exce'"), misspelled);
	}

	@Test
	void referencesToAClassCoverItsMembersTypeUsesAndSubtypes() {
		openSample("hello.jar");
		String system = call("search_references", "owner", "java/lang/System");
		assertTrue(system.contains("invokestatic java/lang/System.getProperty") && system.contains("getstatic java/lang/System.out"), system);

		String list = call("search_references", "owner", "java/util/List");
		assertTrue(list.contains("sample/Hello.names Ljava/util/List;  field of type java/util/List"), list);
		assertTrue(list.contains("sample/Hello.run()V  invokeinterface java/util/List.add (Ljava/lang/Object;)Z"), list);

		String runnable = call("search_references", "owner", "java/lang/Runnable");
		assertTrue(runnable.contains("sample/Hello  implements java/lang/Runnable"), runnable);

		String scoped = call("search_references", "owner", "java/lang/String", "package", "sample/util");
		assertTrue(scoped.contains("sample/util/Checks.isAdmin(Ljava/lang/String;)Z  signature mentions java/lang/String"), scoped);
		assertFalse(scoped.contains("sample/Hello"), scoped);
		assertTrue(call("search_references", "owner", "java/lang/String", "limit", 2).contains("offset=2"));

		String missing = call("search_references", "owner", "com/nope/Missing");
		assertTrue(missing.startsWith("No references to com/nope/Missing"), missing);
		assertTrue(missing.contains("no class named 'com/nope/Missing'"), missing);
		assertTrue(callExpectingError("search_references", "name", "exec").contains("owner"));
	}

	@Test
	void referencesNameHowTheClassIsUsed() throws IOException {
		open(TestClassUtils.fromBundle(TestClassUtils.fromClasses(ClassWithExceptions.class, ClassWithLambda.class, ClassWithAnnotation.class)));
		String exceptions = ClassWithExceptions.class.getName().replace('.', '/') + ".readInt(Ljava/lang/Object;)I";
		String uses = call("search_references", "owner", "java.lang.NumberFormatException");
		assertTrue(uses.contains(exceptions + "  declares throws java/lang/NumberFormatException"), uses);
		assertTrue(uses.contains(exceptions + "  catches java/lang/NumberFormatException"), uses);
		assertTrue(uses.contains(exceptions + "  local variable ex of type java/lang/NumberFormatException"), uses);
		assertTrue(uses.contains(exceptions + "  new java/lang/NumberFormatException"), uses);
		assertTrue(uses.contains(exceptions + "  invokespecial java/lang/NumberFormatException.<init> (Ljava/lang/String;)V"), uses);

		// Lambdas are created by invokedynamic instructions, which mention the type they produce.
		String lambdas = call("search_references", "owner", "java/lang/Runnable");
		assertTrue(lambdas.contains(ClassWithLambda.class.getName().replace('.', '/') + ".runnable()V  invokedynamic run ()Ljava/lang/Runnable;"), lambdas);

		String annotation = AnnotationImpl.class.getName().replace('.', '/');
		String annotated = call("search_references", "owner", annotation);
		assertTrue(annotated.contains(ClassWithAnnotation.class.getName().replace('.', '/') + "  annotated with " + annotation), annotated);
	}

	@Test
	void declarationsAreFoundByNameAndKind() {
		openSample("hello.jar");
		String methods = call("search_declarations", "pattern", "is|magic");
		assertTrue(methods.contains("sample/util/Checks.isAdmin(Ljava/lang/String;)Z  [public static method]"), methods);
		assertTrue(methods.contains("sample/util/Checks.magic()I  [public static method]"), methods);
		assertTrue(methods.contains("declarations 1-2 of 2"), methods);

		String classes = call("search_declarations", "pattern", "^h", "kind", "class");
		assertTrue(classes.contains("sample/Hello  [class]") && !classes.contains("Checks"), classes);
		// A pattern with a slash is matched against the full class name.
		assertTrue(call("search_declarations", "pattern", "^sample/util/").contains("sample/util/Checks  [class]"));

		String fields = call("search_declarations", "pattern", "secret", "kind", "field");
		assertTrue(fields.contains("sample/Hello.SECRET Ljava/lang/String;  [private static final field]"), fields);
		assertTrue(call("search_declarations", "pattern", "secret", "kind", "method").startsWith("No method has a name matching"));

		String any = call("search_declarations", "pattern", "hello|greet|names");
		assertTrue(any.contains("sample/Hello  [class]") && any.contains("sample/Hello.greet(Ljava/lang/String;I)Ljava/lang/String;  [method]") &&
				any.contains("sample/Hello.names Ljava/util/List;  [private final field]"), any);

		// Constructors only match when asked for.
		assertTrue(call("search_declarations", "pattern", "init").startsWith("No class, field or method"));
		assertTrue(call("search_declarations", "pattern", "<init>").contains("sample/Hello.<init>()V  [public method]"));

		String scoped = call("search_declarations", "pattern", ".", "package", "sample.util");
		assertTrue(scoped.contains("sample/util/Checks") && !scoped.contains("sample/Hello"), scoped);
		assertTrue(call("search_declarations", "pattern", ".", "limit", 3).contains("offset=3"));

		assertTrue(callExpectingError("search_declarations", "pattern", "[").contains("Invalid regular expression"));
		assertTrue(callExpectingError("search_declarations", "pattern", "x", "kind", "package").contains("kind"));
		assertTrue(callExpectingError("search_declarations").contains("pattern"));
	}

	@Test
	void instructionsAreMatchedAsConsecutiveLines() {
		openSample("hello.jar");
		String single = call("search_instructions", "instructions", List.of("sipush 1337"));
		assertTrue(single.contains("sample/util/Checks.magic()I  sipush 1337"), single);
		assertTrue(single.contains("methods 1-1 of 1"), single);

		String sequence = call("search_instructions", "instructions",
				List.of("ldc \"user", "invokestatic java/lang/System\\.getProperty"));
		assertTrue(sequence.contains("sample/Hello.run()V  ldc \"user.name\" ; " +
				"invokestatic java/lang/System.getProperty (Ljava/lang/String;)Ljava/lang/String;"), sequence);

		// The last instruction of a method can be matched.
		String last = call("search_instructions", "instructions", List.of("^areturn$"));
		assertTrue(last.contains("sample/Hello.greet(Ljava/lang/String;I)Ljava/lang/String;  areturn") && last.contains("1-1 of 1"), last);

		// A sequence may cross a statement boundary: labels and line numbers in between are not instructions.
		String crossing = call("search_instructions", "instructions", List.of("^pop$", "^ldc \"user\\.name\"$"));
		assertTrue(crossing.contains("sample/Hello.run()V  pop ; ldc \"user.name\""), crossing);

		String repeated = call("search_instructions", "instructions", List.of("invokevirtual java/io/PrintStream\\.println"));
		assertTrue(repeated.contains("sample/Hello.run()V  invokevirtual java/io/PrintStream.println (Ljava/lang/String;)V  (+1 more in this method)"), repeated);

		// Wrong order does not match, and neither does anything outside of the scope.
		assertTrue(call("search_instructions", "instructions", List.of("invokestatic java/lang/System\\.getProperty", "ldc \"user"))
				.startsWith("No method contains 2 consecutive instructions"));
		assertTrue(call("search_instructions", "instructions", List.of("sipush 1337"), "package", "sample/Hello")
				.startsWith("No method contains an instruction"));
		assertTrue(call("search_instructions", "instructions", List.of("invoke"), "limit", 2).contains("offset=2"));
	}

	@Test
	void instructionSearchRejectsBadInput() {
		openSample("hello.jar");
		assertTrue(callExpectingError("search_instructions").contains("instructions"));
		assertTrue(callExpectingError("search_instructions", "instructions", List.of()).contains("instructions"));
		assertTrue(callExpectingError("search_instructions", "instructions", List.of("(")).contains("Invalid regular expression"));
		List<String> tooMany = new ArrayList<>();
		for (int i = 0; i < 17; i++)
			tooMany.add("nop");
		assertTrue(callExpectingError("search_instructions", "instructions", tooMany).contains("At most 16"));
	}

	@Test
	void filesAreSearchedLineByLine() {
		openSample("hello.jar");
		String found = call("search_files", "pattern", "EXAMPLE");
		assertTrue(found.contains("config/app.properties:1: endpoint=https://example.invalid/api"), found);
		assertTrue(found.contains("matching lines 1-1 of 1"), found);

		String manifest = call("search_files", "pattern", "version", "path_pattern", "^META-INF/");
		assertTrue(manifest.contains("META-INF/MANIFEST.MF:1: Manifest-Version: 1.0") && !manifest.contains("app.properties"), manifest);

		String all = call("search_files", "pattern", "=");
		assertTrue(all.contains("config/app.properties:1: ") && all.contains("config/app.properties:2: mode=debug"), all);
		assertTrue(call("search_files", "pattern", "=", "limit", 1).contains("offset=1"));

		String excluded = call("search_files", "pattern", "example", "path_pattern", "\\.MF$");
		assertTrue(excluded.startsWith("No line matches 'example' in 1 text files with a path matching"), excluded);
		assertTrue(call("search_files", "pattern", "(?-i)EXAMPLE").startsWith("No line matches"));

		assertTrue(callExpectingError("search_files", "pattern", "(").contains("Invalid regular expression"));
		assertTrue(callExpectingError("search_files", "pattern", "x", "path_pattern", "[").contains("path_pattern"));
		assertTrue(callExpectingError("search_files").contains("pattern"));
	}

	@Test
	void searchesWorkOnAndroidClasses() {
		openSample("sample.apk");
		String onCreate = "sample/app/MainActivity.onCreate(Landroid/os/Bundle;)V";

		String strings = call("search_strings", "query", "example.invalid");
		assertTrue(strings.contains("sample/app/MainActivity.ENDPOINT Ljava/lang/String;  \"https://example.invalid/collect\""), strings);
		assertTrue(strings.contains(onCreate + "  \"Reporting to https://example.invalid/collect with key \""), strings);

		String member = call("search_references", "owner", "sample.app.Keys", "name", "apiKey");
		assertTrue(member.contains(onCreate + "  invoke-static sample/app/Keys.apiKey()Ljava/lang/String;"), member);
		String framework = call("search_references", "owner", "android/util/Log", "name", "i");
		assertTrue(framework.contains(onCreate + "  invoke-static android/util/Log.i(Ljava/lang/String;Ljava/lang/String;)I"), framework);
		String type = call("search_references", "owner", "android/app/Activity");
		assertTrue(type.contains("sample/app/MainActivity  extends android/app/Activity"), type);
		assertTrue(type.contains(onCreate + "  invoke-super android/app/Activity.onCreate(Landroid/os/Bundle;)V"), type);

		String declarations = call("search_declarations", "pattern", "key");
		assertTrue(declarations.contains("sample/app/Keys  [class]"), declarations);
		assertTrue(declarations.contains("sample/app/Keys.apiKey()Ljava/lang/String;  [static method]"), declarations);

		String numbers = call("search_numbers", "value", "6");
		assertTrue(numbers.contains("sample/app/Keys.apiKey()Ljava/lang/String;  6"), numbers);

		// Instructions of Android classes are searched in their JVM translation.
		String instructions = call("search_instructions", "instructions", List.of("invokestatic sample/app/Keys\\.apiKey"));
		assertTrue(instructions.contains(onCreate + "  invokestatic sample/app/Keys.apiKey ()Ljava/lang/String;"), instructions);

		// The compiled manifest and resources of an APK are binary.
		String files = call("search_files", "pattern", "permission");
		assertTrue(files.startsWith("No line matches 'permission' in 0 text files") && files.contains("binary files were not searched"), files);
	}

	private static void open(@Nonnull Workspace workspace) {
		workspaceManager.setCurrentIgnoringConditions(workspace);
	}
}
