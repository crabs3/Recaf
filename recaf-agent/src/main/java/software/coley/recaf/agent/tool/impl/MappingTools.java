package software.coley.recaf.agent.tool.impl;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import me.darknet.dex.tree.remap.ClassDefinitionRemapper;
import me.darknet.dex.tree.remap.DefinitionRemapper;
import software.coley.recaf.agent.tool.AgentTool;
import software.coley.recaf.agent.tool.ToolContext;
import software.coley.recaf.agent.tool.ToolException;
import software.coley.recaf.agent.tool.ToolProvider;
import software.coley.recaf.agent.tool.ToolSchema;
import software.coley.recaf.agent.tool.support.Lookups;
import software.coley.recaf.agent.tool.support.Text;
import software.coley.recaf.info.AndroidClassInfo;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.member.ClassMember;
import software.coley.recaf.info.member.FieldMember;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.inheritance.InheritanceGraph;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.inheritance.InheritanceVertex;
import software.coley.recaf.services.mapping.IntermediateMappings;
import software.coley.recaf.services.mapping.MappingApplierService;
import software.coley.recaf.services.mapping.MappingResults;
import software.coley.recaf.services.mapping.aggregate.AggregateMappingManager;
import software.coley.recaf.services.mapping.aggregate.AggregatedMappings;
import software.coley.recaf.services.mapping.data.ClassMapping;
import software.coley.recaf.services.mapping.data.FieldMapping;
import software.coley.recaf.services.mapping.data.MethodMapping;
import software.coley.recaf.services.mapping.data.VariableMapping;
import software.coley.recaf.services.mapping.format.InvalidMappingException;
import software.coley.recaf.services.mapping.format.MappingFileFormat;
import software.coley.recaf.services.mapping.format.MappingFormatManager;
import software.coley.recaf.services.mapping.format.SimpleMappings;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static software.coley.recaf.agent.tool.impl.EditTools.count;
import static software.coley.recaf.agent.tool.impl.EditTools.listed;

/**
 * Tools for renaming classes, fields and methods.
 * <p>
 * Renaming in Recaf is mapping based: a rename is a mapping from an old name to a new one that is applied to every
 * class of the analyzed program, which rewrites the declaration together with all references to it.
 *
 * @author Recaf agent tooling
 */
public final class MappingTools implements ToolProvider {
	private static final String DEFAULT_FORMAT = SimpleMappings.NAME;
	private static final int DEFAULT_MAPPING_LINES = 400;
	private static final int MAX_PROBLEMS = 15;
	private final ToolContext context;

	/**
	 * @param context
	 * 		Context the tools operate in.
	 */
	public MappingTools(@Nonnull ToolContext context) {
		this.context = context;

		// The tracker of applied mappings only records renames in workspaces that it saw being opened. Recaf creates it
		// at startup. Asking for it here does the same in environments where services are created on first use.
		context.service(AggregateMappingManager.class).getServiceId();
	}

	@Nonnull
	@Override
	public List<AgentTool> tools() {
		return List.of(renameClass(), renameMember(), applyMappings(), exportMappings());
	}

