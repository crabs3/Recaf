package software.coley.recaf.agent.tool.impl;

import com.google.devrel.gmscore.tools.apk.arsc.BinaryResourceIdentifier;
import com.google.devrel.gmscore.tools.apk.arsc.BinaryResourceValue;
import com.google.devrel.gmscore.tools.apk.arsc.Chunk;
import com.google.devrel.gmscore.tools.apk.arsc.StringPoolChunk;
import com.google.devrel.gmscore.tools.apk.arsc.XmlAttribute;
import com.google.devrel.gmscore.tools.apk.arsc.XmlChunk;
import com.google.devrel.gmscore.tools.apk.arsc.XmlEndElementChunk;
import com.google.devrel.gmscore.tools.apk.arsc.XmlResourceMapChunk;
import com.google.devrel.gmscore.tools.apk.arsc.XmlStartElementChunk;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import software.coley.android.xml.AndroidResourceProvider;
import software.coley.android.xml.XmlDecoder;
import software.coley.recaf.agent.tool.AgentTool;
import software.coley.recaf.agent.tool.ToolContext;
import software.coley.recaf.agent.tool.ToolException;
import software.coley.recaf.agent.tool.ToolProvider;
import software.coley.recaf.agent.tool.ToolSchema;
import software.coley.recaf.agent.tool.support.Lookups;
import software.coley.recaf.agent.tool.support.Text;
import software.coley.recaf.agent.tool.support.Workspaces;
import software.coley.recaf.info.ApkFileInfo;
import software.coley.recaf.info.ArscFileInfo;
import software.coley.recaf.info.BinaryXmlFileInfo;
import software.coley.recaf.info.DexFileInfo;
import software.coley.recaf.info.FileInfo;
import software.coley.recaf.info.JModFileInfo;
import software.coley.recaf.info.JarFileInfo;
import software.coley.recaf.info.ModulesFileInfo;
import software.coley.recaf.info.WarFileInfo;
import software.coley.recaf.info.properties.builtin.IllegalClassSuspectProperty;
import software.coley.recaf.path.FilePathNode;
import software.coley.recaf.util.android.AndroidRes;
import software.coley.recaf.util.android.AndroidXmlUtil;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.Bundle;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResource;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Tools for finding one's way around a workspace: its packages, its files, the Android manifest and what was
 * changed in this session.
 *
 * @author Recaf agent tooling
 */
public final class NavigationTools implements ToolProvider {
	/** Separates the path of a nested archive from the path of a file inside of it, as in {@code lib/a.jar!/b.txt}. */
	private static final String NESTED_SEPARATOR = "!/";
	private static final String DEFAULT_PACKAGE = "(default package)";
	private static final String MANIFEST_NAME = AndroidXmlUtil.MANIFEST_NAME;
	private static final Set<String> COMPONENT_TAGS = Set.of("activity", "activity-alias", "service", "receiver", "provider");
	private static final int DEFAULT_TEXT_LINES = 400;
	private static final int MAX_TEXT_LINES = 2000;
	/** Lines of text files longer than this are wrapped, so that line paging can reach every part of them. */
	private static final int MAX_LINE_LENGTH = 1000;
	private static final int DEFAULT_HEX_BYTES = 512;
	private static final int MAX_HEX_BYTES = 8192;
	private static final int MANIFEST_XML_LINES = 400;
	private static final char[] HEX = "0123456789abcdef".toCharArray();
	private final ToolContext context;

	/**
	 * @param context
	 * 		Context the tools operate in.
	 */
	public NavigationTools(@Nonnull ToolContext context) {
		this.context = context;
	}

	@Nonnull
	@Override
	public List<AgentTool> tools() {
		return List.of(listPackages(), listFiles(), readFile(), getAndroidManifest(), listModified());
	}

	@Nonnull
	private AgentTool listPackages() {
		return AgentTool.builder("list_packages")
				.description("List the packages of the workspace with the number of classes in each, sorted by name. " +
						"Use 'depth' to see the overall shape of a large program in a few lines: packages deeper than that " +
						"are folded into their ancestor, which then shows how many classes and packages it stands for. " +
						"Then pass one of those as 'package' to look closer, and list_classes to see the classes of a package.")
				.schema(ToolSchema.object()
						.optString("package", "Only list this package and the packages below it, for example 'com/example' or 'com.example'.")
						.optInteger("depth", "Fold packages more than this many levels deep into their ancestor at that level, " +
								"for example 2 to get 'com/example', 'com/google', 'org/apache'. " +
								"Levels are counted below 'package' when it is given. Default: no folding.")
						.paging(100))
				.handler(args -> {
					Workspace workspace = context.workspace();
					String packageArg = args.optString("package", null);
					String base = packageArg == null || packageArg.isBlank() ? null : trimSlashes(Lookups.internalName(packageArg));
					int depth = args.optInt("depth", 0);
					if (args.has("depth") && depth < 1)
						throw new ToolException("'depth' must be 1 or more. Leave it out to list every package.");

					// Classes per package, where the default package is the empty name.
					Map<String, Integer> classCounts = new TreeMap<>();
					Workspaces.classes(workspace).forEach(node -> {
						String packageName = node.getValue().getPackageName();
						classCounts.merge(packageName == null ? "" : packageName, 1, Integer::sum);
					});

					int baseDepth = base == null || base.isEmpty() ? 0 : segments(base);
					Map<String, PackageGroup> groups = new TreeMap<>();
					classCounts.forEach((packageName, count) -> {
						if (base != null && !base.isEmpty() && !packageName.equals(base) && !packageName.startsWith(base + "/"))
							return;
						String groupName = depth > 0 ? ancestor(packageName, baseDepth + depth) : packageName;
						PackageGroup group = groups.computeIfAbsent(groupName, PackageGroup::new);
						group.classes += count;
						group.packages++;
						if (packageName.equals(groupName))
							group.holdsClasses = true;
					});
					if (groups.isEmpty() && base != null && !base.isEmpty())
						throw new ToolException("There is no package '" + base + "' in the workspace. " +
								"Call list_packages without 'package' (and with depth=1 or 2) to see which packages exist.");

					return Text.page(new ArrayList<>(groups.values()), args.offset(), args.limit(100, 500), "packages", group -> {
						String name = group.name.isEmpty() ? DEFAULT_PACKAGE : group.name;
						if (group.packages == 1 && group.holdsClasses)
							return name + "  (" + count(group.classes, "class", "classes") + ")";
						return name + "  (" + count(group.classes, "class", "classes") + " in " + count(group.packages, "package", "packages") + ")";
					});
				})
				.build();
	}

