package software.coley.recaf.agent;

import jakarta.annotation.Nonnull;
import me.darknet.assembler.error.Outcome;
import org.junit.jupiter.api.Test;
import software.coley.recaf.agent.tool.AgentTool;
import software.coley.recaf.agent.tool.impl.EditTools;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.FileInfo;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.path.FilePathNode;
import software.coley.recaf.services.assembler.AssemblerPipelineManager;
import software.coley.recaf.services.comment.CommentManager;
import software.coley.recaf.services.workspace.io.ResourceImporter;
import software.coley.recaf.test.TestClassUtils;
import software.coley.recaf.test.dummy.HelloWorld;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResourceBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the tools that change the program: assembling, adding and removing content, undoing, exporting and
 * commenting.
 * <p>
 * The tools that print JASM are developed separately, so these tests disassemble through Recaf's assembler pipeline
 * directly. That is the text those tools hand to a model.
 */
class EditToolsTest extends AgentTestBase {
	private static final String CHECKS = "sample/util/Checks";
	private static final String HELLO = "sample/Hello";
	private static final String KEYS = "sample/app/Keys";
	private static final String ACTIVITY = "sample/app/MainActivity";

	@Test
	void toolDefinitionsAreWellFormed() {
		Map<String, Boolean> readOnlyByName = new TreeMap<>();
		for (AgentTool tool : new EditTools(toolbox.context()).tools()) {
			assertNotNull(toolbox.tool(tool.name()), "Not registered: " + tool.name());
			assertTrue(tool.name().matches("[a-z][a-z0-9_]{2,63}"), "Bad tool name: " + tool.name());
			assertTrue(tool.description().length() > 40, "Description of " + tool.name() + " is too short to guide a model");
			assertEquals("object", tool.inputSchema().get("type").getAsString());
			tool.inputSchema().getAsJsonArray("required").forEach(required ->
					assertTrue(tool.inputSchema().getAsJsonObject("properties").has(required.getAsString()),
							tool.name() + " requires an undeclared property: " + required));
			tool.inputSchema().getAsJsonObject("properties").entrySet().forEach(property ->
					assertTrue(property.getValue().getAsJsonObject().get("description").getAsString().length() > 10,
							tool.name() + "." + property.getKey() + " needs a description"));
			readOnlyByName.put(tool.name(), tool.readOnly());
		}
		// Everything that can change the workspace, or write outside of it, is marked as such.
		assertEquals(Map.of("assemble_class", false, "assemble_method", false, "put_file", false, "delete_class", false, "delete_file", false,
				"undo_change", false, "export_workspace", false, "set_comment", false, "get_comments", true), readOnlyByName);
	}

	@Test
	void assembleMethodChangesBehavior() throws Exception {
		Workspace workspace = openSample("hello.jar");
		assertEquals(1337, invokeStatic(workspace, CHECKS, "magic"));
		String source = disassembleMethod(workspace, CHECKS, "magic", "()I");
		assertTrue(source.contains("sipush 1337"), source);

		String result = call("assemble_method", "class", "sample.util.Checks", "source", source.replace("sipush 1337", "bipush 42"));
		assertTrue(result.contains("Replaced method magic()I of class sample/util/Checks"), result);
		assertTrue(result.contains("Methods 3 -> 3"), result);
		assertFalse(result.contains("warning"), result);

		// The class in the workspace now behaves differently, and Recaf knows it was modified.
		assertEquals(42, invokeStatic(workspace, CHECKS, "magic"));
		String decompiled = call("decompile_class", "class", CHECKS);
		assertTrue(decompiled.contains("return 42;") && !decompiled.contains("1337"), decompiled);
		assertTrue(disassembleMethod(workspace, CHECKS, "isAdmin", "(Ljava/lang/String;)Z").contains("ldc \"root\""), "Other methods are kept");
		JvmClassBundle bundle = workspace.getPrimaryResource().getJvmClassBundle();
		assertTrue(bundle.getDirtyKeys().contains(CHECKS), "The change went through the bundle");
		assertTrue(bundle.hasHistory(CHECKS));
	}