	@Nonnull
	private AgentTool renameClass() {
		return AgentTool.builder("rename_class")
				.description("Rename a class and rewrite every reference to it in the analyzed program. " +
						"Use it to give an obfuscated class a meaningful name once you know what it does, " +
						"or to move a class to another package. Inner classes named after the class are renamed along with it. " +
						"Returns the old and new name and how many classes were updated. " +
						"From then on the class is known only by its new name. " +
						"In Android (DEX) programs renaming is refused when Recaf cannot rewrite every class, which the error then explains.")
				.schema(ToolSchema.object()
						.string("class", "Current name of the class, for example 'a/b/c' or 'a.b.c'.")
						.string("new_name", "New name of the class including its package, for example 'com/example/net/HttpClient' or " +
								"'com.example.net.HttpClient'. A name without a package, such as 'HttpClient', keeps the class in its current package."))
				.mutating()
				.handler(args -> {
					Workspace workspace = context.workspace();
					ClassPathNode path = EditTools.requireEditableClass(workspace, args.string("class"));
					String oldName = path.getValue().getName();
					String newName = Lookups.internalName(args.string("new_name"));
					if (newName.indexOf('/') < 0 && oldName.indexOf('/') >= 0)
						newName = oldName.substring(0, oldName.lastIndexOf('/') + 1) + newName;
					if (newName.equals(oldName))
						throw new ToolException("The class is already named '" + oldName + "'.");

					// Inner classes are classes of their own, named after the class they belong to.
					IntermediateMappings mappings = new IntermediateMappings();
					mappings.addClass(oldName, newName);
					String innerPrefix = oldName + "$";
					List<String> inners = workspace.getPrimaryResource().classBundleStreamRecursive()
							.flatMap(bundle -> bundle.keySet().stream())
							.filter(name -> name.startsWith(innerPrefix))
							.sorted()
							.collect(Collectors.toList());
					for (String inner : inners)
						mappings.addClass(inner, newName + inner.substring(oldName.length()));

					Applied applied = apply(workspace, mappings);
					StringBuilder sb = new StringBuilder("Renamed class ").append(oldName).append(" to ").append(newName);
					if (!inners.isEmpty())
						sb.append(", together with ").append(count(inners.size(), "inner class"));
					sb.append(". ").append(updated(applied));
					if (!oldName.substring(0, oldName.lastIndexOf('/') + 1).equals(newName.substring(0, newName.lastIndexOf('/') + 1)))
						sb.append(" Caution: the class is in another package now. Where it and classes of its old package use each other's " +
								"package-private members, that access fails at runtime.");
					return sb.toString();
				})
				.build();
	}

	@Nonnull
	private AgentTool renameMember() {
		return AgentTool.builder("rename_member")
				.description("Rename a field or method and rewrite every reference to it in the analyzed program. " +
						"Use it to give an obfuscated member a meaningful name once you know what it does. " +
						"A method that overrides or is overridden by methods in other classes of the program is renamed together with them, " +
						"so the whole family keeps working. Methods that implement or override something from the runtime or a library, " +
						"such as run() or toString(), cannot be renamed, and neither can constructors. " +
						"Returns the old and new name, which declarations were renamed and how many classes were updated.")
				.schema(ToolSchema.object()
						.string("class", "Name of the class declaring the member, for example 'com/example/Main'.")
						.choice("kind", "Whether the member is a field or a method.", "field", "method")
						.string("name", "Current name of the field or method, for example 'a'.")
						.optString("descriptor", "Descriptor of the member, for example 'I' for a field or '(Ljava/lang/String;)V' for a method. " +
								"Only needed when the class has several members of that name.")
						.string("new_name", "New name of the member, for example 'decryptString'."))
				.mutating()
				.handler(args -> {
					Workspace workspace = context.workspace();
					ClassInfo info = EditTools.requireEditableClass(workspace, args.string("class")).getValue();
					String kind = args.string("kind").trim().toLowerCase(Locale.ROOT);
					String newName = args.string("new_name").trim();
					IntermediateMappings mappings = new IntermediateMappings();
					ClassMember member;
					if (kind.equals("field")) {
						member = Lookups.requireField(info, args.string("name"), args.optString("descriptor", null));
						mappings.addField(info.getName(), member.getDescriptor(), member.getName(), newName);
					} else if (kind.equals("method")) {
						member = Lookups.requireMethod(info, args.string("name"), args.optString("descriptor", null));
						mappings.addMethod(info.getName(), member.getDescriptor(), member.getName(), newName);
					} else {
						throw new ToolException("Argument 'kind' must be 'field' or 'method'.");
					}
					if (member.getName().equals(newName))
						throw new ToolException("The " + kind + " is already named '" + newName + "'.");

					Applied applied = apply(workspace, mappings);
					StringBuilder sb = new StringBuilder("Renamed ").append(kind).append(' ').append(info.getName()).append('.')
							.append(Lookups.signature(member)).append(" to ").append(newName).append(". ");
					if (member.isMethod()) {
						List<String> owners = applied.effective().getMethods().values().stream()
								.flatMap(List::stream)
								.map(MethodMapping::getOwnerName)
								.distinct()
								.sorted()
								.collect(Collectors.toList());
						if (owners.size() > 1)
							sb.append("It is a method that classes override, so all ").append(owners.size())
									.append(" declarations in the class hierarchy were renamed: ").append(listed(owners)).append(". ");
						String missing = missingAncestor(workspace, info, (MethodMember) member);
						if (missing != null)
							sb.append("Caution: '").append(missing).append("', a super type of the class, is not in the workspace. " +
									"If the method overrides one declared there, it no longer does. ");
					}
					sb.append(updated(applied));
					return sb.toString();
				})
				.build();
	}