	@Nonnull
	private AgentTool listFiles() {
		return AgentTool.builder("list_files")
				.description("List the files of the workspace that are not classes: resources, configuration, native libraries, " +
						"Android resources and so on. Each line shows the path followed by the kind of content Recaf detected " +
						"(text, binary XML, ARSC resource table, image, audio, video, native library, binary, ...), the size, " +
						"and whether the file was modified in this session. Files inside a nested archive are shown as " +
						"'path/of/archive.jar!/path/of/file'. Pass a path exactly as listed to read_file to see the content. " +
						"DEX files and nested archives that Recaf loaded are not listed as files, their classes are part of list_classes.")
				.schema(ToolSchema.object()
						.optString("directory", "Only list files below this directory, for example 'res/xml' or 'META-INF'.")
						.optString("pattern", "Only list files whose path contains a match of this regular expression, " +
								"for example '\\.(json|properties)$' or 'config'. Case-insensitive.")
						.paging(100))
				.handler(args -> {
					Workspace workspace = context.workspace();
					String directoryArg = args.optString("directory", null);
					String directory = directoryArg == null || directoryArg.isBlank() ? null : trimSlashes(directoryArg.trim());
					Pattern pattern = WorkspaceTools.compilePattern(args.optString("pattern", null));

					Map<WorkspaceResource, String> prefixes = new IdentityHashMap<>();
					List<ListedFile> files = Workspaces.files(workspace)
							.map(node -> new ListedFile(displayPath(node, prefixes), node))
							.filter(file -> directory == null || directory.isEmpty() || file.path.startsWith(directory + "/"))
							.filter(file -> pattern == null || pattern.matcher(file.path).find())
							.sorted(Comparator.comparing(file -> file.path))
							.collect(Collectors.toList());

					return Text.page(files, args.offset(), args.limit(100, 500), "files", file -> {
						FileInfo info = file.node.getValue();
						StringBuilder line = new StringBuilder(file.path);
						line.append("  [").append(kind(info)).append(", ").append(Text.size(info.getRawContent().length));
						Bundle<?> bundle = file.node.getValueOfType(Bundle.class);
						if (bundle != null && bundle.hasHistory(info.getName()))
							line.append(", modified");
						return line.append(']').toString();
					});
				})
				.build();
	}

	@Nonnull
	private AgentTool readFile() {
		return AgentTool.builder("read_file")
				.description("Show the content of a file of the workspace. The result starts with a line stating the path, " +
						"the detected kind and the size, followed by the content in the form that suits the kind: " +
						"text files as text, Android binary XML (such as AndroidManifest.xml and layouts) decoded to readable XML, " +
						"an Android resource table (resources.arsc) as one 'type/name (id) = value' line per resource, " +
						"and any other binary file as a hex dump with an ASCII column. " +
						"Text is returned in pages of lines (start_line, max_lines), hex dumps in ranges of bytes (offset, length). " +
						"For classes use decompile_class or disassemble_class instead.")
				.schema(ToolSchema.object()
						.string("path", "Path of the file as shown by list_files, for example 'config/app.properties' " +
								"or 'lib/inner.jar!/META-INF/MANIFEST.MF' for a file inside a nested archive.")
						.optChoice("encoding", "How to show the content. 'auto' (default) picks by the detected kind. " +
										"'text' decodes the raw bytes as UTF-8 text even if the file was detected as binary. " +
										"'hex' shows the raw bytes of any file as a hex dump.",
								"auto", "text", "hex")
						.optInteger("start_line", "For text: first line to return, starting at 1. Default 1.")
						.optInteger("max_lines", "For text: maximum number of lines to return. Default " + DEFAULT_TEXT_LINES + ".")
						.optInteger("offset", "For hex dumps: offset of the first byte to show. Default 0.")
						.optInteger("length", "For hex dumps: number of bytes to show. Default " + DEFAULT_HEX_BYTES +
								", at most " + MAX_HEX_BYTES + "."))
				.handler(args -> {
					Workspace workspace = context.workspace();
					String name = args.string("path");
					FilePathNode path = resolveFile(workspace, name);
					FileInfo info = path.getValue();
					byte[] raw = info.getRawContent();
					String shownPath = displayPath(path, new IdentityHashMap<>());
					String header = shownPath + ": " + kind(info) + ", " + Text.size(raw.length);

					// The content as text, or null when the bytes are shown instead.
					String text = null;
					String encoding = args.optString("encoding", "auto").trim().toLowerCase();
					switch (encoding) {
						case "hex" -> {}
						case "text" -> {
							if (info.isTextFile()) {
								text = info.asTextFile().getText();
							} else {
								text = printable(new String(raw, StandardCharsets.UTF_8));
								header += ", bytes read as UTF-8 text on request (\uFFFD marks what is not text)";
							}
						}
						case "auto", "" -> {
							if (info.isTextFile()) {
								text = info.asTextFile().getText();
							} else if (info instanceof BinaryXmlFileInfo binaryXml) {
								text = decodeBinaryXml(workspace, binaryXml);
								header += ", decoded to XML";
							} else if (info instanceof ArscFileInfo arsc) {
								text = describeResourceTable(arsc);
								header += text == null ? ", could not be decoded as a resource table" : ", decoded to one line per resource";
							}
						}
						default -> throw new ToolException("Unknown encoding '" + encoding + "'. Use 'auto', 'text' or 'hex'.");
					}

					if (text == null) {
						if (args.has("start_line") || args.has("max_lines"))
							throw new ToolException("'" + shownPath + "' is shown as a hex dump, which is paged with 'offset' and 'length' " +
									"in bytes, not with start_line and max_lines." +
									(encoding.equals("hex") ? "" : " Pass encoding='text' to read it as text regardless of its kind (" + kind(info) + ")."));
						return header + " (" + raw.length + " bytes)\n" + hexDump(raw, args.offset(),
								Math.max(1, Math.min(MAX_HEX_BYTES, args.optInt("length", DEFAULT_HEX_BYTES))));
					}
					if (args.has("offset") || args.has("length"))
						throw new ToolException("'" + shownPath + "' is shown as text, which is paged with 'start_line' and 'max_lines', " +
								"not with offset and length. Pass encoding='hex' to address it by bytes.");
					if (text.isEmpty())
						return header + "\n[The file is empty.]";
					String wrapped = wrapLongLines(text);
					if (!wrapped.equals(text))
						header += ", lines longer than " + MAX_LINE_LENGTH + " characters are wrapped";
					int startLine = Math.max(1, args.optInt("start_line", 1));
					int maxLines = Math.max(1, Math.min(MAX_TEXT_LINES, args.optInt("max_lines", DEFAULT_TEXT_LINES)));
					return header + "\n" + Text.lines(wrapped, startLine, maxLines, false);
				})
				.build();
	}