	@Test
	void assembleMethodReportsDiagnosticsWithLines() {
		Workspace workspace = openSample("hello.jar");
		ClassInfo before = classInfo(workspace, CHECKS);
		String source = disassembleMethod(workspace, CHECKS, "magic", "()I");

		// An instruction without its operand: the fifth line of the method source.
		String error = callExpectingError("assemble_method", "class", CHECKS, "source", source.replace("sipush 1337", "sipush"));
		assertTrue(error.contains("The assembler rejected the source"), error);
		assertTrue(error.contains("line 5, column 9"), error);
		assertTrue(error.contains("5 | sipush"), "The offending line is quoted: " + error);
		assertTrue(error.contains("Nothing was changed"), error);

		// An instruction that does not exist.
		error = callExpectingError("assemble_method", "class", CHECKS, "source", source.replace("ireturn", "frobnicate"));
		assertTrue(error.contains("line 6"), error);

		// Unbalanced braces are caught by the parser.
		error = callExpectingError("assemble_method", "class", CHECKS, "source", source.replace("code: {", "code: {{"));
		assertTrue(error.contains("line "), error);

		// The wrong kind of source, and nothing at all.
		error = callExpectingError("assemble_method", "class", CHECKS, "source", disassemble(workspace, CHECKS));
		assertTrue(error.contains("exactly one method declaration") && error.contains("assemble_class"), error);
		error = callExpectingError("assemble_method", "class", CHECKS, "source", "   \n");
		assertTrue(error.contains("no declaration at all") || error.contains("rejected"), error);

		// Classes that cannot be edited.
		assertTrue(callExpectingError("assemble_method", "class", "sample/util/Chekcs", "source", source).contains("No class named"));
		assertTrue(callExpectingError("assemble_method", "class", "java/lang/String", "source", source).contains("runtime library"));
		assertTrue(callExpectingError("assemble_method", "class", CHECKS, "source", source, "descriptor", "()I").contains("'method'"));

		assertSame(before, classInfo(workspace, CHECKS), "Failed calls must not touch the class");
		assertTrue(workspace.getPrimaryResource().getJvmClassBundle().getDirtyKeys().isEmpty());
	}

	@Test
	void assembleMethodAddsRenamesAndWarns() throws Exception {
		Workspace workspace = openSample("hello.jar");
		String magic = disassembleMethod(workspace, CHECKS, "magic", "()I");

		// A source that declares a method the class does not have is added to it.
		String added = call("assemble_method", "class", CHECKS, "source", magic.replace("magic", "answer").replace("sipush 1337", "bipush 7"));
		assertTrue(added.contains("Added method answer()I") && added.contains("Methods 3 -> 4"), added);
		assertEquals(7, invokeStatic(workspace, CHECKS, "answer"));
		assertEquals(1337, invokeStatic(workspace, CHECKS, "magic"));

		// Naming the method being replaced lets the source change the name.
		String isAdmin = disassembleMethod(workspace, CHECKS, "isAdmin", "(Ljava/lang/String;)Z");
		String replaced = call("assemble_method", "class", CHECKS, "source", isAdmin.replace("isAdmin", "isRoot"), "method", "isAdmin");
		assertTrue(replaced.contains("Replaced method isAdmin(Ljava/lang/String;)Z of class sample/util/Checks with isRoot(Ljava/lang/String;)Z"), replaced);
		assertTrue(replaced.contains("Methods 4 -> 4"), replaced);
		assertNotNull(classInfo(workspace, CHECKS).getDeclaredMethod("isRoot", "(Ljava/lang/String;)Z"));
		assertNull(classInfo(workspace, CHECKS).getDeclaredMethod("isAdmin", "(Ljava/lang/String;)Z"));

		// Replacing one existing method with another existing one would lose a method.
		String error = callExpectingError("assemble_method", "class", CHECKS, "source", magic.replace("magic", "answer"), "method", "magic");
		assertTrue(error.contains("already has"), error);
		assertTrue(callExpectingError("assemble_method", "class", CHECKS, "source", magic, "method", "nope").contains("no method named 'nope'"));

		// Code that the JVM would reject is still assembled, as in Recaf itself, but the warning is passed on.
		String warned = call("assemble_method", "class", CHECKS, "source", magic.replace("ireturn", "areturn"));
		assertTrue(warned.contains("Replaced method magic()I"), warned);
		assertTrue(warned.contains("warning") && warned.contains("line 6"), warned);
	}

