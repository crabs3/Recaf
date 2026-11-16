package software.coley.recaf.agent.tool.impl;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import jakarta.annotation.Nonnull;
import me.darknet.assembler.error.Diagnostic;
import me.darknet.assembler.error.Outcome;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
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
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.builder.JvmClassInfoBuilder;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.path.DirectoryPathNode;
import software.coley.recaf.path.PathNode;
import software.coley.recaf.services.assembler.AssemblerPipeline;
import software.coley.recaf.services.assembler.AssemblerPipelineManager;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.decompile.JvmDecompiler;
import software.coley.recaf.services.inheritance.InheritanceGraph;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.inheritance.InheritanceVertex;
import software.coley.recaf.util.visitors.MemberFilteringVisitor;
import software.coley.recaf.util.visitors.MemberPredicate;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.Bundle;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Stack;
import java.util.stream.Collectors;

/**
 * Tools for reading the code of classes and methods, as bytecode and as source, and for relating classes to
 * each other and to their original state.
 *
 * @author Recaf agent tooling
 */
public final class CodeTools implements ToolProvider {
	private static final int DEFAULT_LINES = 400;
	private static final int MAX_LINES = 2000;
	private static final int DIFF_CONTEXT_LINES = 3;
	private final ToolContext context;

	/**
	 * @param context
	 * 		Context the tools operate in.
	 */
	public CodeTools(@Nonnull ToolContext context) {
		this.context = context;
	}

	@Nonnull
	@Override
	public List<AgentTool> tools() {
		return List.of(disassembleClass(), disassembleMethod(), decompileMethod(), getInheritance(), classDiff());
	}

	@Nonnull
	private AgentTool disassembleClass() {
		return AgentTool.builder("disassemble_class")
				.description("Show a whole class as bytecode in JASM, the syntax of Recaf's assembler: the class header, " +
						"every field, and every method with its instructions. JVM classes are shown in JVM instructions, " +
						"Android classes in the Dalvik instructions of their DEX file. " +
						"Bytecode is the ground truth: use it when decompiled source looks wrong or incomplete, " +
						"which is common with obfuscated code. The text is in the form the assembler accepts back for edits. " +
						"Long classes are returned in pages of lines. To read a single method use disassemble_method.")
				.schema(pagedByLines(ToolSchema.object()
						.string("class", "Name of the class, for example 'com/example/Main' or 'com.example.Main'.")))
				.handler(args -> {
					Workspace workspace = context.workspace();
					ClassPathNode path = Lookups.requireClass(workspace, args.string("class"));
					return lines(args, disassemble(path, "class '" + path.getValue().getName() + "'"));
				})
				.build();
	}

	@Nonnull
	private AgentTool disassembleMethod() {
		return AgentTool.builder("disassemble_method")
				.description("Show one method as bytecode in JASM, the syntax of Recaf's assembler: its modifiers, " +
						"parameters, exception handlers and instructions, with labels and local variable names. " +
						"JVM methods are shown in JVM instructions, Android methods in Dalvik instructions. " +
						"Use it to see precisely what a method does when its decompiled source is wrong, incomplete or missing. " +
						"The text is in the form the assembler accepts back for edits.")
				.schema(pagedByLines(methodSchema()))
				.handler(args -> {
					Workspace workspace = context.workspace();
					ClassPathNode path = Lookups.requireClass(workspace, args.string("class"));
					MethodMember method = Lookups.requireMethod(path.getValue(), args.string("method"), args.optString("descriptor", null));
					return lines(args, disassemble(path.child(method),
							"method '" + Lookups.signature(method) + "' of '" + path.getValue().getName() + "'"));
				})
				.build();
	}

