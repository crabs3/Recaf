package software.coley.recaf.agent.tool.impl;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import me.darknet.assembler.ast.ASTElement;
import me.darknet.assembler.ast.specific.ASTClass;
import me.darknet.assembler.ast.specific.ASTMethod;
import me.darknet.assembler.backend.dalvik.DalvikClassRepresentation;
import me.darknet.assembler.backend.jvm.compile.JavaClassRepresentation;
import me.darknet.assembler.compiler.ClassResult;
import me.darknet.assembler.error.Diagnostic;
import me.darknet.assembler.error.Outcome;
import me.darknet.assembler.error.Severity;
import me.darknet.assembler.parser.Token;
import me.darknet.assembler.util.Location;
import me.darknet.dex.tree.definitions.ClassDefinition;
import me.darknet.dex.tree.definitions.MemberIdentifier;
import me.darknet.dex.tree.type.Types;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import software.coley.recaf.RecafConstants;
import software.coley.recaf.agent.tool.AgentTool;
import software.coley.recaf.agent.tool.ToolArgs;
import software.coley.recaf.agent.tool.ToolContext;
import software.coley.recaf.agent.tool.ToolException;
import software.coley.recaf.agent.tool.ToolProvider;
import software.coley.recaf.agent.tool.ToolSchema;
import software.coley.recaf.agent.tool.support.Lookups;
import software.coley.recaf.agent.tool.support.Text;
import software.coley.recaf.agent.tool.support.Workspaces;
import software.coley.recaf.info.AndroidClassInfo;
import software.coley.recaf.info.ApkFileInfo;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.DexFileInfo;
import software.coley.recaf.info.FileInfo;
import software.coley.recaf.info.Info;
import software.coley.recaf.info.JModFileInfo;
import software.coley.recaf.info.JarFileInfo;
import software.coley.recaf.info.WarFileInfo;
import software.coley.recaf.info.ZipFileInfo;
import software.coley.recaf.info.builder.AndroidClassInfoBuilder;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.info.member.ClassMember;
import software.coley.recaf.info.member.FieldMember;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.info.properties.Property;
import software.coley.recaf.info.properties.builtin.CachedDecompileProperty;
import software.coley.recaf.info.properties.builtin.OriginalClassNameProperty;
import software.coley.recaf.info.properties.builtin.RemapOriginTaskProperty;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.path.FilePathNode;
import software.coley.recaf.path.PathNode;
import software.coley.recaf.path.PathNodes;
import software.coley.recaf.services.assembler.AndroidAssemblerPipeline;
import software.coley.recaf.services.assembler.AssemblerPipeline;
import software.coley.recaf.services.assembler.AssemblerPipelineManager;
import software.coley.recaf.services.comment.ClassComments;
import software.coley.recaf.services.comment.CommentManager;
import software.coley.recaf.services.comment.WorkspaceComments;
import software.coley.recaf.services.workspace.io.InfoImporter;
import software.coley.recaf.services.workspace.io.PathWorkspaceExportConsumer;
import software.coley.recaf.services.workspace.io.WorkspaceCompressType;
import software.coley.recaf.services.workspace.io.WorkspaceExportOptions;
import software.coley.recaf.services.workspace.io.WorkspaceOutputType;
import software.coley.recaf.util.io.ByteSources;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.AndroidClassBundle;
import software.coley.recaf.workspace.model.bundle.Bundle;
import software.coley.recaf.workspace.model.bundle.FileBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceDirectoryResource;
import software.coley.recaf.workspace.model.resource.WorkspaceFileResource;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Stack;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Tools for changing the program: assembling classes and methods, adding and removing content, undoing changes,
 * exporting the result, and annotating classes and members with comments.
 * <p>
 * Every change is made through the bundles of the workspace, so Recaf's change history and listeners see it.
 *
 * @author Recaf agent tooling
 */
public final class EditTools implements ToolProvider {
	private static final String SOURCE_NAME = "source";
	private static final int MAX_DIAGNOSTICS = 8;
	private static final int MAX_LISTED = 10;
	private static final String JASM_HINT = "The syntax is the one disassemble_class and disassemble_method print.";
	private final List<Deletion> deletions = new ArrayList<>();
	private final ToolContext context;

	/**
	 * @param context
	 * 		Context the tools operate in.
	 */
	public EditTools(@Nonnull ToolContext context) {
		this.context = context;
	}

	@Nonnull
	@Override
	public List<AgentTool> tools() {
		return List.of(assembleClass(), assembleMethod(), putFile(), deleteClass(), deleteFile(), undoChange(),
				exportWorkspace(), setComment(), getComments());
	}