	@Nonnull
	private AgentTool applyMappings() {
		String formats = String.join(", ", context.service(MappingFormatManager.class).getMappingFileFormats());
		return AgentTool.builder("apply_mappings")
				.description("Rename many classes, fields and methods at once from mapping text, rewriting all references. " +
						"Much cheaper than one rename call per name: use it when you have decided on names for a whole class or package, " +
						"or to apply an existing mapping file. Supported formats: " + formats + ". " +
						"The default format '" + DEFAULT_FORMAT + "' has one rename per line, with names in internal form:\n" +
						"  a/b/c com/example/Decryptor                 (class: old name, new name)\n" +
						"  a/b/c.d Ljava/lang/String; key              (field: owner.name, descriptor, new name)\n" +
						"  a/b/c.e(Ljava/lang/String;)V decrypt        (method: owner.name(descriptor), new name)\n" +
						"Owners are always written with their current name, also when the same text renames the class. " +
						"Lines starting with '#' are comments. Entries that match nothing declared in the program are skipped and reported. " +
						"The same rules as for rename_class and rename_member apply. If any entry breaks them nothing is renamed. " +
						"Returns how many renames were applied and how many classes were updated.")
				.schema(ToolSchema.object()
						.string("mappings", "The mapping text, for example 'a/b/c com/example/Decryptor\\na/b/c.e(I)V decrypt'.")
						.optString("format", "Format of the mapping text, one of: " + formats + ". Default '" + DEFAULT_FORMAT + "'."))
				.mutating()
				.handler(args -> {
					Workspace workspace = context.workspace();
					MappingFileFormat format = format(args.optString("format", null));
					IntermediateMappings parsed;
					try {
						parsed = format.parse(args.string("mappings"));
					} catch (InvalidMappingException ex) {
						throw new ToolException("The text could not be read as '" + format.implementationName() + "' mappings: " + rootMessage(ex));
					}
					if (parsed.isEmpty())
						throw new ToolException("No renames were found in the text. In the '" + format.implementationName() + "' format " +
								"it has to hold at least one entry, see the tool description for the default format.");

					// Only entries for things the program declares are applied. An entry for a member that a class merely
					// inherits would rewrite references made through that class and leave the declaration behind.
					List<String> skipped = new ArrayList<>();
					IntermediateMappings matching = matching(workspace, parsed, skipped);
					if (matching.isEmpty())
						throw new ToolException("None of the " + count(skipped.size(), "entry", "entries") + " in the text matches a class, field or method " +
								"declared in the analyzed program, so nothing was renamed. Unmatched: " + listed(skipped) + ". " +
								"Names must be the current ones, in internal form such as 'com/example/Main', and members need their declaring class.");

					Applied applied = apply(workspace, matching);
					int classes = matching.getClasses().size();
					int fields = size(matching.getFields());
					int methods = size(matching.getMethods());
					int variables = size(matching.getVariables());
					StringBuilder sb = new StringBuilder("Applied ").append(count(classes + fields + methods + variables, "rename"))
							.append(" from the ").append(format.implementationName()).append(" mappings: ")
							.append(count(classes, "class")).append(", ").append(count(fields, "field")).append(", ").append(count(methods, "method"));
					if (variables > 0)
						sb.append(", ").append(count(variables, "local variable"));
					sb.append(". ");
					int familyMethods = size(applied.effective().getMethods()) - methods;
					if (familyMethods > 0)
						sb.append(count(familyMethods, "more method declaration")).append(" in the class hierarchy ")
								.append(familyMethods == 1 ? "was" : "were").append(" renamed along with the methods they override or implement. ");
					sb.append(updated(applied));
					if (!skipped.isEmpty())
						sb.append("\nSkipped ").append(count(skipped.size(), "entry", "entries"))
								.append(skipped.size() == 1 ? " that matches" : " that match")
								.append(" nothing declared in the analyzed program: ").append(listed(skipped)).append('.');
					return sb.toString();
				})
				.build();
	}