	@Test
	void assembleClassReplacesAndAddsClasses() throws Exception {
		Workspace workspace = openSample("hello.jar");
		String source = disassemble(workspace, CHECKS);
		String extraMethod = """
				    .method public static extra ()I {
				        code: {
				        A:
				            bipush 9
				            ireturn
				        B:
				        }
				    }
				}
				""";
		String edited = source.replace("sipush 1337", "sipush 7331");
		edited = edited.substring(0, edited.lastIndexOf('}')) + extraMethod;

		String replaced = call("assemble_class", "source", edited);
		assertTrue(replaced.contains("Replaced class sample/util/Checks: fields 0 -> 0, methods 3 -> 4."), replaced);
		assertTrue(replaced.contains("Methods added: extra()I."), replaced);
		assertEquals(7331, invokeStatic(workspace, CHECKS, "magic"));
		assertEquals(9, invokeStatic(workspace, CHECKS, "extra"));
		assertTrue(workspace.getPrimaryResource().getJvmClassBundle().getDirtyKeys().contains(CHECKS));

		// Dropping the method again is reported as well, here with the class named as a safeguard.
		String dropped = call("assemble_class", "source", source, "class", "sample.util.Checks");
		assertTrue(dropped.contains("methods 4 -> 3") && dropped.contains("Methods removed: extra()I."), dropped);

		// A class name that does not exist yet adds a class.
		String added = call("assemble_class", "source", source.replace(CHECKS, "sample/util/Fresh"));
		assertTrue(added.contains("Added new class sample/util/Fresh with 0 fields and 3 methods."), added);
		assertEquals(1337, invokeStatic(workspace, "sample/util/Fresh", "magic"));
		assertTrue(call("list_classes").contains("sample/util/Fresh"));

		// The safeguard catches a source that declares another class than intended.
		String error = callExpectingError("assemble_class", "source", source.replace(CHECKS, "sample/util/Other"), "class", CHECKS);
		assertTrue(error.contains("declares class 'sample/util/Other'") && error.contains("rename_class"), error);
		assertNull(workspace.findClass("sample/util/Other"));

		// Errors carry positions in the class source.
		int line = lineOf(source, "sipush 1337");
		error = callExpectingError("assemble_class", "source", source.replace("sipush 1337", "sipush"));
		assertTrue(error.contains("line " + line + ","), error);
		error = callExpectingError("assemble_class", "source", disassembleMethod(workspace, CHECKS, "magic", "()I"));
		assertTrue(error.contains("exactly one class declaration") && error.contains("assemble_method"), error);
		assertTrue(callExpectingError("assemble_class", "source", source, "class", "java.lang.String").contains("runtime library"));
	}

	@Test
	void filesArePutAndDeleted() {
		Workspace workspace = openSample("hello.jar");

		String added = call("put_file", "path", "notes/readme.txt", "text", "patched by a test\n");
		assertTrue(added.contains("Added file notes/readme.txt") && added.contains("text"), added);
		assertEquals("patched by a test\n", fileText(workspace, "notes/readme.txt"));

		String original = fileText(workspace, "config/app.properties");
		String replaced = call("put_file", "path", "/config/app.properties", "text", original.replace("mode=debug", "mode=release"));
		assertTrue(replaced.contains("Replaced file config/app.properties"), replaced);
		assertTrue(fileText(workspace, "config/app.properties").contains("mode=release"));
		assertTrue(workspace.getPrimaryResource().getFileBundle().getDirtyKeys().contains("config/app.properties"));

		byte[] binary = {0, 1, 2, (byte) 0xFF, (byte) 0xFE, 0, 7};
		String binaryAdded = call("put_file", "path", "assets/blob.bin", "base64", Base64.getEncoder().encodeToString(binary));
		assertTrue(binaryAdded.contains("Added file assets/blob.bin (7 B, binary)"), binaryAdded);
		assertArrayEquals(binary, file(workspace, "assets/blob.bin").getRawContent());

		// Content has to come from exactly one of the two arguments and has to be usable.
		assertTrue(callExpectingError("put_file", "path", "a.txt").contains("exactly one"));
		assertTrue(callExpectingError("put_file", "path", "a.txt", "text", "a", "base64", "YQ==").contains("exactly one"));
		assertTrue(callExpectingError("put_file", "path", "a.bin", "base64", "not base64 !!").contains("not valid Base64"));
		assertTrue(callExpectingError("put_file", "path", "../outside.txt", "text", "a").contains("'..'"));
		assertTrue(callExpectingError("put_file", "path", "dir/", "text", "a").contains("does not name a file"));
		String classBytes = Base64.getEncoder().encodeToString(classInfo(workspace, CHECKS).asJvmClass().getBytecode());
		assertTrue(callExpectingError("put_file", "path", "x/Copy.class", "base64", classBytes).contains("assemble_class"));
		assertTrue(callExpectingError("put_file", "path", "sample/util/Checks.class", "text", "a").contains("not a file"));

		String deleted = call("delete_file", "path", "config/app.properties");
		assertTrue(deleted.contains("Deleted file config/app.properties"), deleted);
		assertNull(workspace.findFile("config/app.properties"));
		assertTrue(workspace.getPrimaryResource().getFileBundle().getRemovedKeys().contains("config/app.properties"));
		String error = callExpectingError("delete_file", "path", "config/app.properties");
		assertTrue(error.contains("No file named"), error);
	}

