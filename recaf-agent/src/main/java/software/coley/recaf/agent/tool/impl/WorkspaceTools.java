package software.coley.recaf.agent.tool.impl;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import software.coley.recaf.agent.tool.AgentTool;
import software.coley.recaf.agent.tool.ToolContext;
import software.coley.recaf.agent.tool.ToolException;
import software.coley.recaf.agent.tool.ToolProvider;
import software.coley.recaf.agent.tool.ToolSchema;
import software.coley.recaf.agent.tool.support.Lookups;
import software.coley.recaf.agent.tool.support.Text;
import software.coley.recaf.agent.tool.support.Workspaces;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.FileInfo;
import software.coley.recaf.info.properties.builtin.InputFilePathProperty;
import software.coley.recaf.path.FilePathNode;
import software.coley.recaf.services.workspace.WorkspaceManager;
import software.coley.recaf.services.workspace.io.ResourceImporter;
import software.coley.recaf.util.AccessFlag;
import software.coley.recaf.workspace.model.BasicWorkspace;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResource;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * Tools for opening a workspace and getting oriented in it.
 *
 * @author Recaf agent tooling
 */
public final class WorkspaceTools implements ToolProvider {
	private static final int TOP_PACKAGES = 25;
	private final ToolContext context;

	/**
	 * @param context
	 * 		Context the tools operate in.
	 */
	public WorkspaceTools(@Nonnull ToolContext context) {
		this.context = context;
	}

	@Nonnull
	@Override
	public List<AgentTool> tools() {
		return List.of(workspaceInfo(), openWorkspace(), listClasses());
	}