	@Nonnull
	private AgentTool assembleClass() {
		return AgentTool.builder("assemble_class")
				.description("Replace a class, or add a new one, from its complete source in Recaf's JASM assembler syntax. " +
						"The usual workflow is disassemble_class, edit the text, then pass all of it back here. " +
						"The class that is replaced is the one the source declares: if no class of that name exists a new class is added. " +
						"To change a single method prefer assemble_method, which needs less text. " +
						"Returns what changed: the class name and its number of fields and methods before and after, " +
						"plus any assembler warnings. When the source has errors nothing is changed and each error " +
						"is reported with its line and column. Works for JVM and Android (DEX) classes, each in its own instruction set.")
				.schema(ToolSchema.object()
						.string("source", "Complete JASM source of the class, as printed by disassemble_class. " +
								"It starts with directives such as '.super java/lang/Object' followed by '.class public com/example/Main { ... }'.")
						.optString("class", "Name of the existing class this source replaces, for example 'com/example/Main'. " +
								"Optional safeguard: when given, the source must declare exactly this class."))
				.mutating()
				.handler(args -> {
					Workspace workspace = context.workspace();
					WorkspaceResource primary = workspace.getPrimaryResource();
					String source = args.string("source");
					String className = args.optString("class", null);
					ClassPathNode target = className == null || className.isBlank() ? null : requireEditableClass(workspace, className);

					// Parsing depends on the instruction set. Without a named class, start with the kind the workspace holds.
					boolean android = target != null ? target.getValue().isAndroidClass() : holdsOnlyAndroidClasses(primary);
					AssemblerPipeline<?, ?, ?> pipeline = pipeline(workspace, android);
					List<ASTElement> elements = parse(pipeline, source);
					String declaredName = single(elements, ASTClass.class, "class", "'.class'",
							"To replace a single method use assemble_method.").getName().literal();
					if (target == null) {
						ClassPathNode existing = workspace.findClass(false, declaredName);
						if (existing != null) {
							requirePrimary(workspace, existing, "class '" + declaredName + "'");
							target = existing;
							if (existing.getValue().isAndroidClass() != android) {
								android = !android;
								pipeline = pipeline(workspace, android);
								elements = parse(pipeline, source);
							}
						}
					} else if (!declaredName.equals(target.getValue().getName())) {
						throw new ToolException("The source declares class '" + declaredName + "' but 'class' names '" +
								target.getValue().getName() + "'. The assembler does not rename classes: use rename_class for that, " +
								"or leave out 'class' to add '" + declaredName + "' as a new class.");
					}

					// A class that does not exist yet is assembled against an empty placeholder in the bundle it will join.
					ClassInfo previous = target == null ? null : target.getValue();
					ClassPathNode path = target;
					String destination = "";
					if (path == null) {
						if (!isValidClassName(declaredName))
							throw new ToolException("'" + declaredName + "' is not a valid class name. " +
									"Use the internal form, for example 'com/example/Main'.");
						if (android) {
							String dexName = new TreeMap<>(primary.getAndroidClassBundles()).firstKey();
							AndroidClassBundle dex = primary.getAndroidClassBundles().get(dexName);
							ClassDefinition placeholder = new ClassDefinition(Types.instanceTypeFromInternalName(declaredName), Types.OBJECT, 0);
							path = PathNodes.classPath(workspace, primary, dex, new AndroidClassInfoBuilder(placeholder).build());
							destination = " to " + dexName;
						} else {
							path = PathNodes.classPath(workspace, primary, primary.getJvmClassBundle(),
									new JvmClassInfoBuilder(emptyClass(declaredName)).build());
						}
					}

					Assembled assembled = assemble(pipeline, elements, path, source);
					ClassInfo updated = toClassInfo(previous, assembled.representation());
					if (!updated.getName().equals(declaredName))
						throw new ToolException("The assembler produced class '" + updated.getName() + "' instead of '" + declaredName + "'. Nothing was changed.");
					classBundle(path).put(updated);

					StringBuilder sb = new StringBuilder();
					if (previous == null) {
						sb.append("Added new class ").append(declaredName).append(destination).append(" with ")
								.append(count(updated.getFields().size(), "field")).append(" and ")
								.append(count(updated.getMethods().size(), "method")).append('.');
					} else {
						sb.append("Replaced class ").append(declaredName).append(": fields ")
								.append(previous.getFields().size()).append(" -> ").append(updated.getFields().size())
								.append(", methods ").append(previous.getMethods().size()).append(" -> ").append(updated.getMethods().size()).append('.');
						appendMemberChanges(sb, "Fields", previous.getFields(), updated.getFields());
						appendMemberChanges(sb, "Methods", previous.getMethods(), updated.getMethods());
					}
					appendWarnings(sb, source, assembled.warnings());
					return sb.toString();
				})
				.build();
	}

	@Nonnull
	private AgentTool assembleMethod() {
		return AgentTool.builder("assemble_method")
				.description("Replace one method of a class from JASM assembler source for just that method. " +
						"The usual workflow is disassemble_method, edit the text, then pass it back here. " +
						"This is the way to patch behavior: change a constant, a condition, a call or a return value. " +
						"The method that is replaced is the one the source declares. If the new source changes the name or descriptor, " +
						"pass 'method' (and 'descriptor') to name the method being replaced, otherwise the source is added as an additional method. " +
						"Returns which method was replaced or added and the method count before and after, plus any assembler warnings. " +
						"When the source has errors nothing is changed and each error is reported with its line and column. " +
						"Works for JVM and Android (DEX) classes, each in its own instruction set.")
				.schema(ToolSchema.object()
						.string("class", "Name of the class declaring the method, for example 'com/example/Main'.")
						.string("source", "JASM source of the method, as printed by disassemble_method. " +
								"It has the form '.method public static check (Ljava/lang/String;)Z { code: { ... } }'.")
						.optString("method", "Name of the existing method to replace, for example 'check'. " +
								"Only needed when the source declares a different name or descriptor than the method it replaces.")
						.optString("descriptor", "Descriptor of the existing method to replace, for example '(Ljava/lang/String;)Z'. " +
								"Only needed with 'method' when the class has several methods of that name."))
				.mutating()
				.handler(args -> {
					Workspace workspace = context.workspace();
					ClassPathNode path = requireEditableClass(workspace, args.string("class"));
					ClassInfo info = path.getValue();
					String source = args.string("source");
					String methodName = args.optString("method", null);
					String methodDescriptor = args.optString("descriptor", null);
					if ((methodName == null || methodName.isBlank()) && methodDescriptor != null && !methodDescriptor.isBlank())
						throw new ToolException("'descriptor' identifies the method to replace together with 'method'. Pass both, or neither.");

					AssemblerPipeline<?, ?, ?> pipeline = pipeline(workspace, info.isAndroidClass());
					List<ASTElement> elements = parse(pipeline, source);
					ASTMethod declaration = single(elements, ASTMethod.class, "method", "'.method'",
							"To replace a whole class use assemble_class.");
					String newName = declaration.getName().literal();
					String newDescriptor = declaration.getDescriptor().literal();
					MethodMember declared = info.getDeclaredMethod(newName, newDescriptor);
					MethodMember replaced = methodName == null || methodName.isBlank() ? declared :
							Lookups.requireMethod(info, methodName, methodDescriptor);
					boolean changesSignature = replaced != null &&
							!(replaced.getName().equals(newName) && replaced.getDescriptor().equals(newDescriptor));
					if (changesSignature && declared != null)
						throw new ToolException("The source declares " + newName + newDescriptor + ", which class '" + info.getName() +
								"' already has in addition to " + Lookups.signature(replaced) + ". Replacing one with the other would " +
								"silently drop a method. Leave out 'method' to replace " + newName + newDescriptor + " itself, " +
								"or give the source another name.");

					// The DEX assembler writes a method straight into the class definition it is given. It gets a copy, so that
					// the class in the workspace only ever changes through its bundle and keeps a state to undo to.
					ClassPathNode assemblyPath = path;
					if (info.isAndroidClass()) {
						ClassDefinition copy = copyOf(info.asAndroidClass().getBackingDefinition());
						AndroidClassInfo copyInfo = info.asAndroidClass().toAndroidBuilder().adaptFrom(copy).build();
						assemblyPath = PathNodes.classPath(workspace, workspace.getPrimaryResource(), classBundle(path), copyInfo);
					}
					Assembled assembled = assemble(pipeline, elements, assemblyPath, source);
					Object representation = assembled.representation();
					if (changesSignature)
						representation = withoutMethod(representation, replaced);
					ClassInfo updated = toClassInfo(info, representation);
					if (updated.getDeclaredMethod(newName, newDescriptor) == null)
						throw new ToolException("The assembler did not produce method " + newName + newDescriptor + ". Nothing was changed.");
					classBundle(path).put(updated);

					StringBuilder sb = new StringBuilder();
					if (replaced == null)
						sb.append("Added method ").append(newName).append(newDescriptor).append(" to class ").append(info.getName())
								.append(". No existing method was replaced, because the class had no method of that name and descriptor.");
					else if (changesSignature)
						sb.append("Replaced method ").append(Lookups.signature(replaced)).append(" of class ").append(info.getName())
								.append(" with ").append(newName).append(newDescriptor)
								.append(". Calls to the old name were not rewritten, use rename_member when only the name should change.");
					else
						sb.append("Replaced method ").append(newName).append(newDescriptor).append(" of class ").append(info.getName()).append('.');
					sb.append(" Methods ").append(info.getMethods().size()).append(" -> ").append(updated.getMethods().size()).append('.');
					appendWarnings(sb, source, assembled.warnings());
					return sb.toString();
				})
				.build();
	}