	@Test
	void classesAreDeletedAndRestored() throws Exception {
		Workspace workspace = openSample("hello.jar");

		String deleted = call("delete_class", "class", "sample.util.Checks");
		assertTrue(deleted.contains("Deleted class sample/util/Checks."), deleted);
		assertTrue(deleted.contains("1 class still refers to it") && deleted.contains(": sample/Hello."), deleted);
		assertNull(workspace.findClass(CHECKS));
		assertTrue(workspace.getPrimaryResource().getJvmClassBundle().getRemovedKeys().contains(CHECKS));
		assertTrue(callExpectingError("delete_class", "class", CHECKS).contains("No class named"));

		// A deletion can be taken back.
		String restored = call("undo_change", "class", CHECKS);
		assertTrue(restored.contains("Restored the deleted class sample/util/Checks") && restored.contains("original state"), restored);
		assertEquals(1337, invokeStatic(workspace, CHECKS, "magic"));
		assertTrue(workspace.getPrimaryResource().getJvmClassBundle().getRemovedKeys().isEmpty());
		assertTrue(workspace.getPrimaryResource().getJvmClassBundle().getDirtyKeys().isEmpty());

		// A class that was modified before it was deleted comes back modified, with its history.
		String magic = disassembleMethod(workspace, CHECKS, "magic", "()I");
		call("assemble_method", "class", CHECKS, "source", magic.replace("sipush 1337", "bipush 42"));
		call("delete_class", "class", CHECKS);
		restored = call("undo_change", "class", CHECKS);
		assertTrue(restored.contains("1 earlier change"), restored);
		assertEquals(42, invokeStatic(workspace, CHECKS, "magic"));
		assertTrue(call("undo_change", "class", CHECKS).contains("original state"));
		assertEquals(1337, invokeStatic(workspace, CHECKS, "magic"));

		// The same goes for files.
		call("delete_file", "path", "config/app.properties");
		assertTrue(call("undo_change", "path", "config/app.properties").contains("Restored the deleted file"));
		assertTrue(fileText(workspace, "config/app.properties").contains("mode=debug"));

		// Only the analyzed program can be changed, not the runtime and not supporting libraries.
		assertTrue(callExpectingError("delete_class", "class", "java/lang/String").contains("runtime library"));
		workspace.addSupportingResource(new WorkspaceResourceBuilder()
				.withJvmClassBundle(TestClassUtils.fromClasses(HelloWorld.class))
				.build());
		String library = HelloWorld.class.getName().replace('.', '/');
		assertTrue(callExpectingError("delete_class", "class", library).contains("supporting library"));
		assertTrue(callExpectingError("assemble_method", "class", library, "source", magic).contains("supporting library"));
		assertNotNull(workspace.findClass(library));
	}