	@Nonnull
	private AgentTool exportMappings() {
		String formats = String.join(", ", context.service(MappingFormatManager.class).getMappingFileFormats());
		return AgentTool.builder("export_mappings")
				.description("Show every rename applied to the workspace in this session as mapping text, from the original names of the " +
						"input to the current names. Use it to review what has been renamed so far, or to keep the names so that they " +
						"can be applied to the same input again later with apply_mappings. " +
						"Supported formats: " + formats + ". Long mappings are returned in pages of lines.")
				.schema(ToolSchema.object()
						.optString("format", "Format to write the mappings in, one of: " + formats + ". Default '" + DEFAULT_FORMAT + "'.")
						.optInteger("start_line", "First line of the mapping text to return, starting at 1. Default 1.")
						.optInteger("max_lines", "Maximum number of lines to return. Default " + DEFAULT_MAPPING_LINES + "."))
				.handler(args -> {
					context.workspace();
					MappingFileFormat format = format(args.optString("format", null));
					AggregatedMappings aggregated = context.service(AggregateMappingManager.class).getAggregatedMappings();
					if (aggregated == null)
						throw new ToolException("Recaf was not tracking renames when this workspace was opened, so there is no record of them to export.");
					if (aggregated.isEmpty())
						return "No renames have been applied to this workspace yet.";
					String text;
					try {
						text = format.supportsExportText() ? format.exportText(aggregated) : null;
					} catch (InvalidMappingException ex) {
						throw new ToolException("The renames could not be written as '" + format.implementationName() + "' mappings: " +
								rootMessage(ex) + ". Try format '" + DEFAULT_FORMAT + "'.");
					}
					if (text == null)
						throw new ToolException("Recaf can read but not write the '" + format.implementationName() + "' format. " +
								"Try format '" + DEFAULT_FORMAT + "'.");
					int startLine = Math.max(1, args.optInt("start_line", 1));
					int maxLines = Math.max(1, Math.min(5000, args.optInt("max_lines", DEFAULT_MAPPING_LINES)));
					int entries = aggregated.getClasses().size() + size(aggregated.getFields()) + size(aggregated.getMethods()) + size(aggregated.getVariables());
					return count(entries, "rename") + " since the workspace was opened, as " + format.implementationName() +
							" mappings from original to current names:\n" + Text.lines(text, startLine, maxLines, false);
				})
				.build();
	}

	/**
	 * Applies mappings to the analyzed program after checking that they do not break it.
	 *
	 * @param workspace
	 * 		Workspace to rename in.
	 * @param mappings
	 * 		Renames to apply. Every entry names something the analyzed program declares.
	 *
	 * @return The applied mappings and their effect.
	 *
	 * @throws ToolException
	 * 		When a rename would clash with an existing name or detach a method from one it overrides.
	 */
	@Nonnull
	private Applied apply(@Nonnull Workspace workspace, @Nonnull IntermediateMappings mappings) {
		// Computing the result does not change the workspace yet. It also completes the mappings with the other
		// declarations of renamed methods in the class hierarchy, which is what the checks have to look at.
		MappingResults results = context.service(MappingApplierService.class).inWorkspace(workspace).applyToPrimaryResource(mappings);
		IntermediateMappings effective = results.getMappings().exportIntermediate();
		InheritanceGraph graph = context.service(InheritanceGraphService.class).getOrCreateInheritanceGraph(workspace);
		List<String> problems = problems(workspace, graph, effective);
		if (!problems.isEmpty()) {
			if (problems.size() == 1)
				throw new ToolException("Nothing was renamed. " + problems.get(0));
			StringBuilder sb = new StringBuilder("Nothing was renamed, because ").append(problems.size()).append(" of the renames are not possible:");
			for (int i = 0; i < Math.min(MAX_PROBLEMS, problems.size()); i++)
				sb.append("\n  - ").append(problems.get(i));
			if (problems.size() > MAX_PROBLEMS)
				sb.append("\n  [").append(problems.size() - MAX_PROBLEMS).append(" more not shown.]");
			sb.append("\nCorrect or remove these entries and apply the rest again.");
			throw new ToolException(sb.toString());
		}
		requireAndroidClassesRewritten(workspace, results);
		results.apply();
		return new Applied(results, effective);
	}