	@Nonnull
	private AgentTool getAndroidManifest() {
		return AgentTool.builder("get_android_manifest")
				.description("Summarize the AndroidManifest.xml of an Android application: package, version, minimum and target SDK, " +
						"the Application class and security relevant application flags, requested and declared permissions, " +
						"and every activity, service, receiver and provider with its class name, whether it is exported, " +
						"the permission guarding it and its intent-filters (actions, categories, data). " +
						"Component classes are given as internal names ready for decompile_class, and are flagged when the class " +
						"is missing from the workspace, which hints at code that is loaded at runtime. " +
						"Start here for any APK: the exported components and the Application class are its entry points.")
				.schema(ToolSchema.object()
						.optBool("include_xml", "Pass true to also get the decoded manifest XML after the summary, " +
								"for details the summary leaves out such as meta-data. Default false.")
						.paging(100))
				.handler(args -> {
					Workspace workspace = context.workspace();
					FilePathNode path = workspace.findFile(MANIFEST_NAME);
					if (path == null)
						throw new ToolException("The workspace has no " + MANIFEST_NAME + ", so it does not look like an Android application. " +
								"Use workspace_info to see what it is, and list_files to look for manifests in other places.");
					FileInfo info = path.getValue();
					XmlElement document;
					if (info instanceof BinaryXmlFileInfo binaryXml)
						document = readBinaryXml(workspace, binaryXml);
					else if (info.isTextFile())
						document = parseXml(info.asTextFile().getText());
					else
						throw new ToolException(MANIFEST_NAME + " is neither Android binary XML nor text (detected as " + kind(info) + "), " +
								"which can be a deliberate obstacle to analysis. Inspect its bytes with read_file encoding='hex'.");

					XmlElement manifest = document.descendant("manifest");
					if (manifest == null)
						throw new ToolException(MANIFEST_NAME + " has no <manifest> element. Read it with read_file to see what it holds instead.");

					int offset = args.offset();
					StringBuilder sb = new StringBuilder();
					List<XmlElement> components = new ArrayList<>();
					for (XmlElement application : manifest.children("application"))
						for (XmlElement child : application.children)
							if (COMPONENT_TAGS.contains(child.name))
								components.add(child);
					String packageName = manifest.attribute("package");

					// The general part is only shown with the first page of components.
					if (offset == 0)
						sb.append(describeManifest(manifest, packageName)).append('\n');
					if (components.isEmpty())
						sb.append("The manifest declares no activities, services, receivers or providers.");
					else
						sb.append(Text.page(components, offset, args.limit(100, 500), "components",
								component -> describeComponent(workspace, component, packageName)));

					if (args.optBool("include_xml", false)) {
						boolean binary = info instanceof BinaryXmlFileInfo;
						String xml = binary ? decodeBinaryXml(workspace, (BinaryXmlFileInfo) info) : info.asTextFile().getText();
						String[] lines = xml.split("\r?\n");
						int shown = Math.min(lines.length, MANIFEST_XML_LINES);
						sb.append("\n\n").append(MANIFEST_NAME).append(binary ? ", decoded to XML:\n" : ":\n");
						for (int i = 0; i < shown; i++)
							sb.append(lines[i]).append('\n');
						if (shown < lines.length)
							sb.append("[Lines 1-").append(shown).append(" of ").append(lines.length)
									.append(". Continue with read_file path='").append(MANIFEST_NAME)
									.append("' start_line=").append(shown + 1).append(".]");
					}
					return sb.toString().stripTrailing();
				})
				.build();
	}

	@Nonnull
	private AgentTool listModified() {
		return AgentTool.builder("list_modified")
				.description("List the classes and files that were changed or removed in this session, with the number of edits " +
						"made to each. Use it to review your own changes before exporting, and class_diff to see what exactly " +
						"changed in a class. Classes and files that were newly added in this session are not tracked by Recaf " +
						"and do not appear here.")
				.schema(ToolSchema.object().paging(100))
				.handler(args -> {
					Workspace workspace = context.workspace();
					List<String> lines = new ArrayList<>();
					collectModified(workspace.getPrimaryResource(), "", lines);
					if (lines.isEmpty())
						return "Nothing was changed or removed in this session.";
					return Text.page(lines, args.offset(), args.limit(100, 500), "changed or removed items", line -> line);
				})
				.build();
	}