	@Test
	void undoRevertsChangesStepByStep() throws Exception {
		Workspace workspace = openSample("hello.jar");
		byte[] original = classInfo(workspace, CHECKS).asJvmClass().getBytecode();
		String magic = disassembleMethod(workspace, CHECKS, "magic", "()I");
		call("assemble_method", "class", CHECKS, "source", magic.replace("sipush 1337", "bipush 42"));
		call("assemble_method", "class", CHECKS, "source", magic.replace("sipush 1337", "bipush 43"));
		call("assemble_method", "class", CHECKS, "source", magic.replace("sipush 1337", "bipush 44"));
		assertEquals(44, invokeStatic(workspace, CHECKS, "magic"));

		String undone = call("undo_change", "class", "sample.util.Checks");
		assertTrue(undone.contains("Reverted 1 change to class sample/util/Checks") && undone.contains("2 earlier changes remain"), undone);
		assertEquals(43, invokeStatic(workspace, CHECKS, "magic"));

		undone = call("undo_change", "class", CHECKS, "all", true);
		assertTrue(undone.contains("Reverted 2 changes") && undone.contains("original state"), undone);
		assertArrayEquals(original, classInfo(workspace, CHECKS).asJvmClass().getBytecode(), "The original class is back");
		assertTrue(workspace.getPrimaryResource().getJvmClassBundle().getDirtyKeys().isEmpty());
		String error = callExpectingError("undo_change", "class", CHECKS);
		assertTrue(error.contains("nothing to undo"), error);

		// Files have a history too.
		String originalText = fileText(workspace, "config/app.properties");
		call("put_file", "path", "config/app.properties", "text", "mode=release\n");
		undone = call("undo_change", "path", "config/app.properties");
		assertTrue(undone.contains("file config/app.properties") && undone.contains("original state"), undone);
		assertEquals(originalText, fileText(workspace, "config/app.properties"));

		assertTrue(callExpectingError("undo_change").contains("exactly one"));
		assertTrue(callExpectingError("undo_change", "class", CHECKS, "path", "config/app.properties").contains("exactly one"));
		assertTrue(callExpectingError("undo_change", "class", "sample/Nope").contains("No class named"));
		assertTrue(callExpectingError("undo_change", "path", "nope.txt").contains("No file named"));
	}

	@Test
	void exportContainsTheModifications() throws Exception {
		Workspace workspace = openSample("hello.jar");
		String magic = disassembleMethod(workspace, CHECKS, "magic", "()I");
		call("assemble_method", "class", CHECKS, "source", magic.replace("sipush 1337", "bipush 42"));
		call("put_file", "path", "notes/readme.txt", "text", "patched");
		call("delete_file", "path", "config/app.properties");

		String name = "edit-test-" + System.nanoTime() + ".jar";
		Path expected = toolbox.context().exportDirectory().toAbsolutePath().normalize().resolve(name);
		String exported = call("export_workspace", "file_name", name);
		assertTrue(exported.contains("as a JAR to " + expected), exported);
		assertTrue(Files.isRegularFile(expected), "Nothing was written to " + expected);
		assertTrue(exported.contains(" B)") || exported.contains(" KB)"), "The size is reported: " + exported);

		// Opening the exported file shows the edited program.
		Workspace reopened = importWorkspace(expected);
		assertEquals(42, invokeStatic(reopened, CHECKS, "magic"));
		assertNotNull(reopened.findClass(HELLO), "Untouched classes are exported too");
		assertEquals("patched", fileText(reopened, "notes/readme.txt"));
		assertNull(reopened.findFile("config/app.properties"));
		assertNotNull(reopened.findFile("META-INF/MANIFEST.MF"));

		// An existing export is only replaced on request.
		String error = callExpectingError("export_workspace", "file_name", name);
		assertTrue(error.contains("already exists") && error.contains("overwrite=true"), error);
		call("undo_change", "class", CHECKS);
		assertTrue(call("export_workspace", "file_name", name, "overwrite", true).contains(expected.toString()));
		assertEquals(1337, invokeStatic(importWorkspace(expected), CHECKS, "magic"));

		// Exports cannot leave the export directory.
		for (String bad : new String[]{"../escape.jar", "sub/dir.jar", "..", "a\\b.jar", "/tmp/abs.jar"})
			assertTrue(callExpectingError("export_workspace", "file_name", bad).contains("plain file name"), bad);
		try (var files = Files.list(expected.getParent())) {
			assertTrue(files.noneMatch(path -> path.getFileName().toString().endsWith(".tmp")), "No staging files remain");
		}
	}

