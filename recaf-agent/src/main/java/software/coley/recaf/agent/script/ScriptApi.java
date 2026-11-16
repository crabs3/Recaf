package software.coley.recaf.agent.script;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import software.coley.recaf.agent.tool.ToolContext;
import software.coley.recaf.agent.tool.ToolException;
import software.coley.recaf.agent.tool.ToolResult;
import software.coley.recaf.agent.tool.support.Lookups;
import software.coley.recaf.agent.tool.support.Workspaces;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.FileInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.FileInfoBuilder;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.path.FilePathNode;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.JvmClassBundle;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The {@code recaf} object available to scripts: a small, stable surface over the workspace so that scripts do not
 * need to navigate Recaf's object model for the common cases. Everything else remains reachable through
 * {@link #workspace()} and {@link #service(String)}.
 * <p>
 * Public methods of this class are callable from scripts. Keep their parameter types simple, the scripting bridge
 * picks overloads by argument count and convertibility.
 *
 * @author Recaf agent tooling
 */
public final class ScriptApi {
	private final ToolContext context;
	private final StringBuilder output;

	ScriptApi(@Nonnull ToolContext context, @Nonnull StringBuilder output) {
		this.context = context;
		this.output = output;
	}

	/**
	 * @return The open workspace.
	 */
	@Nonnull
	public Workspace workspace() {
		return context.workspace();
	}

	/**
	 * @param className
	 * 		Fully qualified name of a Recaf service class or interface,
	 * 		such as {@code software.coley.recaf.services.search.SearchService}.
	 *
	 * @return The service instance.
	 */
	@Nonnull
	public Object service(@Nonnull String className) {
		if (!className.startsWith("software.coley.recaf.") || className.startsWith("software.coley.recaf.agent.")
				|| className.startsWith("software.coley.recaf.android."))
			throw new ToolException("Not a Recaf service: " + className);
		try {
			return context.service(Class.forName(className));
		} catch (ClassNotFoundException ex) {
			throw new ToolException("There is no Recaf service class named " + className);
		}
	}

	/**
	 * @return Internal names of all classes of the program, sorted.
	 */
	@Nonnull
	public List<String> classNames() {
		return Workspaces.classes(workspace())
				.map(node -> node.getValue().getName())
				.sorted()
				.collect(Collectors.toList());
	}

	/**
	 * @param regex
	 * 		Regular expression to find in class names.
	 *
	 * @return Internal names of the matching classes of the program, sorted.
	 */
	@Nonnull
	public List<String> classNames(@Nonnull String regex) {
		Pattern pattern = Pattern.compile(regex);
		return Workspaces.classes(workspace())
				.map(node -> node.getValue().getName())
				.filter(name -> pattern.matcher(name).find())
				.sorted()
				.collect(Collectors.toList());
	}

	/**
	 * @param className
	 * 		Class name in any common form.
	 *
	 * @return The class as a JVM class. Android classes are translated from DEX.
	 */
	@Nonnull
	public JvmClassInfo jvmClass(@Nonnull String className) {
		return Lookups.jvmView(Lookups.requireClass(workspace(), className).getValue());
	}

	/**
	 * @param className
	 * 		Class name in any common form.
	 *
	 * @return The class file bytes of the class. Android classes are translated from DEX.
	 */
	@Nonnull
	public byte[] classBytes(@Nonnull String className) {
		return jvmClass(className).getBytecode();
	}

	/**
	 * Adds a JVM class to the workspace, or replaces the class of the same name.
	 *
	 * @param classFile
	 * 		Class file bytes, for instance from an ASM {@code ClassWriter}.
	 *
	 * @return Internal name of the class that was written.
	 */
	@Nonnull
	public String putClass(@Nonnull byte[] classFile) {
		Workspace workspace = workspace();
		JvmClassInfo info = new JvmClassInfoBuilder(classFile).build();
		ClassPathNode existing = workspace.findClass(false, info.getName());
		if (existing != null) {
			ClassInfo current = existing.getValue();
			if (!current.isJvmClass())
				throw new ToolException("'" + info.getName() + "' is an Android (DEX) class, it cannot be replaced with a JVM class file.");
			JvmClassBundle bundle = existing.getValueOfType(JvmClassBundle.class);
			if (bundle != null) {
				bundle.put(info);
				return info.getName();
			}
		}
		workspace.getPrimaryResource().getJvmClassBundle().put(info);
		return info.getName();
	}

	/**
	 * @param path
	 * 		Path of a file in the workspace.
	 *
	 * @return Content of the file.
	 */
	@Nonnull
	public byte[] fileBytes(@Nonnull String path) {
		return Lookups.requireFile(workspace(), path).getValue().getRawContent();
	}

	/**
	 * @param path
	 * 		Path of a file in the workspace.
	 *
	 * @return Content of the file decoded as UTF-8.
	 */
	@Nonnull
	public String fileText(@Nonnull String path) {
		return new String(fileBytes(path), StandardCharsets.UTF_8);
	}

	/**
	 * @return Paths of all files of the program, sorted.
	 */
	@Nonnull
	public List<String> fileNames() {
		return Workspaces.files(workspace())
				.map(node -> node.getValue().getName())
				.sorted()
				.collect(Collectors.toList());
	}

	/**
	 * Adds a file to the workspace, or replaces the file of the same path.
	 *
	 * @param path
	 * 		Path of the file in the workspace.
	 * @param content
	 * 		New content.
	 */
	public void putFile(@Nonnull String path, @Nonnull byte[] content) {
		Workspace workspace = workspace();
		String name = path.startsWith("/") ? path.substring(1) : path;
		FilePathNode existing = workspace.findFile(name);
		FileInfo info = existing != null ?
				existing.getValue().toFileBuilder().withRawContent(content).build() :
				new FileInfoBuilder<>().withName(name).withRawContent(content).build();
		workspace.getPrimaryResource().getFileBundle().put(info);
	}

	/**
	 * Adds a text file to the workspace, or replaces the file of the same path.
	 *
	 * @param path
	 * 		Path of the file in the workspace.
	 * @param text
	 * 		New content, written as UTF-8.
	 */
	public void putFile(@Nonnull String path, @Nonnull String text) {
		putFile(path, text.getBytes(StandardCharsets.UTF_8));
	}

	/**
	 * Calls another tool of the toolbox.
	 *
	 * @param name
	 * 		Tool name.
	 * @param argumentsJson
	 * 		Arguments as a JSON object string, or {@code null} for none.
	 *
	 * @return The tool's result text.
	 *
	 * @throws ToolException
	 * 		When the tool reports an error.
	 */
	@Nonnull
	public String callTool(@Nonnull String name, @Nullable String argumentsJson) {
		JsonObject arguments = argumentsJson == null || argumentsJson.isBlank() ? new JsonObject() :
				JsonParser.parseString(argumentsJson).getAsJsonObject();
		if (name.equals("run_script"))
			throw new ToolException("Scripts cannot start other scripts.");
		ToolResult result = context.toolbox().call(name, arguments);
		if (result.error())
			throw new ToolException(name + ": " + result.text());
		return result.text();
	}

	/**
	 * @param text
	 * 		Text to append to the script's output, followed by a line break.
	 */
	public void print(@Nullable Object text) {
		if (output.length() < ScriptRunner.MAX_OUTPUT_CHARS)
			output.append(text).append('\n');
	}

	/**
	 * Call periodically in long loops over Java objects, so that a script the user cancelled stops promptly.
	 */
	public void checkCancelled() {
		context.checkCancelled();
	}
}