	@Nonnull
	private AgentTool decompileMethod() {
		return AgentTool.builder("decompile_method")
				.description("Decompile a single method to Java source. The result is the class declaration with its fields " +
						"and only the requested method, all other methods are left out. " +
						"Much cheaper than decompile_class for large classes, so prefer it once you know which method matters. " +
						"The bodies of lambdas the method uses are included. Android methods are translated from DEX first. " +
						"If the output looks wrong, try another decompiler or read the bytecode with disassemble_method.")
				.schema(pagedByLines(methodSchema()
						.optString("decompiler", "Decompiler to use: CFR, Procyon or Vineflower. Defaults to the configured one.")))
				.handler(args -> {
					Workspace workspace = context.workspace();
					ClassInfo info = Lookups.requireClass(workspace, args.string("class")).getValue();
					MethodMember method = Lookups.requireMethod(info, args.string("method"), args.optString("descriptor", null));
					DecompilerManager manager = context.service(DecompilerManager.class);
					JvmDecompiler decompiler = ClassTools.pickDecompiler(manager, args.optString("decompiler", null));
					JvmClassInfo jvmClass = Lookups.jvmView(info);
					Set<String> kept = withLambdaBodies(jvmClass, method);
					String source = ClassTools.decompile(manager, decompiler, workspace, keepMethods(jvmClass, kept));
					int others = jvmClass.getMethods().size() - kept.size();
					return "// " + info.getName() + "." + Lookups.signature(method) + " decompiled with " + decompiler.getName() +
							(others <= 0 ? "" : others == 1 ? ". The other method of the class is left out." :
									". The other " + others + " methods of the class are left out.") + "\n" +
							lines(args, source);
				})
				.build();
	}

	@Nonnull
	private AgentTool getInheritance() {
		return AgentTool.builder("get_inheritance")
				.description("Show where a class sits in the type hierarchy: its chain of super classes, every interface it " +
						"implements directly or through a super type, and the classes of the workspace that extend or implement it. " +
						"Works for classes of the runtime library too, so it answers questions like 'what implements java/lang/Runnable' " +
						"or 'what extends android/app/Activity'. By default only direct subtypes are listed, " +
						"pass transitive=true for subtypes at any distance. The subtypes are paged.")
				.schema(ToolSchema.object()
						.string("class", "Name of the class or interface, for example 'com/example/Handler' or 'java.lang.Runnable'.")
						.optBool("transitive", "Pass true to list subtypes at any distance instead of only direct ones. Default false.")
						.paging(100))
				.handler(args -> {
					Workspace workspace = context.workspace();
					ClassInfo info = Lookups.requireClass(workspace, args.string("class"), true).getValue();
					boolean transitive = args.optBool("transitive", false);
					InheritanceGraph graph = context.service(InheritanceGraphService.class).getOrCreateInheritanceGraph(workspace);
					int offset = args.offset();
					StringBuilder sb = new StringBuilder();

					// The part about the class itself is only shown with the first page of subtypes.
					if (offset == 0) {
						sb.append(WorkspaceTools.kind(info)).append(' ').append(info.getName()).append(origin(workspace, info.getName())).append('\n');
						sb.append("Super classes: ").append(superChain(graph, info)).append('\n');
						Set<String> direct = new LinkedHashSet<>(info.getInterfaces());
						Set<String> inherited = inheritedInterfaces(graph, info);
						inherited.removeAll(direct);
						sb.append("Interfaces, direct: ").append(direct.isEmpty() ? "none" : String.join(", ", direct)).append('\n');
						sb.append("Interfaces, through super types: ").append(inherited.isEmpty() ? "none" : String.join(", ", inherited)).append('\n');
					}

					InheritanceVertex vertex = graph.getVertex(info.getName());
					List<ClassInfo> subtypes = vertex == null ? new ArrayList<>() :
							(transitive ? vertex.getAllChildren() : vertex.getChildren()).stream()
									.map(InheritanceVertex::getValue)
									.sorted(Comparator.comparing(ClassInfo::getName))
									.collect(Collectors.toList());
					String noun = transitive ? "subtypes at any distance" : "direct subtypes";
					sb.append(Text.page(subtypes, offset, args.limit(100, 500), noun, subtype ->
							subtype.getName() + "  [" + WorkspaceTools.kind(subtype) + "]" + origin(workspace, subtype.getName())));
					return sb.toString();
				})
				.build();
	}