	@Test
	void androidClassesAreEditedAndExported() throws Exception {
		Workspace workspace = openSample("sample.apk");
		ClassInfo originalKeys = classInfo(workspace, KEYS);
		String apiKey = disassembleMethod(workspace, KEYS, "apiKey", "()Ljava/lang/String;");
		assertTrue(apiKey.contains("0x006B") && apiKey.contains("fill-array-data"), apiKey);

		// Replace a method of a DEX class: the key starts with 'X' instead of 'k'.
		String replaced = call("assemble_method", "class", "sample.app.Keys", "source", apiKey.replace("0x006B", "0x0058"));
		assertTrue(replaced.contains("Replaced method apiKey()Ljava/lang/String; of class sample/app/Keys") && replaced.contains("Methods 2 -> 2"), replaced);
		String keys = disassemble(workspace, KEYS);
		assertTrue(keys.contains("0x0058") && !keys.contains("0x006B"), keys);
		assertTrue(keys.contains("java/lang/Object.<init> ()V"), "The other methods are kept: " + keys);
		assertTrue(call("decompile_class", "class", KEYS).contains("'X'"), "The translation to Java shows the change");
		assertNotSame(originalKeys, classInfo(workspace, KEYS));
		assertTrue(workspace.getPrimaryResource().getAndroidClassBundles().get("classes.dex").getDirtyKeys().contains(KEYS));

		// Replace a whole DEX class and add a new one.
		String activity = disassemble(workspace, ACTIVITY);
		String replacedClass = call("assemble_class", "source", activity.replace("\"SampleApp\"", "\"PatchedApp\""));
		assertTrue(replacedClass.contains("Replaced class sample/app/MainActivity: fields 1 -> 1, methods 2 -> 2."), replacedClass);
		assertTrue(disassemble(workspace, ACTIVITY).contains("\"PatchedApp\""));
		String onCreate = disassembleMethod(workspace, ACTIVITY, "onCreate", "(Landroid/os/Bundle;)V");
		call("assemble_method", "class", ACTIVITY, "source", onCreate.replace("\"PatchedApp\"", "\"TwicePatched\""));
		String patchedActivity = disassemble(workspace, ACTIVITY);
		assertTrue(patchedActivity.contains("\"TwicePatched\"") && patchedActivity.contains("android/app/Activity.<init>"), patchedActivity);
		String added = call("assemble_class", "source", disassemble(workspace, KEYS).replace(KEYS, "sample/app/Fresh"));
		assertTrue(added.contains("Added new class sample/app/Fresh to classes.dex"), added);
		assertTrue(classInfo(workspace, "sample/app/Fresh").isAndroidClass());

		// Errors in Dalvik source are reported the same way as in JVM source.
		String error = callExpectingError("assemble_method", "class", KEYS, "source", apiKey.replace("return-object v1", "return-object"));
		assertTrue(error.contains("The assembler rejected the source") && error.contains("line "), error);
		assertTrue(callExpectingError("put_file", "path", "classes.dex", "text", "x").contains("DEX file"));

		// The exported APK holds the edited classes.
		String name = "edit-test-" + System.nanoTime() + ".apk";
		String exported = call("export_workspace", "file_name", name);
		assertTrue(exported.contains("as an APK to ") && exported.contains("unsigned"), exported);
		Workspace reopened = importWorkspace(toolbox.context().exportDirectory().resolve(name));
		assertTrue(disassemble(reopened, KEYS).contains("0x0058"));
		assertTrue(disassemble(reopened, ACTIVITY).contains("\"TwicePatched\""));
		assertNotNull(reopened.findClass("sample/app/Fresh"));
		assertNotNull(reopened.findFile("AndroidManifest.xml"));

		// Undo works because the assembler was kept from writing into the original class.
		String undone = call("undo_change", "class", KEYS);
		assertTrue(undone.contains("original state"), undone);
		assertSame(originalKeys, classInfo(workspace, KEYS));
		assertTrue(disassemble(workspace, KEYS).contains("0x006B"), "The original class was changed in place");
		assertEquals(apiKey, disassembleMethod(workspace, KEYS, "apiKey", "()Ljava/lang/String;"));

		// A method of a DEX class can change its name when the replaced method is named.
		String renamed = call("assemble_method", "class", KEYS, "source", apiKey.replace("apiKey", "loadKey"), "method", "apiKey");
		assertTrue(renamed.contains("with loadKey()Ljava/lang/String;") && renamed.contains("Methods 2 -> 2"), renamed);
		assertNotNull(classInfo(workspace, KEYS).getDeclaredMethod("loadKey", "()Ljava/lang/String;"));
		assertNull(classInfo(workspace, KEYS).getDeclaredMethod("apiKey", "()Ljava/lang/String;"));
		assertNotNull(originalKeys.getDeclaredMethod("apiKey", "()Ljava/lang/String;"));
		assertNotNull(originalKeys.asAndroidClass().getBackingDefinition().getMethod("apiKey", "()Ljava/lang/String;"));
	}

