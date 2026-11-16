package software.coley.recaf.agent;

import jakarta.annotation.Nonnull;
import org.junit.jupiter.api.Test;
import software.coley.recaf.agent.tool.AgentTool;
import software.coley.recaf.agent.tool.impl.EditTools;
import software.coley.recaf.agent.tool.impl.MappingTools;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.test.TestClassUtils;
import software.coley.recaf.test.dummy.ClassWithInner;
import software.coley.recaf.test.dummy.OverlapCaller;
import software.coley.recaf.test.dummy.OverlapClassAB;
import software.coley.recaf.test.dummy.OverlapInterfaceA;
import software.coley.recaf.test.dummy.OverlapInterfaceB;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.AndroidClassBundle;

import java.io.IOException;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the renaming tools.
 */
class MappingToolsTest extends AgentTestBase {
	private static final String CHECKS = "sample/util/Checks";
	private static final String GUARD = "sample/util/Guard";
	private static final String HELLO = "sample/Hello";
	private static final String IS_ADMIN = "(Ljava/lang/String;)Z";
	private static final String KEYS = "sample/app/Keys";
	private static final String ACTIVITY = "sample/app/MainActivity";

	@Test
	void toolDefinitionsAreWellFormed() {
		Map<String, Boolean> readOnlyByName = new TreeMap<>();
		for (AgentTool tool : new MappingTools(toolbox.context()).tools()) {
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
		assertEquals(Map.of("rename_class", false, "rename_member", false, "apply_mappings", false, "export_mappings", true), readOnlyByName);
	}

	@Test
	void renameClassRewritesReferences() {
		Workspace workspace = openSample("hello.jar");
		assertTrue(classInfo(workspace, HELLO).asJvmClass().getReferencedClasses().contains(CHECKS));

		String renamed = call("rename_class", "class", "sample.util.Checks", "new_name", "sample.util.Guard");
		assertTrue(renamed.contains("Renamed class sample/util/Checks to sample/util/Guard."), renamed);
		assertTrue(renamed.contains("Updated 2 classes"), renamed);

		// The class goes by its new name, and the class using it refers to that name.
		assertNull(workspace.findClass(CHECKS));
		assertNotNull(classInfo(workspace, GUARD).getDeclaredMethod("magic", "()I"));
		assertTrue(classInfo(workspace, HELLO).asJvmClass().getReferencedClasses().contains(GUARD));
		String hello = call("decompile_class", "class", HELLO);
		assertTrue(hello.contains("Guard.isAdmin(") && !hello.contains("Checks"), hello);
		assertTrue(callExpectingError("rename_class", "class", CHECKS, "new_name", "sample/util/Other").contains("No class named"));

		// A name without a package keeps the class where it is.
		String moved = call("rename_class", "class", GUARD, "new_name", "AccessChecks");
		assertTrue(moved.contains("to sample/util/AccessChecks.") && !moved.contains("Caution"), moved);
		assertNotNull(workspace.findClass("sample/util/AccessChecks"));
		moved = call("rename_class", "class", "sample/util/AccessChecks", "new_name", "sample/security/AccessChecks");
		assertTrue(moved.contains("Caution: the class is in another package now"), moved);
		assertTrue(call("decompile_class", "class", HELLO).contains("import sample.security.AccessChecks;"));
	}

	@Test
	void undoDoesNotTakeBackHalfOfARename() {
		Workspace workspace = openSample("hello.jar");
		call("put_file", "path", "config/app.properties", "text", "mode=release\n");
		call("rename_class", "class", CHECKS, "new_name", GUARD);

		// Hello was rewritten to call Guard. Reverting that alone would make it call a class that is gone.
		for (boolean all : new boolean[]{false, true}) {
			String error = callExpectingError("undo_change", "class", HELLO, "all", all);
			assertTrue(error.contains("Cannot revert class sample/Hello") && error.contains("rename_class"), error);
		}
		assertTrue(call("decompile_class", "class", HELLO).contains("Guard.isAdmin("));
		String error = callExpectingError("undo_change", "class", GUARD);
		assertTrue(error.contains("renamed from 'sample/util/Checks'"), error);

		// Changes that are not renames are reverted as usual, and renaming back restores the names.
		assertTrue(call("undo_change", "path", "config/app.properties").contains("original state"));
		call("rename_class", "class", GUARD, "new_name", CHECKS);
		assertTrue(call("decompile_class", "class", HELLO).contains("Checks.isAdmin("));
	}

	@Test
	void renameClassRejectsNamesThatDoNotWork() {
		Workspace workspace = openSample("hello.jar");
		String error = callExpectingError("rename_class", "class", CHECKS, "new_name", "sample/Hello");
		assertTrue(error.contains("Nothing was renamed") && error.contains("A class named 'sample/Hello' already exists"), error);
		assertTrue(callExpectingError("rename_class", "class", CHECKS, "new_name", "java.lang.String").contains("already exists"));
		assertTrue(callExpectingError("rename_class", "class", CHECKS, "new_name", "sample/util/Bad;Name").contains("not a valid class name"));
		assertTrue(callExpectingError("rename_class", "class", CHECKS, "new_name", "sample.util.Checks").contains("already named"));
		assertTrue(callExpectingError("rename_class", "class", "java/lang/String", "new_name", "sample/Text").contains("runtime library"));
		assertTrue(callExpectingError("rename_class", "class", "sample/Nope", "new_name", "sample/Other").contains("No class named"));

		assertNotNull(workspace.findClass(CHECKS), "Rejected renames must not change anything");
		assertTrue(workspace.getPrimaryResource().getJvmClassBundle().getDirtyKeys().isEmpty());
		assertTrue(call("export_mappings").contains("No renames have been applied"));
	}

	@Test
	void renameClassTakesInnerClassesAlong() throws IOException {
		Workspace workspace = open(ClassWithInner.class, ClassWithInner.TheInner.class);
		String outer = name(ClassWithInner.class);
		String renamedOuter = outer.substring(0, outer.lastIndexOf('/') + 1) + "Outer";

		String renamed = call("rename_class", "class", outer, "new_name", "Outer");
		assertTrue(renamed.contains("to " + renamedOuter + ", together with 1 inner class."), renamed);
		// The classes of the test fixtures are also on the class path of the tests, which Recaf models as runtime library.
		assertNotNull(workspace.findClass(false, renamedOuter));
		assertNotNull(workspace.findClass(false, renamedOuter + "$TheInner"));
		assertNull(workspace.findClass(false, outer));
		assertNull(workspace.findClass(false, outer + "$TheInner"));
		assertTrue(classInfo(workspace, renamedOuter + "$TheInner").getInnerClasses().stream()
				.anyMatch(inner -> renamedOuter.equals(inner.getOuterClassName())), "The inner class points at its renamed outer class");
		assertTrue(call("decompile_class", "class", renamedOuter).contains("class Outer"));
	}

	@Test
	void renameMemberRewritesReferences() {
		Workspace workspace = openSample("hello.jar");

		String field = call("rename_member", "class", "sample.Hello", "kind", "field", "name", "SECRET", "new_name", "TOKEN");
		assertTrue(field.contains("Renamed field sample/Hello.SECRET Ljava/lang/String; to TOKEN.") && field.contains("Updated 1 class."), field);
		assertNotNull(classInfo(workspace, HELLO).getDeclaredField("TOKEN", "Ljava/lang/String;"));
		assertNull(classInfo(workspace, HELLO).getDeclaredField("SECRET", "Ljava/lang/String;"));

		// A static method called from another class.
		String method = call("rename_member", "class", CHECKS, "kind", "method", "name", "isAdmin", "descriptor", IS_ADMIN, "new_name", "isRoot");
		assertTrue(method.contains("Renamed method sample/util/Checks.isAdmin(Ljava/lang/String;)Z to isRoot.") && method.contains("Updated 2 classes"), method);
		assertNotNull(classInfo(workspace, CHECKS).getDeclaredMethod("isRoot", IS_ADMIN));
		assertTrue(call("decompile_class", "class", HELLO).contains("Checks.isRoot("));

		// An instance method called from within its class.
		call("rename_member", "class", HELLO, "kind", "method", "name", "greet", "new_name", "buildGreeting");
		String hello = call("decompile_class", "class", HELLO);
		assertTrue(hello.contains("this.buildGreeting(") && !hello.contains("greet("), hello);
	}

	@Test
	void renameMemberRejectsNamesThatBreakTheProgram() {
		Workspace workspace = openSample("hello.jar");

		// Names that are taken.
		String error = callExpectingError("rename_member", "class", HELLO, "kind", "field", "name", "names", "new_name", "SECRET");
		assertTrue(error.contains("Nothing was renamed") && error.contains("already declares a field named 'SECRET'"), error);
		assertTrue(callExpectingError("rename_member", "class", HELLO, "kind", "field", "name", "SECRET", "new_name", "SECRET").contains("already named"));

		// run() implements Runnable.run(), which is not ours to rename.
		error = callExpectingError("rename_member", "class", HELLO, "kind", "method", "name", "run", "new_name", "execute");
		assertTrue(error.contains("java/lang/Runnable.run") && error.contains("has to keep its name"), error);
		assertTrue(callExpectingError("rename_member", "class", HELLO, "kind", "method", "name", "<init>", "new_name", "create").contains("constructor"));
		assertTrue(callExpectingError("rename_member", "class", HELLO, "kind", "method", "name", "greet", "new_name", "a.b").contains("not a valid method name"));
		assertTrue(callExpectingError("rename_member", "class", HELLO, "kind", "field", "name", "names", "new_name", "a/b").contains("not a valid field name"));

		// Members and classes that cannot be found or changed.
		assertTrue(callExpectingError("rename_member", "class", HELLO, "kind", "method", "name", "nope", "new_name", "x").contains("no method named 'nope'"));
		assertTrue(callExpectingError("rename_member", "class", HELLO, "kind", "field", "name", "nope", "new_name", "x").contains("no field named 'nope'"));
		assertTrue(callExpectingError("rename_member", "class", "java/lang/String", "kind", "method", "name", "length", "new_name", "size").contains("runtime library"));

		assertTrue(workspace.getPrimaryResource().getJvmClassBundle().getDirtyKeys().isEmpty(), "Rejected renames must not change anything");
	}

	@Test
	void renameMethodCoversTheWholeOverrideFamily() throws IOException {
		Workspace workspace = open(OverlapInterfaceA.class, OverlapInterfaceB.class, OverlapClassAB.class, OverlapCaller.class);
		String interfaceA = name(OverlapInterfaceA.class);
		String interfaceB = name(OverlapInterfaceB.class);
		String classAB = name(OverlapClassAB.class);
		String caller = name(OverlapCaller.class);

		// methodA() is declared by both interfaces and implemented by the class. Renaming it to the name of another
		// method of that family would merge the two.
		String error = callExpectingError("rename_member", "class", interfaceA, "kind", "method", "name", "methodA", "new_name", "methodB");
		assertTrue(error.contains("already declares a method 'methodB()V'"), error);
		assertNotNull(classInfo(workspace, classAB).getDeclaredMethod("methodA", "()V"));

		// Renaming the implementation renames the declarations in both interfaces, and the calls through all three types.
		String renamed = call("rename_member", "class", classAB, "kind", "method", "name", "methodA", "new_name", "shared");
		assertTrue(renamed.contains("all 3 declarations in the class hierarchy were renamed"), renamed);
		assertTrue(renamed.contains(interfaceA) && renamed.contains(interfaceB), renamed);
		assertTrue(renamed.contains("Updated 4 classes"), renamed);
		for (String owner : new String[]{interfaceA, interfaceB, classAB}) {
			assertNotNull(classInfo(workspace, owner).getDeclaredMethod("shared", "()V"), owner);
			assertNull(classInfo(workspace, owner).getDeclaredMethod("methodA", "()V"), owner);
		}
		String callerSource = call("decompile_class", "class", caller);
		assertTrue(callerSource.contains(".shared()") && !callerSource.contains("methodA"), callerSource);

		// methodB() is only declared by one of the interfaces. Renaming it there reaches the implementation.
		renamed = call("rename_member", "class", interfaceB, "kind", "method", "name", "methodB", "new_name", "onlyB");
		assertTrue(renamed.contains("all 2 declarations"), renamed);
		assertNotNull(classInfo(workspace, classAB).getDeclaredMethod("onlyB", "()V"));
		assertNull(classInfo(workspace, interfaceA).getDeclaredMethod("onlyB", "()V"));
	}

	@Test
	void androidClassesAreRenamedOnlyWhenRecafCanRewriteAllOfThem() {
		Workspace workspace = openSample("sample.apk");
		AndroidClassBundle dex = workspace.getPrimaryResource().getAndroidClassBundles().get("classes.dex");

		// onCreate() overrides android/app/Activity.onCreate(), which Recaf knows from its model of the Android API.
		String error = callExpectingError("rename_member", "class", ACTIVITY, "kind", "method", "name", "onCreate", "new_name", "start");
		assertTrue(error.contains("android/app/Activity.onCreate"), error);

		// Recaf's rewriting of DEX classes fails on MainActivity and would silently skip it. Renaming Keys would then
		// leave the call in MainActivity pointing at a class that no longer exists, so the rename is refused.
		error = callExpectingError("rename_class", "class", "sample.app.Keys", "new_name", "sample.app.KeyVault");
		assertTrue(error.contains("Nothing was renamed") && error.contains("1 of the 2 Android (DEX) classes"), error);
		assertTrue(error.contains("sample/app/MainActivity") && error.contains("assemble_method"), error);
		error = callExpectingError("rename_member", "class", KEYS, "kind", "method", "name", "apiKey", "new_name", "loadKey");
		assertTrue(error.contains("Nothing was renamed"), error);
		error = callExpectingError("apply_mappings", "mappings", "sample/app/Keys sample/app/KeyVault");
		assertTrue(error.contains("Nothing was renamed"), error);
		assertNotNull(classInfo(workspace, KEYS).getDeclaredMethod("apiKey", "()Ljava/lang/String;"));
		assertTrue(dex.getDirtyKeys().isEmpty() && dex.getRemovedKeys().isEmpty(), "Refused renames must not change anything");
		assertTrue(call("export_mappings").contains("No renames have been applied"));

		// Without the class that Recaf cannot rewrite, DEX classes are renamed like JVM classes.
		dex.remove(ACTIVITY);
		String renamed = call("rename_class", "class", "sample.app.Keys", "new_name", "sample.app.KeyVault");
		assertTrue(renamed.contains("Renamed class sample/app/Keys to sample/app/KeyVault.") && renamed.contains("Updated 1 class."), renamed);
		assertNull(workspace.findClass(KEYS));
		assertTrue(classInfo(workspace, "sample/app/KeyVault").isAndroidClass());
		renamed = call("rename_member", "class", "sample/app/KeyVault", "kind", "method", "name", "apiKey", "new_name", "loadKey");
		assertTrue(renamed.contains("to loadKey."), renamed);
		assertTrue(call("decompile_class", "class", "sample/app/KeyVault").contains("String loadKey()"));

		String exported = call("export_mappings");
		assertTrue(exported.contains("sample/app/Keys sample/app/KeyVault"), exported);
		assertTrue(exported.contains("sample/app/Keys.apiKey()Ljava/lang/String; loadKey"), exported);
	}

	@Test
	void mappingsAreAppliedInBulkAndExported() {
		Workspace workspace = openSample("hello.jar");
		assertTrue(call("export_mappings").contains("No renames have been applied"));

		String mappings = """
				# names found while reading the code
				sample/util/Checks sample/util/Guard
				sample/util/Checks.isAdmin(Ljava/lang/String;)Z isRoot
				sample/Hello.SECRET Ljava/lang/String; TOKEN
				sample/Missing sample/Gone
				sample/Hello.missing()V gone
				""";
		String applied = call("apply_mappings", "mappings", mappings);
		assertTrue(applied.contains("Applied 3 renames from the Simple mappings: 1 class, 1 field, 1 method."), applied);
		assertTrue(applied.contains("Updated 2 classes"), applied);
		assertTrue(applied.contains("Skipped 2 entries") && applied.contains("class sample/Missing") && applied.contains("method sample/Hello.missing()V"), applied);
		assertNotNull(classInfo(workspace, GUARD).getDeclaredMethod("isRoot", IS_ADMIN));
		assertNotNull(classInfo(workspace, HELLO).getDeclaredField("TOKEN", "Ljava/lang/String;"));
		assertTrue(call("decompile_class", "class", HELLO).contains("Guard.isRoot("));

		// The export maps the names of the input to the current names.
		String exported = call("export_mappings");
		assertTrue(exported.contains("3 renames"), exported);
		assertTrue(exported.contains("sample/util/Checks sample/util/Guard"), exported);
		assertTrue(exported.contains("sample/util/Checks.isAdmin(Ljava/lang/String;)Z isRoot"), exported);
		assertTrue(exported.contains("sample/Hello.SECRET Ljava/lang/String; TOKEN"), exported);
		assertFalse(exported.contains("Missing"), exported);

		// Later renames of the same thing are folded into one entry from the original name.
		call("rename_member", "class", GUARD, "kind", "method", "name", "isRoot", "new_name", "hasRootAccess");
		exported = call("export_mappings", "format", "simple");
		assertTrue(exported.contains("sample/util/Checks.isAdmin(Ljava/lang/String;)Z hasRootAccess") && !exported.contains("isRoot"), exported);
		assertTrue(call("export_mappings", "format", "tiny-v2").contains("tiny\t2\t0"));
		assertTrue(call("export_mappings", "max_lines", 1).contains("start_line=2"));

		// What was exported reproduces the names on the original input.
		String simple = exported.substring(exported.indexOf('\n') + 1);
		workspace = openSample("hello.jar");
		String reapplied = call("apply_mappings", "mappings", simple, "format", "Simple");
		assertTrue(reapplied.contains("Applied 3 renames"), reapplied);
		assertNotNull(classInfo(workspace, GUARD).getDeclaredMethod("hasRootAccess", IS_ADMIN));
		assertNotNull(classInfo(workspace, HELLO).getDeclaredField("TOKEN", "Ljava/lang/String;"));
	}

	@Test
	void mappingsThatDoNotWorkAreRejectedAsAWhole() {
		Workspace workspace = openSample("hello.jar");

		String error = callExpectingError("apply_mappings", "mappings", "sample/Hello sample/Main", "format", "nope");
		assertTrue(error.contains("no mapping format named 'nope'") && error.contains("Simple") && error.contains("Proguard"), error);
		assertTrue(callExpectingError("apply_mappings", "mappings", "# nothing here\n").contains("No renames were found"));
		error = callExpectingError("apply_mappings", "mappings", "sample.util.Checks sample.util.Guard\nsample/Gone sample/Other");
		assertTrue(error.contains("None of the 2 entries") && error.contains("internal form"), error);

		// One good entry and two that break the rules: nothing is applied.
		error = callExpectingError("apply_mappings", "mappings", """
				sample/util/Checks.magic()I answer
				sample/util/Checks sample/Hello
				sample/Hello.run()V execute
				""");
		assertTrue(error.contains("Nothing was renamed, because 2 of the renames are not possible"), error);
		assertTrue(error.contains("A class named 'sample/Hello' already exists"), error);
		assertTrue(error.contains("java/lang/Runnable.run"), error);
		assertNotNull(classInfo(workspace, CHECKS).getDeclaredMethod("magic", "()I"));
		assertTrue(workspace.getPrimaryResource().getJvmClassBundle().getDirtyKeys().isEmpty());

		// Two classes cannot take the same name, and a name only becomes free in a later call.
		error = callExpectingError("apply_mappings", "mappings", "sample/util/Checks sample/Same\nsample/Hello sample/Same");
		assertTrue(error.contains("would both be named 'sample/Same'"), error);
		error = callExpectingError("apply_mappings", "mappings", "sample/util/Checks sample/Hello\nsample/Hello sample/Main");
		assertTrue(error.contains("rename 'sample/Hello' first in a call of its own"), error);
		assertNotNull(workspace.findClass(CHECKS));
		call("apply_mappings", "mappings", "sample/Hello sample/Main");
		String moved = call("apply_mappings", "mappings", "sample/util/Checks sample/Hello");
		assertTrue(moved.contains("Applied 1 rename from"), moved);
		assertNotNull(classInfo(workspace, HELLO).getDeclaredMethod("magic", "()I"));
		assertNotNull(classInfo(workspace, "sample/Main").getDeclaredMethod("greet", "(Ljava/lang/String;I)Ljava/lang/String;"));

		// Members of a class can trade names within one call, each reference is rewritten once.
		String swapped = call("apply_mappings", "mappings", "sample/Main.SECRET Ljava/lang/String; names\nsample/Main.names Ljava/util/List; SECRET");
		assertTrue(swapped.contains("Applied 2 renames"), swapped);
		assertNotNull(classInfo(workspace, "sample/Main").getDeclaredField("names", "Ljava/lang/String;"));
		assertNotNull(classInfo(workspace, "sample/Main").getDeclaredField("SECRET", "Ljava/util/List;"));
	}

	@Nonnull
	private static Workspace open(@Nonnull Class<?>... classes) throws IOException {
		Workspace workspace = TestClassUtils.fromBundle(TestClassUtils.fromClasses(classes));
		workspaceManager.setCurrentIgnoringConditions(workspace);
		return workspace;
	}

	@Nonnull
	private static String name(@Nonnull Class<?> type) {
		return type.getName().replace('.', '/');
	}

	@Nonnull
	private static ClassInfo classInfo(@Nonnull Workspace workspace, @Nonnull String name) {
		ClassPathNode path = workspace.findClass(name);
		assertNotNull(path, "No class " + name);
		return path.getValue();
	}
}