	@Nonnull
	private AgentTool putFile() {
		return AgentTool.builder("put_file")
				.description("Add a file to the workspace or replace the content of an existing one, for example a configuration " +
						"or resource file. Pass the content as 'text', or as 'base64' for binary content. " +
						"Returns whether the file was added or replaced and its size. " +
						"Classes are not files here: change them with assemble_class and assemble_method.")
				.schema(ToolSchema.object()
						.string("path", "Path of the file within the workspace, for example 'config/app.properties'.")
						.optString("text", "New content of the file as text, stored as UTF-8.")
						.optString("base64", "New content of the file as Base64, for binary files. Pass either this or 'text'."))
				.mutating()
				.handler(args -> {
					Workspace workspace = context.workspace();
					WorkspaceResource primary = workspace.getPrimaryResource();
					String name = filePath(args.string("path"));
					boolean hasText = args.has("text");
					boolean hasBase64 = args.has("base64");
					if (hasText == hasBase64)
						throw new ToolException("Pass the content either as 'text' or as 'base64', exactly one of them.");
					byte[] content = hasText ? String.valueOf(args.optString("text", "")).getBytes(StandardCharsets.UTF_8) :
							decodeBase64(String.valueOf(args.optString("base64", "")));

					FilePathNode existing = workspace.findFile(name);
					if (existing != null)
						requirePrimary(workspace, existing, "file '" + name + "'");
					else
						requireFreeFilePath(primary, name);
					Info imported = context.service(InfoImporter.class).readInfo(name, ByteSources.wrap(content));
					if (!imported.isFile())
						throw new ToolException("The content for '" + name + "' is a Java class file. Classes are not stored as files: " +
								"write the class in JASM and add it with assemble_class.");
					FileInfo file = imported.asFile();
					FileBundle bundle = primary.getFileBundle();
					if (existing != null) {
						// Keep what Recaf remembered about the entry, such as whether it was compressed in the archive.
						for (Property<?> property : existing.getValue().getPersistentProperties().values())
							if (file.getProperty(property.key()) == null)
								file.setProperty(property);
						bundle = existing.getValueOfType(FileBundle.class);
						if (bundle == null)
							throw new ToolException("The file '" + name + "' is not held in a bundle that can be changed.");
					}
					bundle.put(file);

					String kind = file.isTextFile() ? "text" : "binary";
					if (existing == null)
						return "Added file " + name + " (" + Text.size(content.length) + ", " + kind + ").";
					return "Replaced file " + name + ": " + Text.size(existing.getValue().getRawContent().length) + " -> " +
							Text.size(content.length) + " (" + kind + ").";
				})
				.build();
	}

	@Nonnull
	private AgentTool deleteClass() {
		return AgentTool.builder("delete_class")
				.description("Remove a class from the workspace, so that it is no longer part of the exported program. " +
						"Returns a confirmation and names the classes that still refer to the removed class, since those fail " +
						"at runtime where they use it. Inner classes are separate classes and are not removed with their outer class. " +
						"A class deleted by mistake can be brought back with undo_change.")
				.schema(ToolSchema.object().string("class", "Name of the class to remove, for example 'com/example/Tracker'."))
				.mutating()
				.handler(args -> {
					Workspace workspace = context.workspace();
					ClassPathNode path = requireEditableClass(workspace, args.string("class"));
					String name = path.getValue().getName();
					Bundle<ClassInfo> bundle = classBundle(path);
					remember(workspace, bundle, name, true);
					bundle.remove(name);

					List<String> referrers = Workspaces.jvmClasses(workspace)
							.map(node -> node.getValue().asJvmClass())
							.filter(cls -> !cls.getName().equals(name) && cls.getReferencedClasses().contains(name))
							.map(ClassInfo::getName)
							.sorted()
							.collect(Collectors.toList());
					StringBuilder sb = new StringBuilder("Deleted class ").append(name).append('.');
					if (!referrers.isEmpty())
						sb.append(' ').append(count(referrers.size(), "class")).append(referrers.size() == 1 ? " still refers to it" : " still refer to it")
								.append(" and would fail at runtime where it is used: ").append(listed(referrers)).append('.');
					return sb.toString();
				})
				.build();
	}

	@Nonnull
	private AgentTool deleteFile() {
		return AgentTool.builder("delete_file")
				.description("Remove a file from the workspace, so that it is no longer part of the exported program. " +
						"Returns a confirmation. A file deleted by mistake can be brought back with undo_change.")
				.schema(ToolSchema.object().string("path", "Path of the file within the workspace, for example 'META-INF/tracking.json'."))
				.mutating()
				.handler(args -> {
					Workspace workspace = context.workspace();
					FilePathNode path = Lookups.requireFile(workspace, args.string("path"));
					String name = path.getValue().getName();
					requirePrimary(workspace, path, "file '" + name + "'");
					FileBundle bundle = path.getValueOfType(FileBundle.class);
					if (bundle == null)
						throw new ToolException("The file '" + name + "' is not held in a bundle that can be changed.");
					remember(workspace, bundle, name, false);
					bundle.remove(name);
					return "Deleted file " + name + " (" + Text.size(path.getValue().getRawContent().length) + ").";
				})
				.build();
	}