	@Nonnull
	private AgentTool classDiff() {
		return AgentTool.builder("class_diff")
				.description("Show what changed in a class that was modified in this session, as a unified diff between its " +
						"original state and its current state. Lines starting with '-' are from the original, lines starting " +
						"with '+' from the current class. Use it to verify that an edit did what was intended and nothing else. " +
						"list_modified shows which classes were modified.")
				.schema(pagedByLines(ToolSchema.object()
						.string("class", "Name of the modified class, for example 'com/example/Main' or 'com.example.Main'.")
						.optChoice("view", "What to compare: 'decompile' for decompiled Java source (default), " +
										"'disassemble' for bytecode in JASM, which shows every change including those " +
										"a decompiler hides.",
								"decompile", "disassemble")
						.optString("decompiler", "Decompiler for the 'decompile' view: CFR, Procyon or Vineflower. Defaults to the configured one.")))
				.handler(args -> {
					Workspace workspace = context.workspace();
					ClassPathNode path = Lookups.requireClass(workspace, args.string("class"));
					ClassInfo current = path.getValue();
					String name = current.getName();
					Bundle<?> bundle = path.getValueOfType(Bundle.class);
					Stack<?> history = bundle == null ? null : bundle.getHistory(name);
					if (history == null || history.size() < 2)
						throw new ToolException("Class '" + name + "' was not modified in this session, there is nothing to compare. " +
								"list_modified shows what was changed.");
					ClassInfo original = (ClassInfo) history.firstElement();

					String view = args.optString("view", "decompile").trim().toLowerCase();
					String originalText;
					String currentText;
					String viewLabel;
					switch (view) {
						case "decompile" -> {
							DecompilerManager manager = context.service(DecompilerManager.class);
							JvmDecompiler decompiler = ClassTools.pickDecompiler(manager, args.optString("decompiler", null));
							originalText = ClassTools.decompile(manager, decompiler, workspace, Lookups.jvmView(original));
							currentText = ClassTools.decompile(manager, decompiler, workspace, Lookups.jvmView(current));
							viewLabel = "source decompiled with " + decompiler.getName();
						}
						case "disassemble" -> {
							DirectoryPathNode parent = path.getParent();
							ClassPathNode originalPath = parent == null ? new ClassPathNode(original) : parent.child(original);
							originalText = disassemble(originalPath, "the original state of class '" + name + "'");
							currentText = disassemble(path, "class '" + name + "'");
							viewLabel = "disassembled bytecode";
						}
						default -> throw new ToolException("Unknown view '" + view + "'. Use 'decompile' or 'disassemble'.");
					}

					List<String> originalLines = splitLines(originalText);
					Patch<String> patch = DiffUtils.diff(originalLines, splitLines(currentText));
					if (patch.getDeltas().isEmpty())
						return "Class '" + name + "' was modified, but its " + viewLabel +
								" is the same before and after." + (view.equals("decompile") ?
								" The change may be one a decompiler does not show, compare with view='disassemble'." :
								" The edits may have cancelled each other out.");
					List<String> diff = UnifiedDiffUtils.generateUnifiedDiff(name + " (original)", name + " (current)",
							originalLines, patch, DIFF_CONTEXT_LINES);
					return "Changes to the " + viewLabel + " of '" + name + "':\n" + lines(args, String.join("\n", diff));
				})
				.build();
	}

	/**
	 * @return Schema with the arguments that identify a method.
	 */
	@Nonnull
	private static ToolSchema methodSchema() {
		return ToolSchema.object()
				.string("class", "Name of the class declaring the method, for example 'com/example/Main' or 'com.example.Main'.")
				.string("method", "Name of the method, for example 'decrypt'. Use '<init>' for constructors and '<clinit>' for the static initializer.")
				.optString("descriptor", "Descriptor of the method, for example '(Ljava/lang/String;I)V'. " +
						"Only needed when the class has several methods of that name.");
	}