	/**
	 * @param resource
	 * 		Resource to collect changes of, including its nested archives.
	 * @param prefix
	 * 		Prefix naming the nested archive the resource was loaded from, empty for the primary resource.
	 * @param lines
	 * 		Collection to add a line per changed or removed item to.
	 */
	private static void collectModified(@Nonnull WorkspaceResource resource, @Nonnull String prefix, @Nonnull List<String> lines) {
		List<? extends Bundle<?>> classBundles = resource.classBundleStream().collect(Collectors.toList());
		for (Bundle<?> bundle : classBundles)
			for (String key : new TreeSet<>(bundle.getDirtyKeys()))
				lines.add("modified class  " + prefix + key + "  (" + edits(bundle, key) + ")");
		Bundle<FileInfo> files = resource.getFileBundle();
		for (String key : new TreeSet<>(files.getDirtyKeys()))
			lines.add("modified file   " + prefix + key + "  (" + edits(files, key) + ")");
		for (Bundle<?> bundle : classBundles)
			for (String key : new TreeSet<>(bundle.getRemovedKeys()))
				lines.add("removed class   " + prefix + key);
		for (String key : new TreeSet<>(files.getRemovedKeys()))
			lines.add("removed file    " + prefix + key);
		for (Map.Entry<String, WorkspaceFileResource> embedded : new TreeMap<>(resource.getEmbeddedResources()).entrySet())
			collectModified(embedded.getValue(), prefix + embedded.getKey() + NESTED_SEPARATOR, lines);
	}

	@Nonnull
	private static String edits(@Nonnull Bundle<?> bundle, @Nonnull String key) {
		Stack<?> history = bundle.getHistory(key);
		return count(history == null ? 1 : Math.max(1, history.size() - 1), "edit", "edits");
	}

	/**
	 * @param workspace
	 * 		Workspace to search.
	 * @param name
	 * 		Path of a file, in the form {@link #displayPath(FilePathNode, Map)} gives.
	 *
	 * @return Path to the file.
	 *
	 * @throws ToolException
	 * 		When the workspace has no such file.
	 */
	@Nonnull
	private static FilePathNode resolveFile(@Nonnull Workspace workspace, @Nonnull String name) {
		if (!name.contains(NESTED_SEPARATOR))
			return Lookups.requireFile(workspace, name);

		// A file inside a nested archive. Paths within different archives can be equal, so the plain name lookup cannot tell them apart.
		String wanted = name.trim();
		while (wanted.startsWith("/"))
			wanted = wanted.substring(1);
		String target = wanted;
		Map<WorkspaceResource, String> prefixes = new IdentityHashMap<>();
		return Workspaces.files(workspace)
				.filter(node -> displayPath(node, prefixes).equals(target))
				.findFirst()
				.orElseThrow(() -> new ToolException("No file named '" + target + "' in the workspace. " +
						"Use list_files to see what is there, paths of files in nested archives are listed in this form."));
	}

	/**
	 * @param node
	 * 		Path to a file.
	 * @param prefixes
	 * 		Cache of the prefixes of resources.
	 *
	 * @return The name of the file, preceded by the path of the nested archive it is in, if it is in one.
	 */
	@Nonnull
	private static String displayPath(@Nonnull FilePathNode node, @Nonnull Map<WorkspaceResource, String> prefixes) {
		WorkspaceResource resource = node.getValueOfType(WorkspaceResource.class);
		String name = node.getValue().getName();
		if (resource == null || resource.getContainingResource() == null)
			return name;
		String prefix = prefixes.get(resource);
		if (prefix == null) {
			prefix = nestedPrefix(resource);
			prefixes.put(resource, prefix);
		}
		return prefix + name;
	}

	@Nonnull
	private static String nestedPrefix(@Nonnull WorkspaceResource resource) {
		WorkspaceResource container = resource.getContainingResource();
		if (container == null)
			return "";
		for (Map.Entry<String, WorkspaceFileResource> embedded : container.getEmbeddedResources().entrySet())
			if (embedded.getValue() == resource)
				return nestedPrefix(container) + embedded.getKey() + NESTED_SEPARATOR;
		return nestedPrefix(container);
	}

	/**
	 * @param info
	 * 		File to describe.
	 *
	 * @return The kind of content Recaf detected in the file.
	 */
	@Nonnull
	static String kind(@Nonnull FileInfo info) {
		if (info instanceof BinaryXmlFileInfo) return "binary XML";
		if (info instanceof ArscFileInfo) return "ARSC resource table";
		if (info.isTextFile()) return "text";
		if (info.isImageFile()) return "image";
		if (info.isAudioFile()) return "audio";
		if (info.isVideoFile()) return "video";
		if (info.isNativeLibraryFile()) return "native library";
		// Recaf turns DEX files and archives into classes and nested resources when it loads them.
		// The ones still present as files are those it could not load.
		if (info instanceof DexFileInfo) return "DEX that could not be loaded";
		if (info instanceof ModulesFileInfo) return "JDK modules image that could not be loaded";
		if (info instanceof ApkFileInfo) return "nested APK archive that could not be loaded";
		if (info instanceof JarFileInfo) return "nested JAR archive that could not be loaded";
		if (info instanceof WarFileInfo) return "nested WAR archive that could not be loaded";
		if (info instanceof JModFileInfo) return "nested JMOD archive that could not be loaded";
		if (info.isZipFile()) return "nested ZIP archive that could not be loaded";
		if (IllegalClassSuspectProperty.get(info)) return "malformed class file";
		return "binary";
	}

	/**
	 * @param workspace
	 * 		Workspace the file belongs to, searched for the resource table that names referenced resources.
	 * @param binaryXml
	 * 		Android binary XML file.
	 *
	 * @return The file as XML text, the way the desktop UI shows it. It is made for reading: values are not escaped,
	 * so it is not necessarily well-formed and must not be parsed again.
	 *
	 * @throws ToolException
	 * 		When the file cannot be decoded.
	 */
	@Nonnull
	private static String decodeBinaryXml(@Nonnull Workspace workspace, @Nonnull BinaryXmlFileInfo binaryXml) {
		// Decoded on every call: the XML that the desktop UI caches on the file is carried over to edited copies of
		// the file, so it can be out of date.
		AndroidResourceProvider resources = resourceTable(workspace);
		try {
			return XmlDecoder.decode(binaryXml.getChunkModel(), AndroidRes.getAndroidBase(), resources);
		} catch (RuntimeException ex) {
			throw undecodable(binaryXml, ex);
		}
	}