	@Nonnull
	private AgentTool undoChange() {
		return AgentTool.builder("undo_change")
				.description("Revert the most recent change made to one class or file in this session, returning it to the state " +
						"before that change. Pass all=true to discard every change and return to the original from the input. " +
						"Also restores a class or file that was removed with delete_class or delete_file. " +
						"Returns the state the class or file is in afterwards and how many earlier changes remain. " +
						"Each class and file has its own history, so this cannot reverse a rename, which touches many classes at once: " +
						"rename back with rename_class or rename_member instead.")
				.schema(ToolSchema.object()
						.optString("class", "Name of the class to revert, for example 'com/example/Main'. Pass either this or 'path'.")
						.optString("path", "Path of the file to revert, for example 'config/app.properties'.")
						.optBool("all", "Pass true to revert all changes and return to the original. Default false, which reverts one change."))
				.mutating()
				.handler(args -> {
					Workspace workspace = context.workspace();
					String className = args.optString("class", null);
					String fileName = args.optString("path", null);
					boolean isClass = className != null && !className.isBlank();
					boolean isFile = fileName != null && !fileName.isBlank();
					if (isClass == isFile)
						throw new ToolException("Pass either 'class' or 'path', exactly one of them, to say what to revert.");
					boolean all = args.optBool("all", false);

					// Something that was deleted is not in the workspace anymore, it comes back from what was kept of it.
					String key = isClass ? Lookups.internalName(className) : filePath(fileName);
					boolean present = isClass ? workspace.findClass(false, key) != null : workspace.findFile(key) != null;
					Deletion deletion = present ? null : deletion(workspace, key, isClass);
					if (deletion != null)
						return restore(deletion, all);

					Bundle<? extends Info> bundle;
					if (isClass) {
						ClassPathNode path = requireEditableClass(workspace, className);
						key = path.getValue().getName();
						bundle = classBundle(path);
					} else {
						FilePathNode path = Lookups.requireFile(workspace, fileName);
						key = path.getValue().getName();
						requirePrimary(workspace, path, "file '" + key + "'");
						bundle = path.getValueOfType(FileBundle.class);
					}
					String what = (isClass ? "class " : "file ") + key;
					if (bundle == null || !bundle.hasHistory(key)) {
						String message = "There is nothing to undo for " + what + ": it has not changed since it entered the workspace.";
						Info current = bundle == null ? null : bundle.get(key);
						if (current instanceof ClassInfo currentClass && OriginalClassNameProperty.get(currentClass) != null)
							message += " It was renamed from '" + OriginalClassNameProperty.get(currentClass) +
									"', which is not undone here: rename it back with rename_class.";
						else
							message += " To drop something that was added in this session use " + (isClass ? "delete_class." : "delete_file.");
						throw new ToolException(message);
					}

					// A rename rewrites the names in every class that uses them. Taking that back for one class alone
					// would leave it referring to names that are gone.
					Stack<? extends Info> history = bundle.getHistory(key);
					for (int i = history.size() - 1; i >= (all ? 1 : history.size() - 1); i--)
						if (history.get(i).getProperty(RemapOriginTaskProperty.KEY) != null)
							throw new ToolException("Cannot revert " + what + ": " + (all ? "its changes include" : "its latest change is") +
									" a rename rewriting the names used in it. Taking that back for this class alone would leave it " +
									"referring to names that no longer exist. Reverse the rename with rename_class or rename_member instead.");

					int reverted = 0;
					do {
						bundle.decrementHistory(key);
						reverted++;
					} while (all && bundle.hasHistory(key));
					int remaining = history.size() - 1;
					return "Reverted " + count(reverted, "change") + " to " + what + ". " + (remaining == 0 ?
							"It is back in its original state." :
							count(remaining, "earlier change") + (remaining == 1 ? " remains" : " remain") + ", call again or pass all=true to revert further.");
				})
				.build();
	}

	@Nonnull
	private AgentTool exportWorkspace() {
		return AgentTool.builder("export_workspace")
				.description("Write the workspace with all modifications made in this session to a new file in the export directory. " +
						"Changes only exist inside Recaf until they are exported. The output is the same kind of container as the input: " +
						"a JAR is written as a JAR, an APK as an APK, a single class as a class file. " +
						"Returns the absolute path and size of the written file. An exported APK is unsigned and needs to be signed before it can be installed.")
				.schema(ToolSchema.object()
						.string("file_name", "Name of the file to write inside the export directory, for example 'app-patched.jar'. " +
								"A plain name without directories.")
						.optBool("overwrite", "Pass true to replace an existing file of that name. Default false."))
				.mutating()
				.handler(args -> {
					Workspace workspace = context.workspace();
					WorkspaceResource primary = workspace.getPrimaryResource();
					String fileName = args.string("file_name").trim();
					if (fileName.isEmpty() || fileName.indexOf('/') >= 0 || fileName.indexOf('\\') >= 0 ||
							fileName.contains("..") || fileName.equals(".") || fileName.indexOf('\0') >= 0)
						throw new ToolException("'" + fileName + "' is not usable as 'file_name'. Pass a plain file name such as " +
								"'app-patched.jar': no directories, no path separators and no '..'. Exports always go to the export directory.");

					Path directory = context.exportDirectory().toAbsolutePath().normalize();
					Path target = directory.resolve(fileName).normalize();
					if (!directory.equals(target.getParent()))
						throw new ToolException("'" + fileName + "' does not name a file directly inside the export directory.");
					boolean asDirectory = primary instanceof WorkspaceDirectoryResource;
					boolean exists = Files.exists(target);
					if (exists && !args.optBool("overwrite", false))
						throw new ToolException("'" + fileName + "' already exists in the export directory. " +
								"Pass overwrite=true to replace it, or choose another file_name.");
					if (exists && Files.isDirectory(target) != asDirectory)
						throw new ToolException("'" + fileName + "' exists in the export directory as a " +
								(asDirectory ? "file" : "directory") + " and cannot be replaced by this export. Choose another file_name.");
					Files.createDirectories(directory);

					// Staged output: a failed export leaves no partial file behind and keeps the one being overwritten.
					PathWorkspaceExportConsumer.Mode mode = asDirectory ?
							PathWorkspaceExportConsumer.Mode.MIRROR_DIRECTORY : PathWorkspaceExportConsumer.Mode.ATOMIC_FILE;
					try (PathWorkspaceExportConsumer consumer = new PathWorkspaceExportConsumer(target, mode)) {
						WorkspaceExportOptions options = new WorkspaceExportOptions(WorkspaceCompressType.MATCH_ORIGINAL,
								asDirectory ? WorkspaceOutputType.DIRECTORY : WorkspaceOutputType.FILE, consumer);
						options.setBundleSupporting(false);
						options.setCreateZipDirEntries(true);
						options.create().export(workspace);
					}

					boolean apk = primary instanceof WorkspaceFileResource fileResource && fileResource.getFileInfo() instanceof ApkFileInfo;
					if (apk) // The signature sidecar of an earlier file of this name does not match the new content.
						Files.deleteIfExists(target.resolveSibling(fileName + ".idsig"));
					StringBuilder sb = new StringBuilder("Exported the workspace as ").append(containerKind(primary)).append(" to ").append(target);
					if (asDirectory) {
						try (Stream<Path> files = Files.walk(target)) {
							sb.append(" (").append(count((int) files.filter(Files::isRegularFile).count(), "file")).append(").");
						}
					} else {
						sb.append(" (").append(Text.size(Files.size(target))).append(").");
					}
					if (apk)
						sb.append(" The APK is unsigned: sign it, for example with apksigner, before installing it.");
					return sb.toString();
				})
				.build();
	}