	@Nonnull
	private AgentTool workspaceInfo() {
		return AgentTool.builder("workspace_info")
				.description("Summarize the open workspace: the input file, how many classes and files it contains, " +
						"its largest packages, its declared entry point and how much has been modified. " +
						"Start here when you know nothing about the program yet.")
				.handler(args -> {
					Workspace workspace = context.workspace();
					WorkspaceResource primary = workspace.getPrimaryResource();
					StringBuilder sb = new StringBuilder();

					String input = describeInput(primary);
					sb.append("Input: ").append(input).append('\n');

					long jvmClasses = Workspaces.jvmClasses(workspace).count();
					long androidClasses = Workspaces.androidClasses(workspace).count();
					long files = Workspaces.files(workspace).count();
					sb.append("JVM classes: ").append(jvmClasses).append('\n');
					if (androidClasses > 0) {
						sb.append("Android (DEX) classes: ").append(androidClasses).append(" in ");
						sb.append(String.join(", ", collectDexNames(primary))).append('\n');
					}
					sb.append("Files: ").append(files).append('\n');

					Map<String, WorkspaceFileResource> embedded = primary.getEmbeddedResources();
					if (!embedded.isEmpty())
						sb.append("Embedded archives: ").append(Text.shorten(String.join(", ", embedded.keySet()), 600)).append('\n');
					if (!workspace.getSupportingResources().isEmpty())
						sb.append("Supporting libraries: ").append(workspace.getSupportingResources().size()).append('\n');

					String mainClass = findMainClass(workspace);
					if (mainClass != null)
						sb.append("Manifest Main-Class: ").append(mainClass).append('\n');
					if (workspace.findFile("AndroidManifest.xml") != null)
						sb.append("This is an Android application. Use get_android_manifest for its components and permissions.\n");

					int modified = countModified(primary);
					sb.append("Modified classes and files: ").append(modified).append('\n');

					// Package histogram
					Map<String, Integer> packageCounts = new TreeMap<>();
					Workspaces.classes(workspace).forEach(node -> {
						String packageName = node.getValue().getPackageName();
						packageCounts.merge(packageName == null ? "(default package)" : packageName, 1, Integer::sum);
					});
					if (!packageCounts.isEmpty()) {
						List<Map.Entry<String, Integer>> sorted = new ArrayList<>(packageCounts.entrySet());
						sorted.sort(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry.comparingByKey()));
						sb.append('\n').append(packageCounts.size()).append(" packages. Largest:\n");
						for (int i = 0; i < Math.min(TOP_PACKAGES, sorted.size()); i++)
							sb.append("  ").append(sorted.get(i).getKey()).append("  (").append(sorted.get(i).getValue()).append(" classes)\n");
						if (sorted.size() > TOP_PACKAGES)
							sb.append("  [").append(sorted.size() - TOP_PACKAGES).append(" more packages. Use list_packages to see them all.]\n");
					}
					return sb.toString().stripTrailing();
				})
				.build();
	}

	@Nonnull
	private AgentTool openWorkspace() {
		return AgentTool.builder("open_workspace")
				.description("Open a file as the workspace to analyze, replacing the currently open one. " +
						"Supports JAR, WAR, ZIP, APK, DEX, JMOD and single class files. " +
						"Changes made to the current workspace that were not exported are lost.")
				.schema(ToolSchema.object()
						.string("path", "Path of the file to open.")
						.optBool("discard_changes", "Pass true to confirm replacing a workspace that has unexported modifications."))
				.mutating()
				.handler(args -> {
					Path path = resolveInput(args.string("path"));
					if (!Files.isRegularFile(path) && !Files.isDirectory(path))
						throw new ToolException("There is no file at '" + path + "'.");

					Workspace current = context.workspaceOrNull();
					if (current != null && countModified(current.getPrimaryResource()) > 0 && !args.optBool("discard_changes", false))
						throw new ToolException("The open workspace has modifications that were not exported. " +
								"Export it first (export_workspace), or pass discard_changes=true to drop them.");

					WorkspaceResource resource = context.service(ResourceImporter.class).importResource(path);
					Workspace workspace = new BasicWorkspace(resource);
					context.service(WorkspaceManager.class).setCurrentIgnoringConditions(workspace);
					return "Opened " + path.getFileName() + ": " +
							Workspaces.jvmClasses(workspace).count() + " JVM classes, " +
							Workspaces.androidClasses(workspace).count() + " Android classes, " +
							Workspaces.files(workspace).count() + " files. Use workspace_info for an overview.";
				})
				.build();
	}

	@Nonnull
	private AgentTool listClasses() {
		return AgentTool.builder("list_classes")
				.description("List classes in the workspace, optionally limited to a package and filtered by a regular expression. " +
						"Each line shows the class name followed by its kind, its number of fields and methods, " +
						"and whether it was modified in this session.")
				.schema(ToolSchema.object()
						.optString("package", "Only list classes in this package, for example 'com/example/net'. " +
								"Sub-packages are included unless recursive is false.")
						.optBool("recursive", "Whether classes in sub-packages of 'package' are included. Default true.")
						.optString("pattern", "Only list classes whose full name contains a match of this regular expression, " +
								"for example 'Crypt|Cipher' or '^a/[a-z]$'. Case-insensitive.")
						.paging(100))
				.handler(args -> {
					Workspace workspace = context.workspace();
					String packageName = args.optString("package", null);
					String packagePrefix = packageName == null || packageName.isBlank() ? null :
							Lookups.internalName(packageName).replaceAll("/+$", "") + "/";
					boolean recursive = args.optBool("recursive", true);
					Pattern pattern = compilePattern(args.optString("pattern", null));

					List<ClassInfo> classes = Workspaces.classes(workspace)
							.map(node -> node.getValue())
							.filter(info -> {
								String name = info.getName();
								if (packagePrefix != null) {
									if (!name.startsWith(packagePrefix))
										return false;
									if (!recursive && name.indexOf('/', packagePrefix.length()) >= 0)
										return false;
								}
								return pattern == null || pattern.matcher(name).find();
							})
							.sorted(Comparator.comparing(ClassInfo::getName))
							.collect(Collectors.toList());

					Set<String> modified = modifiedClassNames(workspace.getPrimaryResource());
					return Text.page(classes, args.offset(), args.limit(100, 500), "classes", info -> {
						StringBuilder line = new StringBuilder(info.getName());
						line.append("  [").append(kind(info));
						line.append(", ").append(info.getFields().size()).append(" fields");
						line.append(", ").append(info.getMethods().size()).append(" methods");
						if (info.isAndroidClass())
							line.append(", dex");
						if (modified.contains(info.getName()))
							line.append(", modified");
						return line.append(']').toString();
					});
				})
				.build();
	}

	/**
	 * @param text
	 * 		Regular expression, or {@code null}.
	 *
	 * @return Compiled case-insensitive pattern, or {@code null} when no expression was given.
	 *
	 * @throws ToolException
	 * 		When the expression is invalid.
	 */
	@Nullable
	static Pattern compilePattern(@Nullable String text) {
		if (text == null || text.isEmpty())
			return null;
		try {
			return Pattern.compile(text, Pattern.CASE_INSENSITIVE);
		} catch (PatternSyntaxException ex) {
			throw new ToolException("Invalid regular expression '" + text + "': " + ex.getDescription());
		}
	}

	@Nonnull
	static String kind(@Nonnull ClassInfo info) {
		int access = info.getAccess();
		if (AccessFlag.isAnnotation(access)) return "annotation";
		if (AccessFlag.isInterface(access)) return "interface";
		if (AccessFlag.isEnum(access)) return "enum";
		if (info.hasRecordModifier()) return "record";
		if (AccessFlag.isAbstract(access)) return "abstract class";
		return "class";
	}

	/**
	 * @param resource
	 * 		Resource to check.
	 *
	 * @return Number of classes and files that were changed or removed since the resource was opened.
	 */
	static int countModified(@Nonnull WorkspaceResource resource) {
		return resource.bundleStreamRecursive()
				.mapToInt(bundle -> bundle.getDirtyKeys().size() + bundle.getRemovedKeys().size())
				.sum();
	}

	@Nonnull
	static Set<String> modifiedClassNames(@Nonnull WorkspaceResource resource) {
		Set<String> names = new HashSet<>();
		resource.classBundleStreamRecursive().forEach(bundle -> names.addAll(bundle.getDirtyKeys()));
		return names;
	}

	@Nonnull
	private static List<String> collectDexNames(@Nonnull WorkspaceResource resource) {
		List<String> names = new ArrayList<>(resource.getAndroidClassBundles().keySet());
		for (WorkspaceFileResource embedded : resource.getEmbeddedResources().values())
			names.addAll(embedded.getAndroidClassBundles().keySet());
		return names;
	}

	@Nonnull
	private static String describeInput(@Nonnull WorkspaceResource resource) {
		if (resource instanceof WorkspaceFileResource fileResource) {
			FileInfo fileInfo = fileResource.getFileInfo();
			Path path = InputFilePathProperty.get(fileInfo);
			String name = path != null ? path.getFileName().toString() : fileInfo.getName();
			// The file info of an imported archive does not retain the archive's bytes, ask the file system.
			long size = fileInfo.getRawContent().length;
			try {
				if (path != null && Files.isRegularFile(path))
					size = Files.size(path);
			} catch (java.io.IOException ignored) {
				// Keep the size known from the file info.
			}
			return size > 0 ? name + " (" + Text.size(size) + ")" : name;
		}
		return resource.getClass().getSimpleName();
	}

	@Nullable
	private static String findMainClass(@Nonnull Workspace workspace) {
		FilePathNode manifestPath = workspace.findFile("META-INF/MANIFEST.MF");
		if (manifestPath == null)
			return null;
		String manifest = new String(manifestPath.getValue().getRawContent(), StandardCharsets.UTF_8);
		for (String line : manifest.split("\r?\n")) {
			if (line.toLowerCase(Locale.ROOT).startsWith("main-class:"))
				return line.substring("main-class:".length()).trim().replace('.', '/');
		}
		return null;
	}

	/**
	 * @param text
	 * 		Path as passed by the model.
	 *
	 * @return The path, resolved against the allowed input directories.
	 *
	 * @throws ToolException
	 * 		When the path lies outside of the allowed input directories.
	 */
	@Nonnull
	private Path resolveInput(@Nonnull String text) {
		Path path = Paths.get(text.trim());
		List<Path> roots = context.inputRoots();
		if (roots.isEmpty())
			return path.toAbsolutePath().normalize();

		// Relative paths are looked up in each allowed directory.
		if (!path.isAbsolute()) {
			for (Path root : roots) {
				Path candidate = root.resolve(path).toAbsolutePath().normalize();
				if (candidate.startsWith(root.toAbsolutePath().normalize()) && Files.exists(candidate))
					return candidate;
			}
		}
		Path absolute = path.toAbsolutePath().normalize();
		for (Path root : roots)
			if (absolute.startsWith(root.toAbsolutePath().normalize()))
				return absolute;
		throw new ToolException("'" + text + "' is outside of the directories files can be opened from: " +
				roots.stream().map(Path::toString).collect(Collectors.joining(", ")));
	}
}