	/**
	 * Recaf drops an Android class from the result of a mapping when rewriting the class fails, and goes on with the
	 * rest. Applying such a result leaves references to the old names behind in the dropped classes.
	 *
	 * @param workspace
	 * 		Workspace the mappings are about to be applied to.
	 * @param results
	 * 		Computed effect of the mappings.
	 *
	 * @throws ToolException
	 * 		When Recaf could not rewrite one of the Android classes of the analyzed program.
	 */
	private static void requireAndroidClassesRewritten(@Nonnull Workspace workspace, @Nonnull MappingResults results) {
		List<AndroidClassInfo> pending = workspace.getPrimaryResource().androidClassBundleStreamRecursive()
				.flatMap(bundle -> bundle.valuesAsCopy().stream())
				.filter(cls -> !results.wasMapped(cls.getName()))
				.collect(Collectors.toList());
		if (pending.isEmpty())
			return;

		// A class is also missing from the result when no mapping applies to it. Rewriting it without any mappings,
		// the way Recaf does with them, tells the two cases apart.
		ClassDefinitionRemapper rewriter = new ClassDefinitionRemapper(new DefinitionRemapper() {});
		List<String> failed = new ArrayList<>();
		String reason = null;
		for (AndroidClassInfo cls : pending) {
			try {
				rewriter.remap(cls.getBackingDefinition());
			} catch (RuntimeException ex) {
				failed.add(cls.getName());
				if (reason == null)
					reason = ex.getClass().getSimpleName() + (ex.getMessage() == null ? "" : ": " + ex.getMessage());
			}
		}
		if (failed.isEmpty())
			return;
		failed.sort(null);
		long total = workspace.getPrimaryResource().androidClassBundleStreamRecursive().mapToLong(Map::size).sum();
		throw new ToolException("Nothing was renamed. Recaf fails to rewrite " + failed.size() + " of the " + total +
				" Android (DEX) classes of this program (" + reason + "): " + listed(failed) + ". References to the renamed names inside " +
				"those classes would keep the old names and the program would no longer fit together, so renaming is not possible " +
				"in this workspace. Changing code with assemble_method and assemble_class is not affected.");
	}

