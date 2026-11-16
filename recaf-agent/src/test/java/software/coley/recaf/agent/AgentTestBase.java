package software.coley.recaf.agent;

import com.google.gson.JsonObject;
import jakarta.annotation.Nonnull;
import org.junit.jupiter.api.BeforeEach;
import software.coley.recaf.agent.tool.AgentToolbox;
import software.coley.recaf.agent.tool.ToolResult;
import software.coley.recaf.services.workspace.io.ResourceImporter;
import software.coley.recaf.test.TestBase;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Base for tests of the agent tooling.
 * <p>
 * Provides a toolbox over the test Recaf instance and helpers to open the sample inputs in
 * {@code src/test/resources/samples}. Their sources are in {@code src/test/samples}:
 * <ul>
 *     <li>{@code hello.jar}: classes {@code sample/Hello} and {@code sample/util/Checks}, file {@code config/app.properties}</li>
 *     <li>{@code sample.apk}: Android classes {@code sample/app/MainActivity} and {@code sample/app/Keys} in
 *     {@code classes.dex}, plus a binary {@code AndroidManifest.xml}</li>
 * </ul>
 */
public abstract class AgentTestBase extends TestBase {
	private static final Path WORK_DIR = createWorkDir();
	protected AgentToolbox toolbox;

	@BeforeEach
	void createToolbox() {
		workspaceManager.setCurrentIgnoringConditions(null);
		toolbox = AgentToolbox.create(recaf, WORK_DIR.resolve("exports"), List.of(WORK_DIR));
	}

	/**
	 * @param name
	 * 		Name of a sample in {@code src/test/resources/samples}.
	 *
	 * @return Path to a copy of the sample inside the directory the toolbox may open files from.
	 */
	@Nonnull
	protected static Path sample(@Nonnull String name) {
		Path target = WORK_DIR.resolve(name);
		try (InputStream in = AgentTestBase.class.getResourceAsStream("/samples/" + name)) {
			assertNotNull(in, "Missing sample: " + name);
			Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return target;
	}

	/**
	 * Opens a sample as the current workspace.
	 *
	 * @param name
	 * 		Name of a sample in {@code src/test/resources/samples}.
	 *
	 * @return The opened workspace.
	 */
	@Nonnull
	protected static Workspace openSample(@Nonnull String name) {
		try {
			Workspace workspace = new BasicWorkspace(recaf.get(ResourceImporter.class).importResource(sample(name)));
			workspaceManager.setCurrentIgnoringConditions(workspace);
			return workspace;
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * Calls a tool that is expected to succeed.
	 *
	 * @param name
	 * 		Tool name.
	 * @param keyValues
	 * 		Alternating argument names and values. Values may be strings, numbers, booleans or lists of strings.
	 *
	 * @return The result text.
	 */
	@Nonnull
	protected String call(@Nonnull String name, @Nonnull Object... keyValues) {
		ToolResult result = toolbox.call(name, args(keyValues));
		assertFalse(result.error(), "Tool '" + name + "' failed: " + result.text());
		return result.text();
	}

	/**
	 * Calls a tool that is expected to fail.
	 *
	 * @param name
	 * 		Tool name.
	 * @param keyValues
	 * 		Alternating argument names and values.
	 *
	 * @return The error text.
	 */
	@Nonnull
	protected String callExpectingError(@Nonnull String name, @Nonnull Object... keyValues) {
		ToolResult result = toolbox.call(name, args(keyValues));
		assertTrue(result.error(), "Tool '" + name + "' was expected to fail but returned: " + result.text());
		return result.text();
	}

	/**
	 * @param keyValues
	 * 		Alternating argument names and values.
	 *
	 * @return Arguments as JSON.
	 */
	@Nonnull
	protected static JsonObject args(@Nonnull Object... keyValues) {
		JsonObject json = new JsonObject();
		for (int i = 0; i < keyValues.length; i += 2) {
			String key = (String) keyValues[i];
			Object value = keyValues[i + 1];
			if (value instanceof Number number) json.addProperty(key, number);
			else if (value instanceof Boolean bool) json.addProperty(key, bool);
			else if (value instanceof List<?> list) {
				com.google.gson.JsonArray array = new com.google.gson.JsonArray();
				list.forEach(item -> array.add(String.valueOf(item)));
				json.add(key, array);
			} else json.addProperty(key, String.valueOf(value));
		}
		return json;
	}

	@Nonnull
	private static Path createWorkDir() {
		try {
			return Files.createTempDirectory("recaf-agent-test");
		} catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}
}