	/**
	 * @param workspace
	 * 		Workspace the file belongs to, searched for the resource table that names referenced resources.
	 * @param binaryXml
	 * 		Android binary XML file.
	 *
	 * @return Unnamed element holding the top-level elements of the file, read from its binary form.
	 *
	 * @throws ToolException
	 * 		When the file cannot be decoded.
	 */
	@Nonnull
	private static XmlElement readBinaryXml(@Nonnull Workspace workspace, @Nonnull BinaryXmlFileInfo binaryXml) {
		BinaryXmlReader reader = new BinaryXmlReader(resourceTable(workspace));
		try {
			for (Chunk chunk : binaryXml.getChunkModel().getChunks())
				if (chunk instanceof XmlChunk xml)
					XmlDecoder.visitChunks(xml.getChunks(), reader);
		} catch (RuntimeException ex) {
			throw undecodable(binaryXml, ex);
		}
		return reader.root;
	}

	@Nonnull
	private static ToolException undecodable(@Nonnull BinaryXmlFileInfo binaryXml, @Nonnull RuntimeException cause) {
		return new ToolException("'" + binaryXml.getName() + "' could not be decoded as Android binary XML (" + cause + "). " +
				"It may be damaged on purpose. Inspect its bytes with read_file encoding='hex'.");
	}

	/**
	 * @param workspace
	 * 		Workspace to search.
	 *
	 * @return The resources of the application's resource table, or {@code null} when it has none.
	 */
	@Nullable
	private static AndroidResourceProvider resourceTable(@Nonnull Workspace workspace) {
		// The table is 'resources.arsc' at the root of an APK, the same assumption the desktop UI makes.
		FilePathNode path = workspace.findFile(ArscFileInfo.NAME);
		return path != null && path.getValue() instanceof ArscFileInfo arsc ? arsc.getResourceInfo() : null;
	}

	/**
	 * @param arsc
	 * 		Android resource table.
	 *
	 * @return One line per resource of the table, grouped by resource type.
	 * {@code null} when the table could not be decoded.
	 */
	@Nullable
	private static String describeResourceTable(@Nonnull ArscFileInfo arsc) {
		// A table that cannot be decoded is modeled by the shared empty instance.
		AndroidRes resources = arsc.getResourceInfo();
		if (resources == AndroidRes.getEmpty())
			return null;
		StringBuilder sb = new StringBuilder();
		for (List<AndroidRes.ResourceEntry> entries : resources.getEntriesByType().values()) {
			for (AndroidRes.ResourceEntry entry : entries) {
				sb.append(entry.fullName()).append(" (").append(AndroidXmlUtil.formatResourceId(entry.id())).append(") = ");
				if (entry.isComplex()) {
					sb.append(entry.complexValues().entrySet().stream()
							.map(value -> AndroidXmlUtil.formatComplexKey(resources, value.getKey()) + ": " +
									Text.quote(AndroidXmlUtil.formatBinaryValue(resources, value.getValue())))
							.collect(Collectors.joining(", ", "{", "}")));
				} else {
					sb.append(Text.quote(AndroidXmlUtil.formatValue(resources, entry)));
				}
				sb.append('\n');
			}
		}
		if (sb.length() == 0)
			return "[The resource table holds no resources.]";
		return sb.toString();
	}

	/**
	 * @param manifest
	 * 		The {@code manifest} element.
	 * @param packageName
	 * 		Package of the application, if declared.
	 *
	 * @return Summary of everything in the manifest except for its components.
	 */
	@Nonnull
	private static String describeManifest(@Nonnull XmlElement manifest, @Nullable String packageName) {
		StringBuilder sb = new StringBuilder();
		sb.append("Package: ").append(orNotDeclared(packageName)).append('\n');
		String versionName = manifest.attribute("versionName");
		String versionCode = manifest.attribute("versionCode");
		sb.append("Version: ").append(orNotDeclared(versionName));
		if (versionCode != null)
			sb.append(" (code ").append(versionCode).append(')');
		sb.append('\n');
		XmlElement sdk = manifest.child("uses-sdk");
		sb.append("SDK: min ").append(orNotDeclared(sdk == null ? null : sdk.attribute("minSdkVersion")))
				.append(", target ").append(orNotDeclared(sdk == null ? null : sdk.attribute("targetSdkVersion"))).append('\n');

		XmlElement application = manifest.child("application");
		if (application != null) {
			List<String> parts = new ArrayList<>();
			String applicationClass = application.attribute("name");
			if (applicationClass != null)
				parts.add("class " + componentClass(packageName, applicationClass));
			for (String flag : List.of("label", "debuggable", "allowBackup", "usesCleartextTraffic", "networkSecurityConfig"))
				if (application.attribute(flag) != null)
					parts.add(flag + "=" + application.attribute(flag));
			if (!parts.isEmpty())
				sb.append("Application: ").append(String.join(", ", parts)).append('\n');
		}

		List<String> requested = new ArrayList<>();
		for (XmlElement element : manifest.children)
			if (element.name.startsWith("uses-permission") && element.attribute("name") != null)
				requested.add(element.attribute("name"));
		sb.append("Permissions requested (").append(requested.size()).append(")");
		if (!requested.isEmpty())
			sb.append(": ").append(String.join(", ", requested));
		sb.append('\n');
		List<String> declared = new ArrayList<>();
		for (XmlElement element : manifest.children("permission")) {
			String level = element.attribute("protectionLevel");
			declared.add(orNotDeclared(element.attribute("name")) + (level == null ? "" : " (protectionLevel " + level + ")"));
		}
		if (!declared.isEmpty())
			sb.append("Permissions declared (").append(declared.size()).append("): ").append(String.join(", ", declared)).append('\n');
		return sb.toString();
	}