	/**
	 * @param workspace
	 * 		Workspace the mappings are about to be applied to.
	 * @param graph
	 * 		Inheritance graph of the workspace, to also find renames that change which methods override each other or
	 * 		that hide fields. {@code null} to only look for renames that would damage classes: names that are not
	 * 		valid and names that are taken.
	 * @param effective
	 * 		Mappings as they will be applied, including the method declarations renamed along with the requested ones.
	 *
	 * @return Reasons why the mappings cannot be applied, written for the model. Empty when they can.
	 */
	@Nonnull
	static List<String> problems(@Nonnull Workspace workspace, @Nullable InheritanceGraph graph, @Nonnull IntermediateMappings effective) {
		boolean strict = graph != null;
		List<String> problems = new ArrayList<>();

		Map<String, String> classTargets = new HashMap<>();
		for (ClassMapping mapping : sorted(effective.getClasses().values(), ClassMapping::getOldName)) {
			String oldName = mapping.getOldName();
			String newName = mapping.getNewName();
			if (programClass(workspace, oldName) == null)
				continue;
			String other = classTargets.putIfAbsent(newName, oldName);
			if (!EditTools.isValidClassName(newName))
				problems.add("'" + newName + "' is not a valid class name (wanted for class '" + oldName + "'). " +
						"Use the internal form, for example 'com/example/Main'.");
			else if (other != null)
				problems.add("The classes '" + other + "' and '" + oldName + "' would both be named '" + newName + "'.");
			else if (workspace.findClass(true, newName) != null)
				// Also when that class is renamed by the same mappings: Recaf moves classes one after the other,
				// and the second move would remove what the first one put in its place.
				problems.add("A class named '" + newName + "' already exists, so class '" + oldName + "' cannot take that name." +
						(strict && effective.getClasses().containsKey(newName) ? " To free the name, rename '" + newName + "' first in a call of its own." : ""));
		}

		Set<String> memberTargets = new TreeSet<>();
		for (FieldMapping mapping : sorted(flatten(effective.getFields()), FieldMapping::getOwnerName)) {
			ClassInfo owner = programClass(workspace, mapping.getOwnerName());
			FieldMember field = owner == null ? null : field(owner, mapping.getOldName(), mapping.getDesc());
			if (field == null)
				continue;
			String newName = mapping.getNewName();
			String what = "field '" + owner.getName() + "." + field.getName() + "'";
			if (!isValidMemberName(newName, false)) {
				problems.add("'" + newName + "' is not a valid field name (wanted for " + what + ").");
				continue;
			}
			// The JVM tells fields of the same name apart by their type, Java source and its readers do not.
			if (!memberTargets.add(owner.getName() + "." + newName + (strict ? "" : " " + field.getDescriptor()))) {
				problems.add("Several fields of class '" + owner.getName() + "' would be named '" + newName + "'.");
				continue;
			}
			FieldMember clash = owner.getFields().stream()
					.filter(other -> other != field && other.getName().equals(newName) &&
							(strict || other.getDescriptor().equals(field.getDescriptor())) &&
							effective.getMappedFieldName(owner.getName(), other.getName(), other.getDescriptor()) == null)
					.findFirst().orElse(null);
			InheritanceVertex vertex = strict ? graph.getVertex(owner.getName()) : null;
			String relative = clash != null || vertex == null || field.hasPrivateModifier() ? null : relative(vertex,
					parent -> declaresVisibleField(parent, newName, field.getDescriptor()),
					child -> child.hasField(newName, field.getDescriptor()));
			if (clash != null)
				problems.add("Class '" + owner.getName() + "' already declares a field named '" + newName + "' (" +
						clash.getDescriptor() + "), so " + what + " cannot take that name.");
			else if (relative != null)
				problems.add("'" + relative + "', which is in the same class hierarchy as '" + owner.getName() + "', already declares a field '" +
						newName + " " + field.getDescriptor() + "'. Giving " + what + " the same name would make references to them ambiguous.");
		}

		for (MethodMapping mapping : sorted(flatten(effective.getMethods()), MethodMapping::getOwnerName)) {
			ClassInfo owner = programClass(workspace, mapping.getOwnerName());
			MethodMember method = owner == null ? null : owner.getDeclaredMethod(mapping.getOldName(), mapping.getDesc());
			if (method == null)
				continue;
			String newName = mapping.getNewName();
			String descriptor = method.getDescriptor();
			String what = "method '" + owner.getName() + "." + method.getName() + descriptor + "'";
			if (method.getName().startsWith("<")) {
				problems.add("The " + what + " is a constructor or static initializer, and those cannot be renamed.");
				continue;
			}
			if (!isValidMemberName(newName, true)) {
				problems.add("'" + newName + "' is not a valid method name (wanted for " + what + ").");
				continue;
			}
			if (!memberTargets.add(owner.getName() + "." + newName + descriptor)) {
				problems.add("Several methods of class '" + owner.getName() + "' would be named '" + newName + descriptor + "'.");
				continue;
			}
			boolean virtual = !method.hasPrivateModifier() && !method.hasStaticModifier();
			InheritanceVertex vertex = strict ? graph.getVertex(owner.getName()) : null;
			String library = !virtual || vertex == null ? null : vertex.allParents()
					.filter(parent -> parent.isLibraryVertex() && parent.hasMethod(method.getName(), descriptor))
					.map(InheritanceVertex::getName)
					.findFirst().orElse(null);
			if (library != null) {
				problems.add("The " + what + " overrides or implements '" + library + "." + method.getName() + "', which is outside " +
						"the analyzed program and keeps its name. Renaming the method would break that link, so it has to keep its name.");
				continue;
			}
			MethodMember clash = owner.getDeclaredMethod(newName, descriptor);
			if (clash != null && effective.getMappedMethodName(owner.getName(), newName, descriptor) != null)
				clash = null;
			Predicate<InheritanceVertex> declaresKept = relative -> relative.hasMethod(newName, descriptor) &&
					effective.getMappedMethodName(relative.getName(), newName, descriptor) == null;
			String relative = clash != null || !virtual || vertex == null ? null : relative(vertex, declaresKept, declaresKept);
			if (clash != null)
				problems.add("Class '" + owner.getName() + "' already declares a method '" + newName + descriptor + "', so " + what +
						" cannot take that name.");
			else if (relative != null)
				problems.add("'" + relative + "', which is in the same class hierarchy as '" + owner.getName() + "', already declares a method '" +
						newName + descriptor + "'. Giving " + what + " the same name would make one override the other.");
		}
		return problems;
	}