	/**
	 * @param schema
	 * 		Schema to extend.
	 *
	 * @return The schema, with the arguments for paging text by lines added.
	 */
	@Nonnull
	private static ToolSchema pagedByLines(@Nonnull ToolSchema schema) {
		return schema
				.optInteger("start_line", "First line of the text to return, starting at 1. Default 1.")
				.optInteger("max_lines", "Maximum number of lines to return. Default " + DEFAULT_LINES + ".");
	}

	/**
	 * @param args
	 * 		Arguments holding the paging values.
	 * @param text
	 * 		Full text.
	 *
	 * @return The requested lines of the text, otherwise unchanged.
	 */
	@Nonnull
	private static String lines(@Nonnull ToolArgs args, @Nonnull String text) {
		int startLine = Math.max(1, args.optInt("start_line", 1));
		int maxLines = Math.max(1, Math.min(MAX_LINES, args.optInt("max_lines", DEFAULT_LINES)));
		return Text.lines(text, startLine, maxLines, false);
	}

	@Nonnull
	private static List<String> splitLines(@Nonnull String text) {
		return Arrays.asList(text.split("\r?\n"));
	}

	/**
	 * @param path
	 * 		Path to a class or a member of a class.
	 * @param what
	 * 		Description of what the path points to, for error messages.
	 *
	 * @return Text of the item in the syntax of the assembler that handles the kind of class.
	 *
	 * @throws ToolException
	 * 		When the assembler cannot print the item.
	 */
	@Nonnull
	private String disassemble(@Nonnull PathNode<?> path, @Nonnull String what) {
		AssemblerPipeline<?, ?, ?> pipeline = context.service(AssemblerPipelineManager.class).getPipeline(path);
		Outcome<String> outcome;
		// A pipeline is shared by everything that works on the workspace and keeps the state of its printer.
		synchronized (pipeline) {
			outcome = pipeline.disassemble(path);
		}
		if (!outcome.hasValue())
			throw new ToolException("The assembler could not disassemble " + what + ": " +
					outcome.diagnostics().stream().map(Diagnostic::message).collect(Collectors.joining("; ")) +
					". Try decompile_class or get_class_info instead.");
		return outcome.requireValue();
	}

	/**
	 * @param jvmClass
	 * 		Class to reduce.
	 * @param kept
	 * 		Keys, name followed by descriptor, of the methods to keep.
	 *
	 * @return Copy of the class that keeps its fields and only the given methods, so that decompiling it yields
	 * little more than those methods.
	 */
	@Nonnull
	private static JvmClassInfo keepMethods(@Nonnull JvmClassInfo jvmClass, @Nonnull Set<String> kept) {
		ClassWriter writer = new ClassWriter(0);
		jvmClass.getClassReader().accept(new MemberFilteringVisitor(writer, new MemberPredicate() {
			@Override
			public boolean matchField(int access, String name, String desc, String sig, Object value) {
				return true;
			}

			@Override
			public boolean matchMethod(int access, String name, String desc, String sig, String[] exceptions) {
				return kept.contains(name + desc);
			}
		}), jvmClass.getClassReaderFlags());
		return new JvmClassInfoBuilder(writer.toByteArray()).build();
	}

