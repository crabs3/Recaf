package software.coley.recaf.agent;

import jakarta.annotation.Nonnull;
import me.darknet.assembler.error.Outcome;
import org.junit.jupiter.api.Test;
import software.coley.recaf.agent.tool.AgentTool;
import software.coley.recaf.agent.tool.impl.EditTools;
import software.coley.recaf.agent.tool.impl.TransformTools;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.assembler.AssemblerPipelineManager;
import software.coley.recaf.services.transform.JvmClassTransformer;
import software.coley.recaf.services.transform.TransformationManager;
import software.coley.recaf.test.TestClassUtils;
import software.coley.recaf.workspace.model.Workspace;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the tools that run Recaf's deobfuscation transformers.
 */
class TransformToolsTest extends AgentTestBase {
	private static final String FOLDED = "sample/fold/Folded";
	/** A method computing {@code 1 + 2} at runtime, which is what constant folding exists to clean up. */
	private static final String FOLDED_SOURCE = """
			.super java/lang/Object
			.class public super sample/fold/Folded {
			    .method public static three ()I {
			        code: {
			        A:
			            iconst_1
			            iconst_2
			            iadd
			            ireturn
			        B:
			        }
			    }
			}
			""";

	@Test
	void toolDefinitionsAreWellFormed() {
		Map<String, Boolean> readOnlyByName = new TreeMap<>();
		for (AgentTool tool : new TransformTools(toolbox.context()).tools()) {
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
		assertEquals(Map.of("list_transformers", true, "apply_transformers", false), readOnlyByName);
	}

	@Test
	void everyTransformerIsListedWithADescription() throws Exception {
		String listed = call("list_transformers");
		TransformationManager manager = recaf.get(TransformationManager.class);
		assertTrue(listed.startsWith(manager.getJvmClassTransformers().size() + " transformers"), listed);
		for (Class<? extends JvmClassTransformer> type : manager.getJvmClassTransformers()) {
			String identifier = manager.newJvmTransformer(type).identifier();
			assertTrue(listed.contains("\n" + identifier + "  "), "Not listed: " + identifier);
		}
		// A transformer added to Recaf needs a line in the description table of the tools.
		assertFalse(listed.contains("No description available"), listed);
		assertTrue(listed.contains("cleanup.deadcode  Removes instructions that can never be executed."), listed);
		assertTrue(listed.contains("peephole.data.staticinline") && listed.contains("[runs peephole.data.staticcollect first on its own]"), listed);
		assertTrue(listed.contains("[best after "), listed);
	}

	@Test
	void dryRunReportsAndApplyChanges() {
		Workspace workspace = openSample("hello.jar");
		call("assemble_class", "source", FOLDED_SOURCE);
		ClassInfo before = classInfo(workspace, FOLDED);
		assertTrue(disassemble(workspace, FOLDED).contains("iadd"));

		// A dry run is the default. It names what would change and leaves everything alone.
		String dryRun = call("apply_transformers", "transformers", List.of("peephole.data.constfold"));
		assertTrue(dryRun.startsWith("Dry run, nothing was changed yet. Of the 3 classes, 1 would be modified."), dryRun);
		assertTrue(dryRun.contains("peephole.data.constfold 1"), dryRun);
		assertTrue(dryRun.contains("Showing classes that would be modified 1-1 of 1:\nsample/fold/Folded"), dryRun);
		assertTrue(dryRun.contains("dry_run=false"), dryRun);
		assertSame(before, classInfo(workspace, FOLDED), "A dry run must not change the class");

		// Applying folds the addition into a constant, through the bundle so that it can be undone.
		String applied = call("apply_transformers", "transformers", List.of("peephole.data.constfold"), "dry_run", false);
		assertTrue(applied.startsWith("Applied. Of the 3 classes, 1 was modified."), applied);
		assertTrue(applied.contains("Showing modified classes 1-1 of 1:\nsample/fold/Folded"), applied);
		String folded = disassemble(workspace, FOLDED);
		assertTrue(folded.contains("iconst_3") && !folded.contains("iadd"), folded);
		assertTrue(workspace.getPrimaryResource().getJvmClassBundle().hasHistory(FOLDED));
		assertFalse(workspace.getPrimaryResource().getJvmClassBundle().hasHistory("sample/Hello"));
		assertTrue(call("undo_change", "class", FOLDED).contains("Reverted 1 change"));
		assertTrue(disassemble(workspace, FOLDED).contains("iadd"));

		// With nothing left to fold the tool says so.
		call("apply_transformers", "transformers", List.of("constfold"), "dry_run", false);
		String nothing = call("apply_transformers", "transformers", List.of("OpaqueConstantFoldingTransformer"), "dry_run", false);
		assertTrue(nothing.contains("found nothing to change in the 3 classes"), nothing);
	}

	@Test
	void runsAreLimitedToPackagesAndClasses() {
		Workspace workspace = openSample("hello.jar");
		call("assemble_class", "source", FOLDED_SOURCE);
		call("assemble_class", "source", FOLDED_SOURCE.replace("sample/fold/Folded", "sample/other/Folded"));

		// Outside of the scope nothing is found.
		String elsewhere = call("apply_transformers", "transformers", List.of("peephole.data.constfold"), "package", "sample.util");
		assertTrue(elsewhere.contains("found nothing to change in the 1 class in scope"), elsewhere);

		String byPackage = call("apply_transformers", "transformers", List.of("peephole.data.constfold"), "package", "sample/fold/", "dry_run", false);
		assertTrue(byPackage.contains("Of the 1 class in scope, 1 was modified"), byPackage);
		assertFalse(disassemble(workspace, FOLDED).contains("iadd"));
		assertTrue(disassemble(workspace, "sample/other/Folded").contains("iadd"), "Classes out of scope stay as they are");

		String byClass = call("apply_transformers", "transformers", List.of("peephole.data.constfold", "cleanup.deadcode"),
				"classes", List.of("sample.other.Folded"), "dry_run", false, "max_passes", 2);
		assertTrue(byClass.contains("Of the 1 class in scope, 1 was modified"), byClass);
		assertFalse(disassemble(workspace, "sample/other/Folded").contains("iadd"));

		assertTrue(callExpectingError("apply_transformers", "transformers", List.of("cleanup.deadcode"), "package", "com/nothing").contains("No JVM class"));
		assertTrue(callExpectingError("apply_transformers", "transformers", List.of("cleanup.deadcode"), "classes", List.of("sample/Nope")).contains("No class named"));
		assertTrue(callExpectingError("apply_transformers", "transformers", List.of("cleanup.deadcode"), "classes", List.of("java/lang/String")).contains("runtime library"));
	}

	@Test
	void transformersThatRenameReportAndApplyTheirRenames() {
		// A class and a field named after Java keywords cannot be written in Java source.
		Workspace workspace = TestClassUtils.fromBundle(TestClassUtils.fromClasses(
				TestClassUtils.createClass("demo/int", node -> node.visitField(0, "class", "I", null, null)),
				TestClassUtils.createEmptyClass("demo/Fine")));
		workspaceManager.setCurrentIgnoringConditions(workspace);

		String dryRun = call("apply_transformers", "transformers", List.of("cleanup.illegalname"));
		assertTrue(dryRun.contains("2 renames would be applied across the program: 1 class, 1 field, 0 methods."), dryRun);
		assertTrue(dryRun.contains("Class renames: demo/int -> "), dryRun);
		assertNotNull(workspace.findClass("demo/int"));

		String applied = call("apply_transformers", "transformers", List.of("cleanup.illegalname"), "dry_run", false);
		assertTrue(applied.contains("2 renames were applied across the program"), applied);
		assertNull(workspace.findClass("demo/int"));
		assertNotNull(workspace.findClass("demo/Fine"));
		assertEquals(2, workspace.getPrimaryResource().getJvmClassBundle().size());
	}

	@Test
	void renamesThatClashAreNotApplied() {
		// A copy of Checks that kept the SourceFile attribute: restoring names from it asks for a name that is taken.
		Workspace workspace = openSample("hello.jar");
		call("assemble_class", "source", disassemble(workspace, "sample/util/Checks").replace("sample/util/Checks", "sample/util/a"));

		String dryRun = call("apply_transformers", "transformers", List.of("restoration.sourcename"));
		assertTrue(dryRun.contains("Class renames: sample/util/a -> sample/util/Checks"), dryRun);
		assertTrue(dryRun.contains("cannot be applied, because they clash") && dryRun.contains("A class named 'sample/util/Checks' already exists"), dryRun);
		assertFalse(dryRun.contains("dry_run=false"), dryRun);

		ClassInfo checks = classInfo(workspace, "sample/util/Checks");
		String error = callExpectingError("apply_transformers", "transformers", List.of("restoration.sourcename"), "dry_run", false);
		assertTrue(error.contains("Nothing was changed") && error.contains("A class named 'sample/util/Checks' already exists"), error);
		assertSame(checks, classInfo(workspace, "sample/util/Checks"));
		assertNotNull(workspace.findClass("sample/util/a"));
	}

	@Test
	void unusableRequestsAreRejected() {
		openSample("hello.jar");
		String error = callExpectingError("apply_transformers", "transformers", List.of("cleanup.deadcod"));
		assertTrue(error.contains("no transformer named 'cleanup.deadcod'") && error.contains("list_transformers"), error);
		error = callExpectingError("apply_transformers", "transformers", List.of("deadcode", "cleanup.deadcode"));
		assertTrue(error.contains("listed more than once"), error);
		assertTrue(callExpectingError("apply_transformers", "transformers", List.of()).contains("at least one"));

		// Cancelling stops a run before it changes anything.
		toolbox.context().setCancelled(true);
		try {
			assertTrue(callExpectingError("apply_transformers", "transformers", List.of("cleanup.frames"), "dry_run", false).contains("Cancelled"));
		} finally {
			toolbox.context().setCancelled(false);
		}
		assertTrue(workspaceManager.getCurrent().getPrimaryResource().getJvmClassBundle().getDirtyKeys().isEmpty());

		// Transformers are for JVM bytecode.
		openSample("sample.apk");
		error = callExpectingError("apply_transformers", "transformers", List.of("cleanup.deadcode"));
		assertTrue(error.contains("JVM bytecode only"), error);
		workspaceManager.setCurrentIgnoringConditions(null);
		assertTrue(callExpectingError("apply_transformers", "transformers", List.of("cleanup.deadcode")).contains("No workspace is open"));
	}

	@Nonnull
	private static ClassInfo classInfo(@Nonnull Workspace workspace, @Nonnull String name) {
		ClassPathNode path = workspace.findClass(name);
		assertNotNull(path, "No class " + name);
		return path.getValue();
	}

	@Nonnull
	private static String disassemble(@Nonnull Workspace workspace, @Nonnull String className) {
		ClassPathNode path = workspace.findClass(className);
		assertNotNull(path, "No class " + className);
		Outcome<String> outcome = recaf.get(AssemblerPipelineManager.class).getPipeline(path).disassemble(path);
		assertTrue(outcome.isSuccess(), "Disassembly failed: " + outcome.diagnostics());
		return outcome.requireValue();
	}
}