	/**
	 * @param workspace
	 * 		Workspace the mappings are for.
	 * @param parsed
	 * 		Mappings as read from text.
	 * @param skipped
	 * 		List to add descriptions of the entries to that match nothing the analyzed program declares.
	 *
	 * @return The entries that name a class, field, method or variable the analyzed program declares.
	 */
	@Nonnull
	private static IntermediateMappings matching(@Nonnull Workspace workspace, @Nonnull IntermediateMappings parsed, @Nonnull List<String> skipped) {
		IntermediateMappings matching = new IntermediateMappings();
		for (ClassMapping mapping : sorted(parsed.getClasses().values(), ClassMapping::getOldName)) {
			if (programClass(workspace, mapping.getOldName()) != null)
				matching.addClass(mapping.getOldName(), mapping.getNewName());
			else
				skipped.add("class " + mapping.getOldName());
		}
		for (FieldMapping mapping : sorted(flatten(parsed.getFields()), FieldMapping::getOwnerName)) {
			ClassInfo owner = programClass(workspace, mapping.getOwnerName());
			if (owner != null && field(owner, mapping.getOldName(), mapping.getDesc()) != null)
				matching.addField(mapping.getOwnerName(), mapping.getDesc(), mapping.getOldName(), mapping.getNewName());
			else
				skipped.add("field " + mapping.getOwnerName() + "." + mapping.getOldName());
		}
		for (MethodMapping mapping : sorted(flatten(parsed.getMethods()), MethodMapping::getOwnerName)) {
			ClassInfo owner = programClass(workspace, mapping.getOwnerName());
			if (owner != null && owner.getDeclaredMethod(mapping.getOldName(), mapping.getDesc()) != null)
				matching.addMethod(mapping.getOwnerName(), mapping.getDesc(), mapping.getOldName(), mapping.getNewName());
			else
				skipped.add("method " + mapping.getOwnerName() + "." + mapping.getOldName() + mapping.getDesc());
		}
		for (VariableMapping mapping : sorted(flatten(parsed.getVariables()), VariableMapping::getOwnerName)) {
			ClassInfo owner = programClass(workspace, mapping.getOwnerName());
			if (owner != null && owner.getDeclaredMethod(mapping.getMethodName(), mapping.getMethodDesc()) != null)
				matching.addVariable(mapping.getOwnerName(), mapping.getMethodName(), mapping.getMethodDesc(), mapping.getDesc(),
						mapping.getOldName(), mapping.getIndex(), mapping.getNewName());
			else
				skipped.add("variable " + mapping.getOldName() + " of " + mapping.getOwnerName() + "." + mapping.getMethodName() + mapping.getMethodDesc());
		}
		return matching;
	}

	/**
	 * @param name
	 * 		Format name as passed by the model, or {@code null} for the default format.
	 *
	 * @return The mapping format of that name.
	 *
	 * @throws ToolException
	 * 		When there is no such format.
	 */
	@Nonnull
	private MappingFileFormat format(@Nullable String name) {
		MappingFormatManager manager = context.service(MappingFormatManager.class);
		String wanted = normalize(name == null || name.isBlank() ? DEFAULT_FORMAT : name);
		String match = null;
		for (String candidate : manager.getMappingFileFormats())
			if (normalize(candidate).equals(wanted))
				match = candidate;
		if (match == null) {
			List<String> partial = manager.getMappingFileFormats().stream()
					.filter(candidate -> normalize(candidate).startsWith(wanted))
					.collect(Collectors.toList());
			if (partial.size() == 1)
				match = partial.get(0);
		}
		MappingFileFormat format = match == null ? null : manager.createFormatInstance(match);
		if (format == null)
			throw new ToolException("There is no mapping format named '" + name + "'. Supported formats: " +
					String.join(", ", manager.getMappingFileFormats()) + ".");
		return format;
	}

	/**
	 * @param workspace
	 * 		Workspace the class is in.
	 * @param info
	 * 		Class declaring the method.
	 * @param method
	 * 		Method that was renamed.
	 *
	 * @return Name of a super type of the class that the workspace does not contain, which may declare the method
	 * without Recaf knowing. {@code null} when the method cannot override anything or all super types are known.
	 */
	@Nullable
	private String missingAncestor(@Nonnull Workspace workspace, @Nonnull ClassInfo info, @Nonnull MethodMember method) {
		if (method.hasPrivateModifier() || method.hasStaticModifier())
			return null;
		InheritanceGraph graph = context.service(InheritanceGraphService.class).getOrCreateInheritanceGraph(workspace);
		InheritanceVertex vertex = graph.getVertex(info.getName());
		if (vertex == null)
			return null;
		return Stream.concat(Stream.of(vertex), vertex.allParents())
				.flatMap(known -> known.getParentNames().stream())
				.filter(parent -> workspace.findClass(true, parent) == null)
				.findFirst().orElse(null);
	}