	/**
	 * @param workspace
	 * 		Workspace to look the class of the component up in.
	 * @param component
	 * 		An {@code activity}, {@code activity-alias}, {@code service}, {@code receiver} or {@code provider} element.
	 * @param packageName
	 * 		Package of the application, if declared.
	 *
	 * @return Line describing the component, followed by a line per intent-filter.
	 */
	@Nonnull
	private static String describeComponent(@Nonnull Workspace workspace, @Nonnull XmlElement component, @Nullable String packageName) {
		List<XmlElement> filters = component.children("intent-filter");
		boolean isAlias = component.name.equals("activity-alias");
		String className = componentClass(packageName, component.attribute("name"));
		StringBuilder sb = new StringBuilder();
		sb.append(component.name).append(' ').append(className);
		String target = component.attribute("targetActivity");
		if (target != null)
			sb.append(" -> ").append(componentClass(packageName, target));

		List<String> notes = new ArrayList<>();
		String exported = component.attribute("exported");
		if (exported != null)
			notes.add("exported=" + exported);
		else if (component.name.equals("provider"))
			notes.add("exported not declared (default false since target SDK 17)");
		else
			notes.add("exported not declared (default " + (filters.isEmpty() ? "false: no intent-filter" : "true: has intent-filter") + ")");
		for (String attribute : List.of("permission", "readPermission", "writePermission", "authorities", "enabled", "process"))
			if (component.attribute(attribute) != null)
				notes.add(attribute + "=" + component.attribute(attribute));
		if (!isAlias && component.attribute("name") != null && workspace.findClass(false, className) == null)
			notes.add("class not in workspace");
		sb.append("  [").append(String.join(", ", notes)).append(']');

		for (XmlElement filter : filters) {
			List<String> parts = new ArrayList<>();
			String actions = names(filter.children("action"));
			if (!actions.isEmpty())
				parts.add("actions " + actions);
			String categories = names(filter.children("category"));
			if (!categories.isEmpty())
				parts.add("categories " + categories);
			for (XmlElement data : filter.children("data"))
				if (!data.attributes.isEmpty())
					parts.add("data " + data.attributes.entrySet().stream()
							.map(attribute -> attribute.getKey() + "=" + attribute.getValue())
							.collect(Collectors.joining(" ")));
			sb.append("\n    intent-filter: ").append(parts.isEmpty() ? "(empty)" : String.join("; ", parts));
		}
		return sb.toString();
	}

	@Nonnull
	private static String names(@Nonnull List<XmlElement> elements) {
		return elements.stream()
				.map(element -> element.attribute("name"))
				.filter(name -> name != null && !name.isEmpty())
				.collect(Collectors.joining(", "));
	}

	/**
	 * @param packageName
	 * 		Package of the application, if declared.
	 * @param name
	 * 		Class name as written in the manifest, which may be relative to the package.
	 *
	 * @return Internal name of the class.
	 */
	@Nonnull
	private static String componentClass(@Nullable String packageName, @Nullable String name) {
		if (name == null || name.isEmpty())
			return "(unnamed)";
		String full = name;
		if (packageName != null && !packageName.isEmpty()) {
			if (name.startsWith("."))
				full = packageName + name;
			else if (name.indexOf('.') < 0)
				full = packageName + "." + name;
		}
		return full.replace('.', '/');
	}

	@Nonnull
	private static String orNotDeclared(@Nullable String value) {
		return value == null || value.isEmpty() ? "not declared" : value;
	}

	/**
	 * Reads the elements and attributes of XML text, ignoring everything else. Used for manifests that are stored
	 * as text, as they are in unpacked projects. Binary manifests are read by {@link BinaryXmlReader}.
	 * <p>
	 * This reader never fails, it reads what it can make sense of. A strict parser would give up entirely on text
	 * that is not well-formed, and would have to be guarded against external entities.
	 *
	 * @param xml
	 * 		XML text.
	 *
	 * @return Unnamed element holding the top-level elements of the text.
	 */
	@Nonnull
	private static XmlElement parseXml(@Nonnull String xml) {
		XmlElement root = new XmlElement("");
		Deque<XmlElement> open = new ArrayDeque<>();
		open.push(root);
		int length = xml.length();
		int position = 0;
		while (position < length) {
			int tag = xml.indexOf('<', position);
			if (tag < 0)
				break;
			if (xml.startsWith("<!--", tag)) {
				position = after(xml, "-->", tag + 4);
			} else if (xml.startsWith("<![CDATA[", tag)) {
				position = after(xml, "]]>", tag + 9);
			} else if (xml.startsWith("<?", tag)) {
				position = after(xml, "?>", tag + 2);
			} else if (xml.startsWith("<!", tag)) {
				position = after(xml, ">", tag + 2);
			} else if (xml.startsWith("</", tag)) {
				if (open.size() > 1)
					open.pop();
				position = after(xml, ">", tag + 2);
			} else {
				int nameEnd = tag + 1;
				while (nameEnd < length && !endsName(xml.charAt(nameEnd)))
					nameEnd++;
				XmlElement element = new XmlElement(xml.substring(tag + 1, nameEnd));
				open.peek().children.add(element);
				position = readAttributes(xml, nameEnd, element);
				if (!element.selfClosing)
					open.push(element);
			}
		}
		return root;
	}

	/**
	 * @param xml
	 * 		XML text.
	 * @param start
	 * 		Position after the name of a start tag.
	 * @param element
	 * 		Element to add the attributes of the tag to.
	 *
	 * @return Position after the end of the tag.
	 */
	private static int readAttributes(@Nonnull String xml, int start, @Nonnull XmlElement element) {
		int length = xml.length();
		int position = start;
		while (position < length) {
			char c = xml.charAt(position);
			if (c == '>') {
				return position + 1;
			} else if (c == '/') {
				element.selfClosing = true;
				position++;
			} else if (Character.isWhitespace(c)) {
				position++;
			} else {
				int nameEnd = position;
				while (nameEnd < length && xml.charAt(nameEnd) != '=' && !endsName(xml.charAt(nameEnd)))
					nameEnd++;
				String name = xml.substring(position, nameEnd);
				position = skipWhitespace(xml, nameEnd);
				String value = "";
				if (position < length && xml.charAt(position) == '=') {
					position = skipWhitespace(xml, position + 1);
					if (position < length && (xml.charAt(position) == '"' || xml.charAt(position) == '\'')) {
						int close = xml.indexOf(xml.charAt(position), position + 1);
						if (close < 0)
							close = length;
						value = unescape(xml.substring(position + 1, close));
						position = Math.min(length, close + 1);
					} else {
						int valueEnd = position;
						while (valueEnd < length && !endsName(xml.charAt(valueEnd)))
							valueEnd++;
						value = unescape(xml.substring(position, valueEnd));
						position = valueEnd;
					}
				}
				int colon = name.indexOf(':');
				element.addAttribute(name.substring(colon + 1), colon >= 0, value);
			}
		}
		return length;
	}