	@Test
	void commentsAreSetListedAndShownInDecompilation() {
		Workspace workspace = openSample("hello.jar");
		// Comments are kept per input file for the lifetime of Recaf, start from a clean slate.
		recaf.get(CommentManager.class).removeWorkspaceComments(workspace);
		assertTrue(call("get_comments", "class", CHECKS).contains("No comments"));
		assertFalse(call("decompile_class", "class", CHECKS).contains("Helper"), "Decompiled before commenting, so the result is cached");

		String set = call("set_comment", "class", "sample.util.Checks", "comment", "Helper checks used by Hello");
		assertTrue(set.contains("Added a comment to class sample/util/Checks"), set);
		set = call("set_comment", "class", CHECKS, "kind", "method", "name", "magic", "comment", "Returns the magic number\nthat unlocks admin mode");
		assertTrue(set.contains("Added a comment to method sample/util/Checks.magic()I"), set);
		set = call("set_comment", "class", HELLO, "kind", "field", "name", "SECRET", "descriptor", "Ljava/lang/String;", "comment", "Hardcoded token");
		assertTrue(set.contains("field sample/Hello.SECRET Ljava/lang/String;"), set);

		String listed = call("get_comments", "class", CHECKS);
		assertTrue(listed.contains("2 comments in class sample/util/Checks"), listed);
		assertTrue(listed.contains("class sample/util/Checks:\n  Helper checks used by Hello"), listed);
		assertTrue(listed.contains("method magic()I:\n  Returns the magic number\n  that unlocks admin mode"), listed);
		assertTrue(call("get_comments", "class", HELLO).contains("field SECRET Ljava/lang/String;:\n  Hardcoded token"));

		// Recaf writes the comments into decompiled source.
		String decompiled = call("decompile_class", "class", CHECKS);
		assertTrue(decompiled.contains("Helper checks used by Hello"), decompiled);
		assertTrue(decompiled.contains("Returns the magic number"), decompiled);
		assertTrue(call("decompile_class", "class", HELLO).contains("Hardcoded token"));

		// Comments are replaced and removed.
		assertTrue(call("set_comment", "class", CHECKS, "comment", "Access checks").contains("Replaced the comment on class"));
		String removed = call("set_comment", "class", CHECKS, "kind", "method", "name", "magic");
		assertTrue(removed.contains("Removed the comment from method sample/util/Checks.magic()I"), removed);
		assertTrue(call("set_comment", "class", CHECKS, "kind", "method", "name", "magic", "comment", "").contains("no comment"));
		listed = call("get_comments", "class", CHECKS);
		assertTrue(listed.contains("1 comment in") && listed.contains("Access checks") && !listed.contains("magic"), listed);
		decompiled = call("decompile_class", "class", CHECKS);
		assertTrue(decompiled.contains("Access checks") && !decompiled.contains("Returns the magic number"), decompiled);

		assertTrue(callExpectingError("set_comment", "class", CHECKS, "kind", "method", "comment", "x").contains("'name'"));
		assertTrue(callExpectingError("set_comment", "class", CHECKS, "name", "magic", "comment", "x").contains("kind"));
		assertTrue(callExpectingError("set_comment", "class", CHECKS, "kind", "method", "name", "nope", "comment", "x").contains("no method named 'nope'"));
		assertTrue(callExpectingError("set_comment", "class", "sample/Nope", "comment", "x").contains("No class named"));
		assertTrue(callExpectingError("get_comments", "class", "sample/Nope").contains("No class named"));
		recaf.get(CommentManager.class).removeWorkspaceComments(workspace);
	}