	@Nonnull
	private AgentTool setComment() {
		return AgentTool.builder("set_comment")
				.description("Attach a comment to a class, field or method, or remove one. Use comments to write down what an " +
						"obfuscated class or member turned out to do, next to the code itself: decompile_class shows them " +
						"as documentation comments above the declaration. Comments are notes kept by Recaf for this input file. " +
						"They do not change the program and are not part of the exported file. Returns a confirmation.")
				.schema(ToolSchema.object()
						.string("class", "Name of the class, for example 'com/example/Main'.")
						.optChoice("kind", "What the comment is attached to. Leave out to comment the class itself.", "class", "field", "method")
						.optString("name", "Name of the field or method, for example 'decrypt'. Required when kind is 'field' or 'method'.")
						.optString("descriptor", "Descriptor of the field or method, for example '(Ljava/lang/String;)Ljava/lang/String;'. " +
								"Only needed when the class has several members of that name.")
						.optString("comment", "Text of the comment. Leave out or pass an empty text to remove the existing comment."))
				.mutating()
				.handler(args -> {
					Workspace workspace = context.workspace();
					ClassPathNode path = Lookups.requireClass(workspace, args.string("class"));
					ClassInfo info = path.getValue();
					ClassMember member = commentedMember(info, args);
					String comment = args.optString("comment", null);
					if (comment != null && comment.isBlank())
						comment = null;
					String target = member == null ? "class " + info.getName() :
							(member.isField() ? "field " : "method ") + info.getName() + "." + Lookups.signature(member);

					WorkspaceComments workspaceComments = context.service(CommentManager.class).getOrCreateWorkspaceComments(workspace);
					ClassComments comments = comment == null ? workspaceComments.getClassComments(path) : workspaceComments.getOrCreateClassComments(path);
					String existing = comments == null ? null : member == null ? comments.getClassComment() :
							member.isField() ? comments.getFieldComment((FieldMember) member) : comments.getMethodComment((MethodMember) member);
					if (comment == null && existing == null)
						return "There is no comment on " + target + " to remove.";
					if (member == null)
						comments.setClassComment(comment);
					else if (member.isField())
						comments.setFieldComment((FieldMember) member, comment);
					else
						comments.setMethodComment((MethodMember) member, comment);
					forgetDecompilation(info);
					if (comment == null)
						return "Removed the comment from " + target + ".";
					return (existing == null ? "Added a comment to " : "Replaced the comment on ") + target +
							". It is shown above the declaration by decompile_class.";
				})
				.build();
	}

	@Nonnull
	private AgentTool getComments() {
		return AgentTool.builder("get_comments")
				.description("List the comments attached to a class and to its fields and methods, as set with set_comment " +
						"or by the user in Recaf. Each entry names what is commented followed by the comment text.")
				.schema(ToolSchema.object().string("class", "Name of the class, for example 'com/example/Main'."))
				.handler(args -> {
					Workspace workspace = context.workspace();
					ClassPathNode path = Lookups.requireClass(workspace, args.string("class"));
					ClassInfo info = path.getValue();
					WorkspaceComments workspaceComments = context.service(CommentManager.class).getWorkspaceComments(workspace);
					ClassComments comments = workspaceComments == null ? null : workspaceComments.getClassComments(path);
					List<String> entries = new ArrayList<>();
					if (comments != null) {
						addComment(entries, "class " + info.getName(), comments.getClassComment());
						for (FieldMember field : info.getFields())
							addComment(entries, "field " + Lookups.signature(field), comments.getFieldComment(field));
						for (MethodMember method : info.getMethods())
							addComment(entries, "method " + Lookups.signature(method), comments.getMethodComment(method));
					}
					if (entries.isEmpty())
						return "No comments on class " + info.getName() + " or its members.";
					return count(entries.size(), "comment") + " in class " + info.getName() + ":\n" + String.join("\n", entries);
				})
				.build();
	}

	/**
	 * @param workspace
	 * 		Workspace to search.
	 * @param name
	 * 		Class name in any common form.
	 *
	 * @return Path to the class, which is part of the analyzed program and can be changed.
	 *
	 * @throws ToolException
	 * 		When there is no such class, or it belongs to the runtime library or a supporting library.
	 */
	@Nonnull
	static ClassPathNode requireEditableClass(@Nonnull Workspace workspace, @Nonnull String name) {
		ClassPathNode path = Lookups.requireClass(workspace, name);
		requirePrimary(workspace, path, "class '" + path.getValue().getName() + "'");
		return path;
	}

	/**
	 * @param workspace
	 * 		Workspace the path belongs to.
	 * @param path
	 * 		Path to a class or file.
	 * @param what
	 * 		Description of the content for the error message, such as {@code class 'com/example/Foo'}.
	 *
	 * @throws ToolException
	 * 		When the content is not part of the analyzed program but of a supporting library.
	 */
	static void requirePrimary(@Nonnull Workspace workspace, @Nonnull PathNode<?> path, @Nonnull String what) {
		if (path.getValueOfType(WorkspaceResource.class) != workspace.getPrimaryResource())
			throw new ToolException("The " + what + " belongs to a supporting library of the workspace, not to the analyzed program. " +
					"Only the analyzed program can be changed.");
	}