	private static boolean endsName(char c) {
		return c == '>' || c == '/' || Character.isWhitespace(c);
	}

	private static int skipWhitespace(@Nonnull String text, int position) {
		while (position < text.length() && Character.isWhitespace(text.charAt(position)))
			position++;
		return position;
	}

	private static int after(@Nonnull String text, @Nonnull String marker, int from) {
		int index = text.indexOf(marker, from);
		return index < 0 ? text.length() : index + marker.length();
	}

	@Nonnull
	private static String unescape(@Nonnull String value) {
		if (value.indexOf('&') < 0)
			return value;
		StringBuilder sb = new StringBuilder(value.length());
		int position = 0;
		while (position < value.length()) {
			char c = value.charAt(position);
			int end = c == '&' ? value.indexOf(';', position) : -1;
			String replacement = end < 0 ? null : entity(value.substring(position + 1, end));
			if (replacement == null) {
				sb.append(c);
				position++;
			} else {
				sb.append(replacement);
				position = end + 1;
			}
		}
		return sb.toString();
	}

	@Nullable
	private static String entity(@Nonnull String name) {
		switch (name) {
			case "lt": return "<";
			case "gt": return ">";
			case "amp": return "&";
			case "quot": return "\"";
			case "apos": return "'";
			default:
				if (name.length() < 2 || name.length() > 8 || name.charAt(0) != '#')
					return null;
				try {
					boolean hex = name.charAt(1) == 'x' || name.charAt(1) == 'X';
					int codePoint = hex ? Integer.parseInt(name.substring(2), 16) : Integer.parseInt(name.substring(1));
					return Character.isValidCodePoint(codePoint) ? new String(Character.toChars(codePoint)) : null;
				} catch (NumberFormatException ex) {
					return null;
				}
		}
	}

	/**
	 * @param data
	 * 		Bytes to show.
	 * @param offset
	 * 		Offset of the first byte to show.
	 * @param length
	 * 		Number of bytes to show.
	 *
	 * @return Hex dump of the range with 16 bytes per row, each row with its offset and an ASCII column,
	 * followed by a note on how to get the following bytes if there are more.
	 */
	@Nonnull
	private static String hexDump(@Nonnull byte[] data, int offset, int length) {
		if (data.length == 0)
			return "[The file is empty.]";
		if (offset >= data.length)
			return "[No byte at offset " + offset + ". The file has " + data.length + " bytes.]";
		int end = (int) Math.min(data.length, (long) offset + length);
		StringBuilder sb = new StringBuilder();
		StringBuilder ascii = new StringBuilder(16);
		for (int row = offset; row < end; row += 16) {
			String rowOffset = Integer.toHexString(row);
			for (int pad = rowOffset.length(); pad < 8; pad++)
				sb.append('0');
			sb.append(rowOffset).append(' ');
			ascii.setLength(0);
			for (int i = 0; i < 16; i++) {
				if (i == 8)
					sb.append(' ');
				if (row + i < end) {
					int value = data[row + i] & 0xFF;
					sb.append(' ').append(HEX[value >> 4]).append(HEX[value & 0xF]);
					ascii.append(value >= 0x20 && value < 0x7F ? (char) value : '.');
				} else {
					sb.append("   ");
				}
			}
			sb.append("  |").append(ascii).append("|\n");
		}
		if (end < data.length)
			sb.append("[Bytes ").append(offset).append('-').append(end - 1).append(" of ").append(data.length)
					.append(". Call again with offset=").append(end).append(" to continue.]");
		else if (offset > 0)
			sb.append("[Bytes ").append(offset).append('-').append(end - 1).append(" of ").append(data.length).append(".]");
		return sb.toString().stripTrailing();
	}

	/**
	 * @param text
	 * 		Text decoded from bytes that may not be text.
	 *
	 * @return The text with control characters replaced, so that it is safe to show.
	 */
	@Nonnull
	private static String printable(@Nonnull String text) {
		StringBuilder sb = new StringBuilder(text.length());
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			sb.append(Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t' ? '�' : c);
		}
		return sb.toString();
	}

	/**
	 * @param text
	 * 		Text to wrap.
	 *
	 * @return The text with a line break inserted into every stretch of {@link #MAX_LINE_LENGTH} characters without
	 * one. Minified files put megabytes on a single line, which paging by lines could otherwise not get through.
	 */
	@Nonnull
	private static String wrapLongLines(@Nonnull String text) {
		StringBuilder sb = null;
		int lineStart = 0;
		int copied = 0;
		for (int i = 0; i < text.length(); i++) {
			if (text.charAt(i) == '\n') {
				lineStart = i + 1;
			} else if (i - lineStart == MAX_LINE_LENGTH) {
				if (sb == null)
					sb = new StringBuilder(text.length() + text.length() / MAX_LINE_LENGTH + 1);
				sb.append(text, copied, i).append('\n');
				copied = i;
				lineStart = i;
			}
		}
		return sb == null ? text : sb.append(text, copied, text.length()).toString();
	}

	@Nonnull
	private static String trimSlashes(@Nonnull String path) {
		int start = 0;
		int end = path.length();
		while (start < end && path.charAt(start) == '/')
			start++;
		while (end > start && path.charAt(end - 1) == '/')
			end--;
		return path.substring(start, end);
	}

	private static int segments(@Nonnull String packageName) {
		int segments = 1;
		for (int i = 0; i < packageName.length(); i++)
			if (packageName.charAt(i) == '/')
				segments++;
		return segments;
	}

	/**
	 * @param packageName
	 * 		Package name.
	 * @param depth
	 * 		Number of leading name segments to keep.
	 *
	 * @return The package itself if it is not deeper than the given depth, otherwise its ancestor at that depth.
	 */
	@Nonnull
	private static String ancestor(@Nonnull String packageName, int depth) {
		int index = -1;
		for (int i = 0; i < depth; i++) {
			index = packageName.indexOf('/', index + 1);
			if (index < 0)
				return packageName;
		}
		return packageName.substring(0, index);
	}

	@Nonnull
	private static String count(int number, @Nonnull String singular, @Nonnull String plural) {
		return number + " " + (number == 1 ? singular : plural);
	}