	/**
	 * Compilers move the bodies of lambdas into synthetic methods of the same class. A decompiler can only show a
	 * lambda in place when it is given the synthetic method along with the method that uses it.
	 *
	 * @param jvmClass
	 * 		Class declaring the method.
	 * @param method
	 * 		Method to start from.
	 *
	 * @return Keys, name followed by descriptor, of the method and of the synthetic methods of the class that it
	 * refers to as lambda bodies, directly or through other lambda bodies.
	 */
	@Nonnull
	private static Set<String> withLambdaBodies(@Nonnull JvmClassInfo jvmClass, @Nonnull MethodMember method) {
		String className = jvmClass.getName();
		Set<String> synthetic = new HashSet<>();
		Map<String, Set<String>> handles = new HashMap<>();
		int api = RecafConstants.getAsmVersion();
		jvmClass.getClassReader().accept(new ClassVisitor(api) {
			@Override
			public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] exceptions) {
				String key = name + desc;
				if ((access & Opcodes.ACC_SYNTHETIC) != 0)
					synthetic.add(key);
				return new MethodVisitor(api) {
					@Override
					public void visitInvokeDynamicInsn(String indyName, String indyDesc, Handle bootstrap, Object... bootstrapArgs) {
						for (Object arg : bootstrapArgs)
							if (arg instanceof Handle handle && handle.getOwner().equals(className))
								handles.computeIfAbsent(key, k -> new HashSet<>()).add(handle.getName() + handle.getDesc());
					}
				};
			}
		}, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

		Set<String> kept = new HashSet<>();
		Deque<String> pending = new ArrayDeque<>();
		pending.add(method.getName() + method.getDescriptor());
		while (!pending.isEmpty()) {
			String key = pending.remove();
			if (kept.add(key))
				for (String handle : handles.getOrDefault(key, Set.of()))
					if (synthetic.contains(handle))
						pending.add(handle);
		}
		return kept;
	}

	/**
	 * @param graph
	 * 		Inheritance graph of the workspace.
	 * @param info
	 * 		Class to describe.
	 *
	 * @return The super classes of the class from nearest to furthest, noting where the chain cannot be followed.
	 */
	@Nonnull
	private static String superChain(@Nonnull InheritanceGraph graph, @Nonnull ClassInfo info) {
		List<String> chain = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		seen.add(info.getName());
		String superName = info.getSuperName();
		while (superName != null) {
			if (!seen.add(superName)) {
				chain.add(superName + " (cyclic inheritance)");
				break;
			}
			InheritanceVertex vertex = graph.getVertex(superName);
			if (vertex == null) {
				chain.add(superName + " (not in the workspace, its super classes are unknown)");
				break;
			}
			chain.add(superName);
			superName = vertex.getValue().getSuperName();
		}
		return chain.isEmpty() ? "none" : String.join(" -> ", chain);
	}

	/**
	 * @param graph
	 * 		Inheritance graph of the workspace.
	 * @param info
	 * 		Class to describe.
	 *
	 * @return Every interface the class implements, directly or through its super classes and super interfaces.
	 * Interfaces declared by types that are missing from the workspace cannot be known and are not included.
	 */
	@Nonnull
	private static Set<String> inheritedInterfaces(@Nonnull InheritanceGraph graph, @Nonnull ClassInfo info) {
		Set<String> interfaces = new LinkedHashSet<>();
		Set<String> seen = new HashSet<>();
		Deque<ClassInfo> pending = new ArrayDeque<>();
		pending.add(info);
		while (!pending.isEmpty()) {
			ClassInfo current = pending.remove();
			if (!seen.add(current.getName()))
				continue;
			List<String> parents = new ArrayList<>(current.getInterfaces());
			interfaces.addAll(parents);
			if (current.getSuperName() != null)
				parents.add(current.getSuperName());
			for (String parent : parents) {
				InheritanceVertex vertex = graph.getVertex(parent);
				if (vertex != null)
					pending.add(vertex.getValue());
			}
		}
		return interfaces;
	}

	/**
	 * @param workspace
	 * 		Workspace to look the class up in.
	 * @param name
	 * 		Class name.
	 *
	 * @return Note for classes that are not part of the analyzed program, empty for those that are.
	 */
	@Nonnull
	private static String origin(@Nonnull Workspace workspace, @Nonnull String name) {
		return workspace.findClass(false, name) == null ? "  (runtime library)" : "";
	}
}