	/**
	 * @param name
	 * 		Internal class name to check.
	 *
	 * @return {@code true} when the JVM accepts the name for a class.
	 */
	static boolean isValidClassName(@Nonnull String name) {
		if (name.isEmpty() || name.startsWith("/") || name.endsWith("/") || name.contains("//"))
			return false;
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (c == '.' || c == ';' || c == '[' || c <= ' ')
				return false;
		}
		return true;
	}

	/**
	 * @param amount
	 * 		Number of things.
	 * @param noun
	 * 		Singular noun for the things, pluralized by appending {@code s} or {@code es}.
	 *
	 * @return The amount followed by the noun in the matching number, such as {@code 3 classes}.
	 */
	@Nonnull
	static String count(int amount, @Nonnull String noun) {
		return count(amount, noun, noun.endsWith("s") ? noun + "es" : noun + "s");
	}

	/**
	 * @param amount
	 * 		Number of things.
	 * @param singular
	 * 		Singular noun for the things.
	 * @param plural
	 * 		Plural noun for the things.
	 *
	 * @return The amount followed by the noun in the matching number.
	 */
	@Nonnull
	static String count(int amount, @Nonnull String singular, @Nonnull String plural) {
		return amount + " " + (amount == 1 ? singular : plural);
	}

	/**
	 * @param names
	 * 		Names to list.
	 *
	 * @return The first names separated by commas, followed by how many more there are.
	 */
	@Nonnull
	static String listed(@Nonnull List<String> names) {
		String shown = String.join(", ", names.subList(0, Math.min(MAX_LISTED, names.size())));
		return names.size() <= MAX_LISTED ? shown : shown + " and " + (names.size() - MAX_LISTED) + " more";
	}

	/**
	 * @param path
	 * 		Path to a class.
	 *
	 * @return The bundle holding the class.
	 */
	@Nonnull
	@SuppressWarnings("unchecked")
	static Bundle<ClassInfo> classBundle(@Nonnull ClassPathNode path) {
		Bundle<ClassInfo> bundle = path.getValueOfType(Bundle.class);
		if (bundle == null)
			throw new ToolException("The class '" + path.getValue().getName() + "' is not held in a bundle that can be changed.");
		return bundle;
	}

	@Nonnull
	private AssemblerPipeline<?, ?, ?> pipeline(@Nonnull Workspace workspace, boolean android) {
		AssemblerPipelineManager manager = context.service(AssemblerPipelineManager.class);
		return android ? manager.newAndroidAssemblerPipeline(workspace) : manager.newJvmAssemblerPipeline(workspace);
	}

	/**
	 * @param pipeline
	 * 		Assembler to parse with.
	 * @param source
	 * 		JASM source.
	 *
	 * @return The declarations in the source.
	 *
	 * @throws ToolException
	 * 		When the source has syntax errors.
	 */
	@Nonnull
	private static List<ASTElement> parse(@Nonnull AssemblerPipeline<?, ?, ?> pipeline, @Nonnull String source) {
		Outcome<List<Token>> tokens = pipeline.tokenize(source, SOURCE_NAME);
		if (!tokens.isSuccess())
			throw rejected(pipeline, source, tokens.diagnostics());
		Outcome<List<ASTElement>> parsed = pipeline.fullParse(tokens.requireValue());
		if (!parsed.isSuccess())
			throw rejected(pipeline, source, parsed.diagnostics());
		return parsed.requireValue();
	}

	/**
	 * @param elements
	 * 		Declarations parsed from the source.
	 * @param type
	 * 		Kind of declaration the tool takes.
	 * @param noun
	 * 		Name of that kind of declaration.
	 * @param keyword
	 * 		Keyword such a declaration starts with.
	 * @param alternative
	 * 		Advice on what to use for other kinds of declarations.
	 * @param <T>
	 * 		Kind of declaration the tool takes.
	 *
	 * @return The one declaration in the source.
	 *
	 * @throws ToolException
	 * 		When the source does not consist of exactly one declaration of that kind.
	 */
	@Nonnull
	private static <T extends ASTElement> T single(@Nonnull List<ASTElement> elements, @Nonnull Class<T> type, @Nonnull String noun,
	                                               @Nonnull String keyword, @Nonnull String alternative) {
		if (elements.size() == 1 && type.isInstance(elements.get(0)))
			return type.cast(elements.get(0));
		String found = elements.isEmpty() ? "no declaration at all" : elements.stream()
				.map(element -> element == null ? "nothing" : "a " + String.valueOf(element.type()).toLowerCase().replace('_', ' ') + " declaration")
				.collect(Collectors.joining(", "));
		throw new ToolException("The source must consist of exactly one " + noun + " declaration starting with " + keyword +
				", but it contains " + found + ". " + alternative + " " + JASM_HINT);
	}

	/**
	 * @param pipeline
	 * 		Assembler to use.
	 * @param elements
	 * 		Declarations parsed from the source.
	 * @param path
	 * 		Path to the class that is replaced, or that the declared member is assembled into.
	 * @param source
	 * 		JASM source the declarations were parsed from.
	 *
	 * @return The assembled class and the warnings raised on the way.
	 *
	 * @throws ToolException
	 * 		When the assembler rejects the source.
	 */
	@Nonnull
	private static Assembled assemble(@Nonnull AssemblerPipeline<?, ?, ?> pipeline, @Nonnull List<ASTElement> elements,
	                                  @Nonnull ClassPathNode path, @Nonnull String source) {
		Outcome<? extends ClassResult<?>> outcome = pipeline.assemble(elements, path);
		if (!outcome.isSuccess())
			throw rejected(pipeline, source, outcome.diagnostics());
		Object representation = outcome.requireValue().representation();
		if (representation == null)
			throw new ToolException("The assembler accepted the source but produced no class. Nothing was changed.");
		return new Assembled(representation, outcome.warnings());
	}

	/**
	 * @param previous
	 * 		Class that is replaced, or {@code null} for a new class.
	 * @param representation
	 * 		Class as produced by the assembler.
	 *
	 * @return The assembled class, carrying over what Recaf remembered about the replaced class.
	 */
	@Nonnull
	private static ClassInfo toClassInfo(@Nullable ClassInfo previous, @Nonnull Object representation) {
		if (representation instanceof JavaClassRepresentation jvm)
			return previous == null ? new JvmClassInfoBuilder(jvm.classFile()).build() :
					previous.asJvmClass().toJvmClassBuilder().adaptFrom(jvm.classFile()).build();
		if (representation instanceof DalvikClassRepresentation dalvik)
			return previous == null ? new AndroidClassInfoBuilder(dalvik.definition()).build() :
					previous.asAndroidClass().toAndroidBuilder().adaptFrom(dalvik.definition()).build();
		throw new IllegalStateException("Unsupported assembler output: " + representation.getClass().getName());
	}

	/**
	 * @param original
	 * 		Definition of a DEX class.
	 *
	 * @return A separate definition with the same content. Fields and methods are shared with the original, which is
	 * safe to hand to the assembler as it replaces members rather than changing them.
	 *
	 * @throws ToolException
	 * 		When the copy does not come out equal to the original.
	 */
	@Nonnull
	private static ClassDefinition copyOf(@Nonnull ClassDefinition original) {
		ClassDefinition copy = new ClassDefinition(original.getType(), original.getSuperClass(), original.getAccess());
		copy.addInterfaces(original.getInterfaces());
		copy.setSourceFile(original.getSourceFile());
		copy.setSignature(original.getSignature());
		copy.setEnclosingClass(original.getEnclosingClass());
		copy.setEnclosingMethod(original.getEnclosingMethod());
		copy.setNestHost(original.getNestHost());
		original.getInnerClasses().forEach(copy::addInnerClass);
		original.getMemberClasses().forEach(copy::addMemberClass);
		original.getNestMembers().forEach(copy::addNestMember);
		original.getPermittedSubclasses().forEach(copy::addPermittedSubclass);
		original.getRecordComponents().forEach(copy::addRecordComponent);
		original.getFields().values().forEach(copy::putField);
		original.getMethods().values().forEach(copy::putMethod);
		copy.addAnnotations(original.getAnnotations());

		// Guards against a class definition gaining content that is not carried over above.
		if (!copy.equals(original))
			throw new ToolException("The Android class '" + original.getType().internalName() + "' has content that cannot be carried over " +
					"into a changed copy, so a single method of it cannot be replaced. Replace the whole class with assemble_class instead.");
		return copy;
	}

	/**
	 * @param representation
	 * 		Class as produced by the assembler.
	 * @param method
	 * 		Method to drop from it.
	 *
	 * @return The class without the method.
	 */
	@Nonnull
	private static Object withoutMethod(@Nonnull Object representation, @Nonnull MethodMember method) {
		String name = method.getName();
		String descriptor = method.getDescriptor();
		if (representation instanceof JavaClassRepresentation jvm) {
			ClassReader reader = new ClassReader(jvm.classFile());
			ClassWriter writer = new ClassWriter(reader, 0);
			reader.accept(new ClassVisitor(RecafConstants.getAsmVersion(), writer) {
				@Override
				public MethodVisitor visitMethod(int access, String methodName, String methodDescriptor, String signature, String[] exceptions) {
					if (name.equals(methodName) && descriptor.equals(methodDescriptor))
						return null;
					return super.visitMethod(access, methodName, methodDescriptor, signature, exceptions);
				}
			}, 0);
			return new JavaClassRepresentation(writer.toByteArray());
		}
		if (representation instanceof DalvikClassRepresentation dalvik) {
			if (dalvik.definition().getMethods().remove(new MemberIdentifier(name, descriptor)) == null)
				throw new ToolException("The method " + name + descriptor + " could not be removed from the assembled class. Nothing was changed.");
			return representation;
		}
		throw new IllegalStateException("Unsupported assembler output: " + representation.getClass().getName());
	}

	/**
	 * @param pipeline
	 * 		Assembler that rejected the source.
	 * @param source
	 * 		JASM source that was rejected.
	 * @param diagnostics
	 * 		What the assembler reported.
	 *
	 * @return Error listing the assembler's diagnostics with their positions in the source.
	 */
	@Nonnull
	private static ToolException rejected(@Nonnull AssemblerPipeline<?, ?, ?> pipeline, @Nonnull String source,
	                                      @Nonnull List<Diagnostic> diagnostics) {
		List<Diagnostic> errors = diagnostics.stream()
				.filter(diagnostic -> diagnostic.severity() == Severity.ERROR)
				.collect(Collectors.toList());
		if (errors.isEmpty())
			errors = diagnostics;
		StringBuilder sb = new StringBuilder("The assembler rejected the source");
		if (!errors.isEmpty())
			sb.append(" with ").append(count(errors.size(), "error"));
		sb.append(". Nothing was changed. Correct the source and call the tool again. ").append(JASM_HINT);
		if (pipeline instanceof AndroidAssemblerPipeline)
			sb.append(" This is an Android (DEX) class, its code is written in Dalvik instructions, not in JVM instructions.");
		appendDiagnostics(sb, source, errors);
		return new ToolException(sb.toString());
	}

	private static void appendWarnings(@Nonnull StringBuilder sb, @Nonnull String source, @Nonnull List<Diagnostic> warnings) {
		if (warnings.isEmpty())
			return;
		sb.append("\nThe change was applied, but the assembler raised ").append(count(warnings.size(), "warning"))
				.append(". Warnings about verification mean the code may be rejected when the class is loaded:");
		appendDiagnostics(sb, source, warnings);
	}

	private static void appendDiagnostics(@Nonnull StringBuilder sb, @Nonnull String source, @Nonnull List<Diagnostic> diagnostics) {
		String[] lines = source.split("\r?\n", -1);
		for (int i = 0; i < Math.min(MAX_DIAGNOSTICS, diagnostics.size()); i++) {
			Diagnostic diagnostic = diagnostics.get(i);
			Location location = diagnostic.location();
			String message = Text.shorten(String.valueOf(diagnostic.message()).replaceAll("\\s*\r?\n\\s*", " "), 300);
			sb.append("\n  ");
			if (location != null && location.line() > 0) {
				sb.append("line ").append(location.line()).append(", column ").append(location.column()).append(": ").append(message);
				if (location.line() <= lines.length)
					sb.append("\n      ").append(location.line()).append(" | ").append(Text.shorten(lines[location.line() - 1].trim(), 160));
			} else {
				sb.append(message);
			}
		}
		if (diagnostics.size() > MAX_DIAGNOSTICS)
			sb.append("\n  [").append(diagnostics.size() - MAX_DIAGNOSTICS).append(" more not shown. They often follow from the first ones.]");
	}

	private static void appendMemberChanges(@Nonnull StringBuilder sb, @Nonnull String label,
	                                        @Nonnull List<? extends ClassMember> before, @Nonnull List<? extends ClassMember> after) {
		Set<String> beforeSignatures = before.stream().map(Lookups::signature).collect(Collectors.toCollection(LinkedHashSet::new));
		Set<String> afterSignatures = after.stream().map(Lookups::signature).collect(Collectors.toCollection(LinkedHashSet::new));
		List<String> added = afterSignatures.stream().filter(signature -> !beforeSignatures.contains(signature)).collect(Collectors.toList());
		List<String> removed = beforeSignatures.stream().filter(signature -> !afterSignatures.contains(signature)).collect(Collectors.toList());
		if (!added.isEmpty())
			sb.append(' ').append(label).append(" added: ").append(listed(added)).append('.');
		if (!removed.isEmpty())
			sb.append(' ').append(label).append(" removed: ").append(listed(removed)).append('.');
	}

	private static boolean holdsOnlyAndroidClasses(@Nonnull WorkspaceResource resource) {
		return resource.androidClassBundleStreamRecursive().anyMatch(bundle -> !bundle.isEmpty()) &&
				resource.jvmAllClassBundleStreamRecursive().allMatch(Bundle::isEmpty);
	}

	@Nonnull
	private static byte[] emptyClass(@Nonnull String name) {
		ClassWriter writer = new ClassWriter(0);
		writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER, name, null, "java/lang/Object", null);
		writer.visitEnd();
		return writer.toByteArray();
	}

	/**
	 * @param text
	 * 		File path as passed by the model.
	 *
	 * @return The path as used for files in the workspace.
	 *
	 * @throws ToolException
	 * 		When the path cannot name a file in an archive.
	 */
	@Nonnull
	private static String filePath(@Nonnull String text) {
		String name = text.trim().replace('\\', '/');
		while (name.startsWith("/"))
			name = name.substring(1);
		if (name.isEmpty() || name.endsWith("/"))
			throw new ToolException("'" + text + "' does not name a file. Pass a path within the workspace such as 'config/app.properties'.");
		for (String segment : name.split("/"))
			if (segment.isEmpty() || segment.equals(".") || segment.equals(".."))
				throw new ToolException("'" + text + "' is not usable as a file path within the workspace: " +
						"it must not contain empty, '.' or '..' segments.");
		return name;
	}

	/**
	 * @param resource
	 * 		Resource a new file is about to be added to.
	 * @param name
	 * 		Path of the new file.
	 *
	 * @throws ToolException
	 * 		When the path is taken by content that Recaf holds in another form than a file.
	 */
	private static void requireFreeFilePath(@Nonnull WorkspaceResource resource, @Nonnull String name) {
		if (resource.getAndroidClassBundles().containsKey(name))
			throw new ToolException("'" + name + "' is a DEX file whose classes are loaded in the workspace, it is rebuilt from them on export. " +
					"Change its classes with assemble_class and assemble_method instead of replacing the file.");
		if (resource.getEmbeddedResources().containsKey(name))
			throw new ToolException("'" + name + "' is an archive whose content is loaded in the workspace, it is rebuilt from that on export. " +
					"Change the classes and files inside it instead of replacing the archive.");
		if (name.endsWith(".class")) {
			String className = name.substring(0, name.length() - ".class".length());
			if (resource.jvmAllClassBundleStreamRecursive().anyMatch(bundle -> bundle.containsKey(className)))
				throw new ToolException("'" + name + "' is the class '" + className + "' of the workspace, not a file. " +
						"Change it with assemble_class or assemble_method.");
		}
	}

	@Nonnull
	private static byte[] decodeBase64(@Nonnull String text) {
		try {
			return Base64.getMimeDecoder().decode(text.trim());
		} catch (IllegalArgumentException ex) {
			throw new ToolException("'base64' is not valid Base64: " + ex.getMessage() + ". For text content pass 'text' instead.");
		}
	}

	@Nonnull
	private static String containerKind(@Nonnull WorkspaceResource resource) {
		if (resource instanceof WorkspaceDirectoryResource)
			return "a directory";
		if (resource instanceof WorkspaceFileResource fileResource) {
			FileInfo info = fileResource.getFileInfo();
			if (info instanceof ApkFileInfo) return "an APK";
			if (info instanceof WarFileInfo) return "a WAR";
			if (info instanceof JModFileInfo) return "a JMOD";
			if (info instanceof JarFileInfo) return "a JAR";
			if (info instanceof ZipFileInfo) return "a ZIP archive";
			if (info instanceof DexFileInfo) return "a DEX file";
			return "a single file";
		}
		return "a ZIP archive";
	}

	/**
	 * @param info
	 * 		Class the comment belongs to.
	 * @param args
	 * 		Arguments naming the commented element.
	 *
	 * @return The field or method the arguments name, or {@code null} when they name the class itself.
	 */
	@Nullable
	private static ClassMember commentedMember(@Nonnull ClassInfo info, @Nonnull ToolArgs args) {
		String kind = args.optString("kind", "class");
		String name = args.optString("name", null);
		boolean named = name != null && !name.isBlank();
		if (kind == null || kind.isBlank() || kind.equalsIgnoreCase("class")) {
			if (named)
				throw new ToolException("'name' was passed without saying what it names. Pass kind='field' or kind='method' with it.");
			return null;
		}
		if (!kind.equalsIgnoreCase("field") && !kind.equalsIgnoreCase("method"))
			throw new ToolException("Argument 'kind' must be 'class', 'field' or 'method'.");
		if (!named)
			throw new ToolException("Pass 'name' to say which " + kind.toLowerCase() + " of class '" + info.getName() + "' the comment is for.");
		String descriptor = args.optString("descriptor", null);
		return kind.equalsIgnoreCase("field") ? Lookups.requireField(info, name, descriptor) : Lookups.requireMethod(info, name, descriptor);
	}

	/**
	 * Recaf caches decompiled source with the comments in it. Dropping that cache makes the next decompilation
	 * show a comment that was just changed.
	 *
	 * @param info
	 * 		Class whose comments changed.
	 */
	private static void forgetDecompilation(@Nonnull ClassInfo info) {
		CachedDecompileProperty.remove(info);
		if (info.isAndroidClass() && info.asAndroidClass().canMapToJvmClass()) {
			try {
				// Android classes are decompiled through their JVM translation, which holds the cache.
				CachedDecompileProperty.remove(info.asAndroidClass().asJvmClass());
			} catch (IllegalStateException ignored) {
				// A class that cannot be translated has no decompiled source to refresh.
			}
		}
	}

	private static void addComment(@Nonnull List<String> entries, @Nonnull String target, @Nullable String comment) {
		if (comment != null && !comment.isBlank())
			entries.add(target + ":\n  " + comment.strip().replace("\n", "\n  "));
	}

	/**
	 * Keeps the states of a class or file that is about to be removed, so that the removal can be undone.
	 *
	 * @param workspace
	 * 		Workspace the content is removed from.
	 * @param bundle
	 * 		Bundle the content is removed from.
	 * @param key
	 * 		Name of the content.
	 * @param isClass
	 *        {@code true} for a class, {@code false} for a file.
	 */
	private void remember(@Nonnull Workspace workspace, @Nonnull Bundle<? extends Info> bundle, @Nonnull String key, boolean isClass) {
		Stack<? extends Info> history = bundle.getHistory(key);
		List<Info> states = new ArrayList<>();
		if (history != null)
			states.addAll(history);
		if (states.isEmpty())
			states.add(bundle.get(key));
		synchronized (deletions) {
			// What was removed from a workspace that is no longer open cannot come back.
			deletions.removeIf(deletion -> deletion.workspace() != workspace || (deletion.isClass() == isClass && deletion.key().equals(key)));
			deletions.add(new Deletion(workspace, bundle, key, isClass, states));
		}
	}

	/**
	 * @param workspace
	 * 		Open workspace.
	 * @param key
	 * 		Name of the content.
	 * @param isClass
	 *        {@code true} for a class, {@code false} for a file.
	 *
	 * @return What was kept of the content when it was removed from the workspace, or {@code null} when it was not
	 * removed through these tools.
	 */
	@Nullable
	private Deletion deletion(@Nonnull Workspace workspace, @Nonnull String key, boolean isClass) {
		synchronized (deletions) {
			deletions.removeIf(deletion -> deletion.workspace() != workspace);
			for (Deletion deletion : deletions)
				if (deletion.isClass() == isClass && deletion.key().equals(key))
					return deletion;
		}
		return null;
	}

	/**
	 * @param deletion
	 * 		What was kept of a removed class or file.
	 * @param original
	 *        {@code true} to bring it back in its original state rather than the one it was removed in.
	 *
	 * @return Report for the model.
	 */
	@Nonnull
	@SuppressWarnings("unchecked")
	private String restore(@Nonnull Deletion deletion, boolean original) {
		// Putting the states back one by one rebuilds the history the bundle had before the removal.
		Bundle<Info> bundle = (Bundle<Info>) deletion.bundle();
		List<Info> states = original ? deletion.states().subList(0, 1) : deletion.states();
		for (Info state : states)
			bundle.put(state);
		synchronized (deletions) {
			deletions.remove(deletion);
		}
		String what = (deletion.isClass() ? "class " : "file ") + deletion.key();
		int remaining = states.size() - 1;
		return "Restored the deleted " + what + ". " + (remaining == 0 ?
				"It is in its original state." :
				"It is in the state it was deleted in, with " + count(remaining, "earlier change") + " that can still be reverted.");
	}

	/**
	 * Output of the assembler.
	 *
	 * @param representation
	 * 		Assembled class in the form of the assembler backend.
	 * @param warnings
	 * 		Warnings raised while assembling.
	 */
	private record Assembled(@Nonnull Object representation, @Nonnull List<Diagnostic> warnings) {}

	/**
	 * A class or file removed through these tools.
	 *
	 * @param workspace
	 * 		Workspace it was removed from.
	 * @param bundle
	 * 		Bundle it was removed from.
	 * @param key
	 * 		Its name.
	 * @param isClass
	 *        {@code true} for a class, {@code false} for a file.
	 * @param states
	 * 		Its states from the original to the one it was removed in.
	 */
	private record Deletion(@Nonnull Workspace workspace, @Nonnull Bundle<? extends Info> bundle, @Nonnull String key,
	                        boolean isClass, @Nonnull List<Info> states) {}
}