	/**
	 * Packages shown as one line of the package listing.
	 */
	private static final class PackageGroup {
		private final String name;
		private int classes;
		private int packages;
		private boolean holdsClasses;

		private PackageGroup(@Nonnull String name) {
			this.name = name;
		}
	}

	/**
	 * File of the file listing.
	 */
	private static final class ListedFile {
		private final String path;
		private final FilePathNode node;

		private ListedFile(@Nonnull String path, @Nonnull FilePathNode node) {
			this.path = path;
			this.node = node;
		}
	}

	/**
	 * Builds the elements of Android binary XML from its chunks, the way Android itself reads them.
	 * <p>
	 * The decoded text is no basis for a summary: its values are not escaped, so a crafted label could pose as
	 * further attributes and elements. Reading the chunks also sees through two tricks that make decoded text lie.
	 * Android identifies its own attributes by resource identifier, so their names in the string pool can be
	 * replaced by anything. And Android takes their values from the typed value, so the raw string stored next to
	 * it can say something else.
	 */
	private static final class BinaryXmlReader extends XmlDecoder {
		private final XmlElement root = new XmlElement("");
		private final Deque<XmlElement> open = new ArrayDeque<>();
		private StringPoolChunk strings;
		private XmlResourceMapChunk resourceMap;

		private BinaryXmlReader(@Nullable AndroidResourceProvider resources) {
			super(AndroidRes.getAndroidBase(), resources);
			open.push(root);
		}

		@Override
		public void stringPool(StringPoolChunk chunk) {
			super.stringPool(chunk);
			strings = chunk;
		}

		@Override
		public void xmlResourceMap(XmlResourceMapChunk chunk) {
			super.xmlResourceMap(chunk);
			resourceMap = chunk;
		}

		@Override
		public void startElement(XmlStartElementChunk chunk) {
			XmlElement element = new XmlElement(chunk.getName());
			for (XmlAttribute attribute : chunk.getAttributes()) {
				String frameworkName = frameworkAttributeName(attribute);
				String name = frameworkName != null ? frameworkName : attribute.name();
				String namespace = attribute.namespace();
				element.addAttribute(name == null ? "" : name, frameworkName != null || (namespace != null && !namespace.isEmpty()),
						value(attribute, name, frameworkName != null));
			}
			open.peek().children.add(element);
			open.push(element);
		}

		@Override
		public void endElement(XmlEndElementChunk chunk) {
			if (open.size() > 1)
				open.pop();
		}

		/**
		 * @param attribute
		 * 		Attribute of an element.
		 * @param name
		 * 		Name of the attribute, which tells how to show numbers that stand for flags or enumerated values.
		 * @param framework
		 *        {@code true} for an attribute of the Android framework.
		 *
		 * @return The value of the attribute as text. An attribute stores a typed value and optionally a raw string,
		 * which need not agree. Android takes its own attributes from the typed value, and the attributes it looks up
		 * by name, such as {@code package}, from the raw string if there is one. This does the same.
		 */
		@Nonnull
		private String value(@Nonnull XmlAttribute attribute, @Nullable String name, boolean framework) {
			String raw = attribute.rawValueIndex() >= 0 ? attribute.rawValue() : null;
			BinaryResourceValue typed = attribute.typedValue();
			String fromTyped = null;
			// Strings are looked up here, the formatting of the decoder does not resolve them.
			if (typed != null && typed.type() == BinaryResourceValue.Type.STRING)
				fromTyped = AndroidXmlUtil.getString(strings, typed.data());
			else if (typed != null && typed.type() != BinaryResourceValue.Type.NULL)
				fromTyped = formatValue(typed, name == null ? "" : name);
			String preferred = framework ? fromTyped : raw;
			String fallback = framework ? raw : fromTyped;
			return preferred != null ? preferred : fallback != null ? fallback : "";
		}

		/**
		 * @param attribute
		 * 		Attribute of an element.
		 *
		 * @return Name of the Android framework attribute the resource map assigns to the attribute,
		 * or {@code null} if it assigns none.
		 */
		@Nullable
		private String frameworkAttributeName(@Nonnull XmlAttribute attribute) {
			BinaryResourceIdentifier identifier = resourceMap == null ? null : resourceMap.getResourceId(attribute.nameIndex());
			String resourceName = identifier == null ? null : AndroidRes.getAndroidBase().getResName(identifier.id());
			// Resource names have the form 'attr/exported'.
			return resourceName == null ? null : resourceName.substring(resourceName.lastIndexOf('/') + 1);
		}
	}

	/**
	 * Element of an XML document, reduced to what the manifest summary needs.
	 */
	private static final class XmlElement {
		private final String name;
		/** Attribute values by attribute name without namespace prefix. */
		private final Map<String, String> attributes = new LinkedHashMap<>();
		private final Set<String> namespaced = new HashSet<>();
		private final List<XmlElement> children = new ArrayList<>();
		private boolean selfClosing;

		private XmlElement(@Nonnull String name) {
			this.name = name;
		}

		private void addAttribute(@Nonnull String localName, boolean namespaced, @Nonnull String value) {
			// Android reads the attributes of its own namespace, so 'android:name' wins over a plain 'name' decoy.
			if (namespaced)
				this.namespaced.add(localName);
			else if (this.namespaced.contains(localName))
				return;
			attributes.put(localName, value);
		}

		@Nullable
		private String attribute(@Nonnull String localName) {
			return attributes.get(localName);
		}

		@Nonnull
		private List<XmlElement> children(@Nonnull String childName) {
			return children.stream().filter(child -> child.name.equals(childName)).collect(Collectors.toList());
		}

		@Nullable
		private XmlElement child(@Nonnull String childName) {
			for (XmlElement child : children)
				if (child.name.equals(childName))
					return child;
			return null;
		}

		@Nullable
		private XmlElement descendant(@Nonnull String elementName) {
			Deque<XmlElement> pending = new ArrayDeque<>(children);
			while (!pending.isEmpty()) {
				XmlElement element = pending.remove();
				if (element.name.equals(elementName))
					return element;
				pending.addAll(element.children);
			}
			return null;
		}
	}
}