	@Nonnull
	private static String updated(@Nonnull Applied applied) {
		int classes = applied.results().getMappedClasses().size();
		return "Updated " + count(classes, "class") + (classes == 1 ? "." : " in total, counting those whose references were rewritten.");
	}

	/**
	 * @param workspace
	 * 		Workspace to search.
	 * @param name
	 * 		Internal class name.
	 *
	 * @return The class of that name if the analyzed program declares it, otherwise {@code null}.
	 */
	@Nullable
	private static ClassInfo programClass(@Nonnull Workspace workspace, @Nonnull String name) {
		ClassPathNode path = workspace.findClass(false, name);
		if (path == null || path.getValueOfType(WorkspaceResource.class) != workspace.getPrimaryResource())
			return null;
		return path.getValue();
	}

	/**
	 * @param owner
	 * 		Class to search.
	 * @param name
	 * 		Field name.
	 * @param descriptor
	 * 		Field descriptor, or {@code null} for mapping formats that do not record it.
	 *
	 * @return The field the class declares, or {@code null} if it declares none.
	 */
	@Nullable
	private static FieldMember field(@Nonnull ClassInfo owner, @Nonnull String name, @Nullable String descriptor) {
		if (descriptor != null)
			return owner.getDeclaredField(name, descriptor);
		return owner.getFields().stream().filter(field -> field.getName().equals(name)).findFirst().orElse(null);
	}

	/**
	 * @param vertex
	 * 		Class to look around.
	 * @param inParent
	 * 		Check for the classes it extends and implements.
	 * @param inChild
	 * 		Check for the classes extending and implementing it.
	 *
	 * @return Name of the first related class passing its check, or {@code null} if there is none.
	 */
	@Nullable
	private static String relative(@Nonnull InheritanceVertex vertex, @Nonnull Predicate<InheritanceVertex> inParent,
	                               @Nonnull Predicate<InheritanceVertex> inChild) {
		return Stream.concat(vertex.allParents().filter(inParent), vertex.allChildren().filter(inChild))
				.map(InheritanceVertex::getName)
				.findFirst().orElse(null);
	}

	private static boolean declaresVisibleField(@Nonnull InheritanceVertex vertex, @Nonnull String name, @Nonnull String descriptor) {
		FieldMember field = vertex.getValue().getDeclaredField(name, descriptor);
		return field != null && !field.hasPrivateModifier();
	}

	private static boolean isValidMemberName(@Nonnull String name, boolean method) {
		if (name.isEmpty())
			return false;
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (c == '.' || c == ';' || c == '[' || c == '/' || c <= ' ' || (method && (c == '<' || c == '>')))
				return false;
		}
		return true;
	}

	@Nonnull
	private static String normalize(@Nonnull String formatName) {
		return formatName.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
	}

	@Nonnull
	private static String rootMessage(@Nonnull Throwable t) {
		Throwable root = t;
		while (root.getCause() != null && root.getCause() != root)
			root = root.getCause();
		return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
	}

	private static int size(@Nonnull Map<String, ? extends List<?>> entries) {
		return entries.values().stream().mapToInt(List::size).sum();
	}

	@Nonnull
	private static <T> List<T> flatten(@Nonnull Map<String, List<T>> entries) {
		return entries.values().stream().flatMap(List::stream).collect(Collectors.toList());
	}

	@Nonnull
	private static <T> List<T> sorted(@Nonnull Collection<T> entries, @Nonnull Function<T, String> key) {
		List<T> list = new ArrayList<>(entries);
		list.sort(Comparator.comparing(key));
		return list;
	}

	/**
	 * Mappings that were applied to the workspace.
	 *
	 * @param results
	 * 		Effect of the mappings on the classes of the workspace.
	 * @param effective
	 * 		The mappings as applied, including the method declarations renamed along with the requested ones.
	 */
	private record Applied(@Nonnull MappingResults results, @Nonnull IntermediateMappings effective) {}
}