	@Test
	void commentsWorkForAndroidClasses() {
		Workspace workspace = openSample("sample.apk");
		recaf.get(CommentManager.class).removeWorkspaceComments(workspace);
		assertFalse(call("decompile_class", "class", KEYS).contains("Builds the API key"));
		call("set_comment", "class", KEYS, "kind", "method", "name", "apiKey", "comment", "Builds the API key");
		assertTrue(call("get_comments", "class", KEYS).contains("method apiKey()Ljava/lang/String;:\n  Builds the API key"));
		assertTrue(call("decompile_class", "class", KEYS).contains("Builds the API key"));
		recaf.get(CommentManager.class).removeWorkspaceComments(workspace);
	}

	@Nonnull
	private static ClassInfo classInfo(@Nonnull Workspace workspace, @Nonnull String name) {
		ClassPathNode path = workspace.findClass(name);
		assertNotNull(path, "No class " + name);
		return path.getValue();
	}

	@Nonnull
	private static FileInfo file(@Nonnull Workspace workspace, @Nonnull String name) {
		FilePathNode path = workspace.findFile(name);
		assertNotNull(path, "No file " + name);
		return path.getValue();
	}

	@Nonnull
	private static String fileText(@Nonnull Workspace workspace, @Nonnull String name) {
		return new String(file(workspace, name).getRawContent(), StandardCharsets.UTF_8);
	}

	/**
	 * @return The class in JASM, as a tool printing a whole class would return it.
	 */
	@Nonnull
	private static String disassemble(@Nonnull Workspace workspace, @Nonnull String className) {
		ClassPathNode path = workspace.findClass(className);
		assertNotNull(path, "No class " + className);
		return text(recaf.get(AssemblerPipelineManager.class).getPipeline(path).disassemble(path));
	}

	/**
	 * @return The method in JASM, as a tool printing a single method would return it.
	 */
	@Nonnull
	private static String disassembleMethod(@Nonnull Workspace workspace, @Nonnull String className,
	                                        @Nonnull String name, @Nonnull String descriptor) {
		ClassPathNode path = workspace.findClass(className);
		assertNotNull(path, "No class " + className);
		MethodMember method = path.getValue().getDeclaredMethod(name, descriptor);
		assertNotNull(method, "No method " + name + descriptor);
		return text(recaf.get(AssemblerPipelineManager.class).getPipeline(path).disassemble(path.child(method)));
	}

	@Nonnull
	private static String text(@Nonnull Outcome<String> outcome) {
		assertTrue(outcome.isSuccess(), "Disassembly failed: " + outcome.diagnostics());
		return outcome.requireValue();
	}

	/**
	 * @return Result of running the static, argument-less method of the class as it currently is in the workspace.
	 */
	private static Object invokeStatic(@Nonnull Workspace workspace, @Nonnull String className, @Nonnull String method) throws Exception {
		byte[] bytecode = classInfo(workspace, className).asJvmClass().getBytecode();
		ClassLoader loader = new ClassLoader(EditToolsTest.class.getClassLoader()) {
			@Override
			protected Class<?> findClass(String name) {
				return defineClass(name, bytecode, 0, bytecode.length);
			}
		};
		return loader.loadClass(className.replace('/', '.')).getDeclaredMethod(method).invoke(null);
	}

	@Nonnull
	private static Workspace importWorkspace(@Nonnull Path path) throws IOException {
		return new BasicWorkspace(recaf.get(ResourceImporter.class).importResource(path));
	}

	private static int lineOf(@Nonnull String text, @Nonnull String fragment) {
		int index = text.indexOf(fragment);
		assertTrue(index >= 0, "Missing: " + fragment);
		return (int) text.substring(0, index).chars().filter(c -> c == '\n').count() + 1;
	}
}
