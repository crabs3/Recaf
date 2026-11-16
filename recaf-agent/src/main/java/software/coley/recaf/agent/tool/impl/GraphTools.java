package software.coley.recaf.agent.tool.impl;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import software.coley.recaf.agent.tool.AgentTool;
import software.coley.recaf.agent.tool.ToolArgs;
import software.coley.recaf.agent.tool.ToolContext;
import software.coley.recaf.agent.tool.ToolException;
import software.coley.recaf.agent.tool.ToolProvider;
import software.coley.recaf.agent.tool.ToolSchema;
import software.coley.recaf.agent.tool.support.Lookups;
import software.coley.recaf.agent.tool.support.Text;
import software.coley.recaf.agent.tool.support.Workspaces;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.services.analysis.antitamper.AntiReversalAnalysisService;
import software.coley.recaf.services.analysis.antitamper.IllegalNameAnalysis;
import software.coley.recaf.services.analysis.antitamper.IllegalNameAntiReversalAnalyzer;
import software.coley.recaf.services.analysis.antitamper.TransformerImpactAnalysis;
import software.coley.recaf.services.analysis.antitamper.TransformerImpactAntiReversalAnalyzer;
import software.coley.recaf.services.analysis.entry.EntryAnalysisService;
import software.coley.recaf.services.analysis.entry.EntryPoint;
import software.coley.recaf.services.callgraph.CallEdge;
import software.coley.recaf.services.callgraph.CallGraph;
import software.coley.recaf.services.callgraph.CallGraphService;
import software.coley.recaf.services.callgraph.CallSite;
import software.coley.recaf.services.callgraph.MethodRef;
import software.coley.recaf.services.callgraph.MethodVertex;
import software.coley.recaf.services.callgraph.scanner.AndroidMethodCallScanner;
import software.coley.recaf.services.callgraph.scanner.JvmMethodCallScanner;
import software.coley.recaf.services.callgraph.scanner.MethodCallScanner;
import software.coley.recaf.services.inheritance.InheritanceGraphService;
import software.coley.recaf.services.inheritance.InheritanceVertex;
import software.coley.recaf.services.mapping.gen.filter.NameGeneratorFilter;
import software.coley.recaf.services.transform.JvmClassTransformer;
import software.coley.recaf.services.transform.JvmTransformResult;
import software.coley.recaf.util.android.AndroidXmlUtil;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Tools for following the structure of a program as a whole: who calls what, where execution starts,
 * and what was done to the program to resist analysis.
 *
 * @author Recaf agent tooling
 */
public final class GraphTools implements ToolProvider {
	private static final int GRAPH_TIMEOUT_SECONDS = 120;
	/** How long to wait for a call graph that an earlier call already waited the full timeout for. */
	private static final int GRAPH_RETRY_TIMEOUT_SECONDS = 15;
	private static final int MAX_DEPTH = 4;
	private static final int DEFAULT_LIMIT = 100;
	private static final int MAX_LIMIT = 500;
	private static final int MAX_OVERRIDDEN_SHOWN = 4;
	/** What the anti-reversal transformers of Recaf repair, by transformer class name. */
	private static final Map<String, String> TRANSFORMER_FINDINGS = Map.of(
			"CycleClassRemovingTransformer", "cyclic class hierarchy",
			"IllegalAnnotationRemovingTransformer", "malformed annotations",
			"IllegalSignatureRemovingTransformer", "malformed generic signatures",
			"DuplicateAnnotationRemovingTransformer", "duplicated annotations",
			"LongAnnotationRemovingTransformer", "oversized annotations",
			"LongExceptionRemovingTransformer", "oversized exception names"
	);
	private final MethodCallScanner jvmScanner = new JvmMethodCallScanner();
	private final MethodCallScanner androidScanner = new AndroidMethodCallScanner();
	private final ToolContext context;
	private final int graphTimeoutSeconds;
	private Workspace ownGraphWorkspace;
	private CallGraph ownGraph;
	private CallGraph overdueGraph;

	/**
	 * @param context
	 * 		Context the tools operate in.
	 */
	public GraphTools(@Nonnull ToolContext context) {
		this(context, GRAPH_TIMEOUT_SECONDS);
	}

	/**
	 * @param context
	 * 		Context the tools operate in.
	 * @param graphTimeoutSeconds
	 * 		How long a tool call may wait for the call graph of the workspace to be built, in seconds.
	 */
	public GraphTools(@Nonnull ToolContext context, int graphTimeoutSeconds) {
		this.context = context;
		this.graphTimeoutSeconds = graphTimeoutSeconds;
	}

	@Nonnull
	@Override
	public List<AgentTool> tools() {
		return List.of(getCallers(), getCallees(), findEntryPoints(), detectAntiReversal());
	}

	@Nonnull
	private AgentTool getCallers() {
		return AgentTool.builder("get_callers")
				.description("List the methods that call a method, from Recaf's call graph. Use it to find out how code is reached: " +
						"who triggers a suspicious method, or where a decryption routine gets its input. " +
						"The method may also belong to the runtime library, for example class 'java/lang/Runtime', method 'exec', " +
						"to find the code that runs processes. " +
						"Each result line is a caller as 'class.method(descriptor)'. With 'depth' above 1 the callers of each caller " +
						"are listed indented below it, with recursion cut and methods that were already expanded marked. " +
						"Calls are resolved by declared type and inheritance, not by what an object is at runtime: a call through an " +
						"interface or super class counts as a call of the method declared there, and reflective calls are not seen. " +
						"The graph is built the first time it is needed, which can take a while for a large program.")
				.schema(methodSchema("Method whose callers to list"))
				.handler(args -> {
					Workspace workspace = context.workspace();
					String className = args.string("class");
					ClassInfo info;
					try {
						info = Lookups.requireClass(workspace, className, true).getValue();
					} catch (ToolException unknown) {
						// The graph only has methods of classes it can find, but calls to others are still in the code.
						throw new ToolException(unknown.getMessage() + " If the class belongs to a library that is not in the workspace, " +
								"search_references finds the calls to its methods without needing the class.");
					}
					MethodMember method = Lookups.requireMethod(info, args.string("method"), args.optString("descriptor", null));
					return callTree(workspace, info, method, true, args);
				})
				.build();
	}

	@Nonnull
	private AgentTool getCallees() {
		return AgentTool.builder("get_callees")
				.description("List the methods a method calls, from Recaf's call graph. Use it to see what a method does without " +
						"reading its code, or to follow execution down from an entry point. " +
						"Each result line is a called method as 'class.method(descriptor)'. Methods of the analyzed program come first, " +
						"then methods of the runtime library marked [runtime library], then calls that could not be resolved to a " +
						"declaration, marked [unresolved], which happens when the class is missing from the workspace. " +
						"With 'depth' above 1 the callees of each program method are listed indented below it, with recursion cut and " +
						"methods that were already expanded marked. " +
						"A call through an interface or super class is listed as the method declared there, the implementations it may " +
						"reach at runtime are not followed. " +
						"The graph is built the first time it is needed, which can take a while for a large program.")
				.schema(methodSchema("Method whose callees to list"))
				.handler(args -> {
					Workspace workspace = context.workspace();
					ClassInfo info = Lookups.requireClass(workspace, args.string("class")).getValue();
					MethodMember method = Lookups.requireMethod(info, args.string("method"), args.optString("descriptor", null));
					if (method.hasAbstractModifier() || method.hasNativeModifier())
						return reference(info, method) + " is " + (method.hasNativeModifier() ? "native" : "abstract") +
								": it has no bytecode, so no callees are known. " +
								(method.hasNativeModifier() ? "Its code is in a native library." :
										"Find its implementations with search_declarations.");
					return callTree(workspace, info, method, false, args);
				})
				.build();
	}

	@Nonnull
	private AgentTool findEntryPoints() {
		return AgentTool.builder("find_entry_points")
				.description("List the places where execution of the program is likely to start. Use it early to decide where to begin reading. " +
						"Detected are: Java main methods, Android activities declared in AndroidManifest.xml, and the initializers of " +
						"Minecraft Fabric and Forge mods and Bukkit and Velocity plugins. " +
						"Each result line shows 'class.method(descriptor)', or the class alone when the class as a whole is the entry point, " +
						"followed by the kind of entry point. " +
						"Not detected are servlets, Java agents (premain/agentmain), service loader implementations, static initializers, " +
						"and Android services, receivers, providers and Application classes: " +
						"look for those in the manifest files and with search_declarations.")
				.schema(ToolSchema.object().paging(DEFAULT_LIMIT))
				.handler(args -> {
					Workspace workspace = context.workspace();
					// A class can be found more than once, for instance in each variant a multi-release JAR holds of it.
					List<String> entries = context.service(EntryAnalysisService.class)
							.findEntryPoints(workspace, workspace.getPrimaryResource()).stream()
							.sorted(Comparator.comparing((EntryPoint entry) -> entry.kind().id()).thenComparing(GraphTools::target))
							.map(entry -> target(entry) + "  [" + entry.kind().displayName() + "]")
							.distinct()
							.collect(Collectors.toList());

					String androidNote = workspace.findFile(AndroidXmlUtil.MANIFEST_NAME) == null ? null :
							"This is an Android application: activities are only detected when the manifest names them with a fully " +
									"qualified class name, and services, receivers, providers and the Application class are not detected at all. " +
									"Use get_android_manifest for the complete list of components.";
					if (entries.isEmpty())
						return "No entry points were detected. " + (androidNote != null ? androidNote :
								"Detection covers Java main methods, Android activities and Minecraft mod and plugin initializers. " +
										"The program may be a library, or be started in another way: check META-INF/MANIFEST.MF " +
										"and other descriptors with search_files.");
					return Text.page(entries, args.offset(), args.limit(DEFAULT_LIMIT, MAX_LIMIT), "entry points", entry -> entry) +
							(androidNote == null ? "" : "\n" + androidNote);
				})
				.build();
	}

	@Nonnull
	private AgentTool detectAntiReversal() {
		return AgentTool.builder("detect_anti_reversal")
				.description("Detect what was done to the class files to break reverse engineering tools, using Recaf's anti-reversal analysis. " +
						"Reported are classes with names that are not legal in Java source (keywords, whitespace, non-ASCII or otherwise " +
						"invalid identifiers, for the class or its fields and methods), and classes with structural traps: cyclic class " +
						"hierarchies, malformed or duplicated or oversized annotations, malformed generic signatures and oversized exception names. " +
						"Run it when decompilation fails or produces garbage, the listed transformers of Recaf repair the structural traps. " +
						"The result is a summary per kind of finding followed by the affected classes, each with its findings. " +
						"Only JVM classes of the opened file are analyzed, not Android (DEX) classes. " +
						"Checks the program performs at runtime are not detected: for debugger, emulator, root or integrity checks " +
						"use search_references (for example owner 'android/os/Debug') and search_strings.")
				.schema(ToolSchema.object().paging(DEFAULT_LIMIT))
				.handler(args -> {
					Workspace workspace = context.workspace();
					WorkspaceResource resource = workspace.getPrimaryResource();
					long jvmClasses = resource.jvmAllClassBundleStreamRecursive().mapToLong(bundle -> bundle.size()).sum();
					long androidClasses = Workspaces.androidClasses(workspace).count();
					if (jvmClasses == 0)
						throw new ToolException("Recaf's anti-reversal analysis only covers JVM classes and the opened file has none" +
								(androidClasses > 0 ? " (it has " + androidClasses + " Android classes)" : "") + ". " +
								"Look for runtime anti-analysis checks with search_references and search_strings instead.");

					AntiReversalAnalysisService service = context.service(AntiReversalAnalysisService.class);
					Map<String, List<String>> findingsByClass = new TreeMap<>();
					Map<String, Integer> summary = new LinkedHashMap<>();
					Map<String, String> repairs = new HashMap<>();

					// Names that are legal in a class file but not in source.
					IllegalNameAnalysis names = service.analyze(workspace, resource, IllegalNameAntiReversalAnalyzer.class);
					NameGeneratorFilter illegalName = IllegalNameAntiReversalAnalyzer.getIllegalNameFilter();
					for (ClassPathNode path : names.classesWithIllegalNames()) {
						ClassInfo info = path.getValue();
						List<String> parts = new ArrayList<>();
						if (illegalName.shouldMapClass(info))
							parts.add("class name");
						long fields = info.getFields().stream().filter(field -> illegalName.shouldMapField(info, field)).count();
						if (fields > 0)
							parts.add(fields + (fields == 1 ? " field" : " fields"));
						long methods = info.getMethods().stream().filter(method -> illegalName.shouldMapMethod(info, method)).count();
						if (methods > 0)
							parts.add(methods + (methods == 1 ? " method" : " methods"));
						findingsByClass.computeIfAbsent(info.getName(), name -> new ArrayList<>())
								.add("illegal names: " + String.join(", ", parts));
					}
					summary.put("illegal names", names.classesWithIllegalNames().size());

					// Structures that the anti-reversal transformers would have to repair.
					TransformerImpactAnalysis impact = service.analyze(workspace, resource, TransformerImpactAntiReversalAnalyzer.class);
					JvmTransformResult result = impact.jvm().result();
					String failures = "";
					if (result == null) {
						failures = "\nThe analysis of structural traps failed to run, see Recaf's log. Only names were checked.";
					} else {
						Set<String> removed = result.getClassesToRemove().stream()
								.map(path -> path.getValue().getName()).collect(Collectors.toSet());
						Map<Class<? extends JvmClassTransformer>, Collection<ClassPathNode>> modified = result.getModifiedClassesPerTransformer();
						for (Class<? extends JvmClassTransformer> transformer : impact.jvm().transformerClasses()) {
							String finding = TRANSFORMER_FINDINGS.getOrDefault(transformer.getSimpleName(), transformer.getSimpleName());
							Set<String> affected = new HashSet<>();
							for (ClassPathNode path : modified.getOrDefault(transformer, List.of()))
								affected.add(path.getValue().getName());
							// The cycle transformer is the one that removes classes rather than changing them.
							if (transformer.getSimpleName().equals("CycleClassRemovingTransformer"))
								affected.addAll(removed);
							for (String className : affected)
								findingsByClass.computeIfAbsent(className, name -> new ArrayList<>())
										.add(finding + (removed.contains(className) ? " (the transformer would remove the class)" : ""));
							summary.put(finding, affected.size());
							repairs.put(finding, transformer.getSimpleName());
						}
						if (!result.getTransformerFailures().isEmpty())
							failures = "\n" + result.getTransformerFailures().size() + " classes could not be fully analyzed for structural traps, " +
									"which itself suggests they are malformed: " + Text.shorten(result.getTransformerFailures().keySet().stream()
									.map(path -> printable(path.getValue().getName())).sorted().collect(Collectors.joining(", ")), 600);
					}

					if (androidClasses > 0)
						failures += "\n" + androidClasses + " Android classes were not analyzed, the analysis only covers JVM classes.";
					if (findingsByClass.isEmpty())
						return "No anti-reversal measures were found in " + jvmClasses + " JVM classes. Checked for: " +
								String.join(", ", summary.keySet()) + "." + failures;

					StringBuilder sb = new StringBuilder();
					sb.append(findingsByClass.size()).append(" of ").append(jvmClasses).append(" JVM classes show anti-reversal measures:\n");
					summary.forEach((finding, count) -> {
						if (count == 0)
							return;
						sb.append("  ").append(finding).append(": ").append(count).append(count == 1 ? " class" : " classes");
						if (repairs.containsKey(finding))
							sb.append(", repaired by ").append(repairs.get(finding));
						sb.append('\n');
					});
					List<Map.Entry<String, List<String>>> affected = new ArrayList<>(findingsByClass.entrySet());
					sb.append('\n').append(Text.page(affected, args.offset(), args.limit(DEFAULT_LIMIT, MAX_LIMIT), "affected classes",
							entry -> printable(entry.getKey()) + "  [" + String.join("; ", entry.getValue()) + "]"));
					return sb.toString().stripTrailing() + failures;
				})
				.build();
	}

	/**
	 * @param subject
	 * 		What the method is to the tool, used to describe the arguments.
	 *
	 * @return Schema of the arguments identifying a method and bounding a call tree.
	 */
	@Nonnull
	private static ToolSchema methodSchema(@Nonnull String subject) {
		return ToolSchema.object()
				.string("class", "Name of the class declaring the method, for example 'com/example/Main' or 'com.example.Main'.")
				.string("method", subject + ", by name, for example 'run'. Constructors are named '<init>'.")
				.optString("descriptor", "Descriptor of the method, for example '(Ljava/lang/String;)V'. Only needed when the class " +
						"declares several methods of that name.")
				.optInteger("depth", "How many levels of calls to follow. Default 1, which lists direct calls only. Maximum " + MAX_DEPTH + ".")
				.paging(DEFAULT_LIMIT);
	}

	/**
	 * @param workspace
	 * 		Workspace the method belongs to.
	 * @param info
	 * 		Class declaring the method.
	 * @param method
	 * 		Method to start from.
	 * @param callers
	 *        {@code true} to list the methods calling the method, {@code false} to list the methods it calls.
	 * @param args
	 * 		Arguments holding the depth and the paging window.
	 *
	 * @return The callers or callees of the method, as an indented tree when more than one level was asked for.
	 */
	@Nonnull
	private String callTree(@Nonnull Workspace workspace, @Nonnull ClassInfo info, @Nonnull MethodMember method,
	                        boolean callers, @Nonnull ToolArgs args) throws InterruptedException {
		int depth = Math.max(1, Math.min(MAX_DEPTH, args.optInt("depth", 1)));
		int offset = args.offset();
		int limit = args.limit(DEFAULT_LIMIT, MAX_LIMIT);
		String target = reference(info, method);

		CallGraph graph = readyGraph(workspace);
		MethodVertex root = graph.getVertex(method);
		if (root == null)
			throw new ToolException("The call graph has no entry for " + target + ".");

		// One entry more than the page holds is enough to know whether there is a next page.
		CallTree tree = new CallTree(workspace, callers, depth, offset + limit + 1);
		tree.build(root);
		List<String> lines = tree.lines;
		String overridden = callers ? overriddenNote(workspace, info, method) : "";
		if (lines.isEmpty()) {
			if (callers)
				return "No callers of " + target + " were found: no code in the workspace calls it directly. " +
						"It may still be invoked reflectively, by a framework, or as an entry point (see find_entry_points)." + overridden;
			return target + " calls no methods.";
		}
		if (offset >= lines.size())
			return "No entries at offset " + offset + " (there are " + lines.size() + " in total).";

		String noun = callers ? "caller" : "callee";
		StringBuilder sb = new StringBuilder();
		sb.append(callers ? "Callers of " : "Callees of ").append(target).append(": ")
				.append(tree.direct).append(" direct ").append(noun).append(tree.direct == 1 ? "" : "s").append('.');
		if (depth > 1)
			sb.append(" Shown ").append(depth).append(" levels deep, each indented method ")
					.append(callers ? "calls" : "is called by").append(" the method above it.");
		sb.append('\n');
		int end = Math.min(lines.size(), offset + limit);
		for (int i = offset; i < end; i++)
			sb.append(lines.get(i)).append('\n');
		if (end < lines.size())
			sb.append("[More entries follow. Call again with offset=").append(end).append(" to continue.]\n");
		else if (offset > 0)
			sb.append("[Entries ").append(offset + 1).append('-').append(end).append(", the last ones.]\n");
		return sb.toString().stripTrailing() + overridden;
	}

	/**
	 * @param workspace
	 * 		Workspace to get the call graph of.
	 *
	 * @return The call graph of the workspace, fully built.
	 *
	 * @throws ToolException
	 * 		When the graph is not ready within the time a tool call may reasonably take.
	 */
	@Nonnull
	private CallGraph readyGraph(@Nonnull Workspace workspace) throws InterruptedException {
		CallGraph graph = obtainGraph(workspace);
		int timeout = graph == overdueGraph ? Math.min(GRAPH_RETRY_TIMEOUT_SECONDS, graphTimeoutSeconds) : graphTimeoutSeconds;
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeout);
		while (!graph.isReady().getValue()) {
			context.checkCancelled();
			if (System.nanoTime() > deadline) {
				boolean repeated = graph == overdueGraph;
				overdueGraph = graph;
				throw new ToolException("The call graph of this workspace is not ready" +
						(repeated ? " yet, after several waits. If this persists it failed to build, see Recaf's log. " :
								" after waiting " + timeout + " seconds: it is still being built in the background. Call this tool again in a moment. ") +
						"Meanwhile search_references finds the direct call sites of a method without the graph.");
			}
			Thread.sleep(25);
		}
		if (graph == overdueGraph)
			overdueGraph = null;
		return graph;
	}

	/**
	 * @param workspace
	 * 		Workspace to get the call graph of.
	 *
	 * @return The call graph of the workspace, with its initialization started.
	 */
	@Nonnull
	private synchronized CallGraph obtainGraph(@Nonnull Workspace workspace) throws InterruptedException {
		CallGraphService service = context.service(CallGraphService.class);

		// The service keeps one graph for the open workspace and starts building it, on another thread, when it is
		// first asked for. Wait for that to begin, asking again before then would start it a second time.
		CallGraph shared = service.getCurrentWorkspaceCallGraph();
		if (shared != null) {
			ownGraph = null;
			ownGraphWorkspace = null;
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(GRAPH_RETRY_TIMEOUT_SECONDS);
			while (!shared.isInitialized() && System.nanoTime() < deadline)
				Thread.sleep(5);
			return shared;
		}

		// The service only learns of workspaces opened after it was created, which is not at startup everywhere.
		// A workspace it missed gets a graph of our own, kept for as long as that workspace stays open.
		if (ownGraph == null || ownGraphWorkspace != workspace) {
			ownGraph = service.newCallGraph(workspace);
			ownGraphWorkspace = workspace;
			ownGraph.initialize();
		}
		return ownGraph;
	}

	/**
	 * @param workspace
	 * 		Workspace the method belongs to.
	 * @param info
	 * 		Class declaring the method.
	 * @param method
	 * 		Method to check.
	 *
	 * @return Note naming the declarations the method overrides or implements, since calls made through those are
	 * not callers of the method itself. Empty when it overrides nothing.
	 */
	@Nonnull
	private String overriddenNote(@Nonnull Workspace workspace, @Nonnull ClassInfo info, @Nonnull MethodMember method) {
		if (method.getName().startsWith("<") || method.hasStaticModifier() || method.hasPrivateModifier())
			return "";
		InheritanceVertex vertex = context.service(InheritanceGraphService.class)
				.getOrCreateInheritanceGraph(workspace).getVertex(info.getName());
		if (vertex == null)
			return "";
		List<String> declarations = new ArrayList<>();
		for (InheritanceVertex parent : vertex.getAllParents()) {
			MethodMember declared = parent.getValue().getDeclaredMethod(method.getName(), method.getDescriptor());
			if (declared != null && !declared.hasStaticModifier() && !declared.hasPrivateModifier())
				declarations.add(reference(parent.getValue(), declared));
		}
		if (declarations.isEmpty())
			return "";
		declarations.sort(null);
		int shown = Math.min(MAX_OVERRIDDEN_SHOWN, declarations.size());
		return "\nNote: this method overrides or implements " + String.join(", ", declarations.subList(0, shown)) +
				(shown < declarations.size() ? " and " + (declarations.size() - shown) + " more" : "") +
				". Calls made through such a type are callers of the method declared there, look those up as well.";
	}

	@Nonnull
	private static String reference(@Nonnull ClassInfo info, @Nonnull MethodMember method) {
		return info.getName() + "." + method.getName() + method.getDescriptor();
	}

	@Nonnull
	private static String reference(@Nonnull MethodRef method) {
		return method.owner() + "." + method.name() + method.desc();
	}

	@Nonnull
	private static String target(@Nonnull EntryPoint entry) {
		String className = entry.classPath().getValue().getName();
		return entry.memberPath() == null ? className : className + "." + Lookups.signature(entry.memberPath().getValue());
	}

	/**
	 * @param name
	 * 		Class name, possibly crafted to be unprintable.
	 *
	 * @return The name, quoted and escaped when it would otherwise garble a line of output.
	 */
	@Nonnull
	private static String printable(@Nonnull String name) {
		for (int i = 0; i < name.length(); i++) {
			char c = name.charAt(i);
			if (c <= ' ' || c == 0x7F || Character.isWhitespace(c))
				return Text.quote(name);
		}
		return name;
	}

	/**
	 * A method next to another in the call graph.
	 *
	 * @param method
	 * 		The method.
	 * @param vertex
	 * 		Its vertex in the graph, or {@code null} for a call that could not be resolved to a declaration.
	 * @param rank
	 * 		Sorting rank: methods of the program first, then of the runtime library, then unresolved ones.
	 * @param note
	 * 		Remark to print after the method, or an empty string.
	 */
	private record Neighbor(@Nonnull MethodRef method, @Nullable MethodVertex vertex, int rank, @Nonnull String note) {}

	/**
	 * Flattens the callers or callees of a method, to a given depth, into indented lines.
	 */
	private final class CallTree {
		private final List<String> lines = new ArrayList<>();
		private final Set<MethodRef> path = new HashSet<>();
		private final Set<MethodRef> expanded = new HashSet<>();
		private final Map<ClassInfo, Map<String, Set<CallSite>>> callSites = new IdentityHashMap<>();
		private final Map<String, Boolean> programClasses = new HashMap<>();
		private final Workspace workspace;
		private final boolean callers;
		private final int depth;
		private final int capacity;
		private int direct;

		/**
		 * @param workspace
		 * 		Workspace the graph belongs to.
		 * @param callers
		 *        {@code true} to follow callers, {@code false} to follow callees.
		 * @param depth
		 * 		Number of levels to follow.
		 * @param capacity
		 * 		Number of lines after which to stop.
		 */
		private CallTree(@Nonnull Workspace workspace, boolean callers, int depth, int capacity) {
			this.workspace = workspace;
			this.callers = callers;
			this.depth = depth;
			this.capacity = capacity;
		}

		private void build(@Nonnull MethodVertex root) {
			path.add(root.getMethod());
			expanded.add(root.getMethod());
			List<Neighbor> neighbors = neighbors(root);
			direct = neighbors.size();
			expand(neighbors, 1);
		}

		private void expand(@Nonnull List<Neighbor> neighbors, int level) {
			for (Neighbor neighbor : neighbors) {
				if (lines.size() >= capacity)
					return;
				StringBuilder line = new StringBuilder("  ".repeat(level - 1)).append(reference(neighbor.method())).append(neighbor.note());
				List<Neighbor> next = List.of();
				if (path.contains(neighbor.method())) {
					line.append("  [recursion]");
				} else if (level < depth && neighbor.vertex() != null && neighbor.rank() == 0) {
					next = neighbors(neighbor.vertex());
					if (!next.isEmpty() && !expanded.add(neighbor.method())) {
						line.append("  [expanded above]");
						next = List.of();
					}
				}
				lines.add(line.toString());
				if (!next.isEmpty()) {
					path.add(neighbor.method());
					expand(next, level + 1);
					path.remove(neighbor.method());
				}
			}
		}

		/**
		 * @param vertex
		 * 		Method in the graph.
		 *
		 * @return The methods calling it, or the methods it calls, in a stable order.
		 */
		@Nonnull
		private List<Neighbor> neighbors(@Nonnull MethodVertex vertex) {
			Collection<CallEdge> edges = callers ? vertex.getCallerEdges() : vertex.getCallEdges();
			Map<MethodRef, MethodVertex> vertices = new HashMap<>();
			Map<MethodRef, Set<String>> namedOwners = new HashMap<>();
			Set<CallSite> resolvedSites = new HashSet<>();
			for (CallEdge edge : edges) {
				MethodVertex other = callers ? edge.caller() : edge.callee();
				vertices.put(other.getMethod(), other);
				resolvedSites.add(edge.callSite());
				// The class named at the call site differs from the declaring class when the method is inherited.
				if (!edge.callSite().owner().equals(edge.callee().getMethod().owner()))
					namedOwners.computeIfAbsent(other.getMethod(), method -> new LinkedHashSet<>()).add(edge.callSite().owner());
			}

			List<Neighbor> neighbors = new ArrayList<>();
			vertices.forEach((method, other) -> {
				boolean program = programClasses.computeIfAbsent(method.owner(), owner -> workspace.findClass(false, owner) != null);
				String note = program ? "" : "  [runtime library]";
				Set<String> owners = namedOwners.get(method);
				if (owners != null)
					note += "  [" + (callers ? "calls it via " : "called via ") + owners.stream().sorted().collect(Collectors.joining(", ")) + "]";
				neighbors.add(new Neighbor(method, other, program ? 0 : 1, note));
			});
			if (!callers) {
				Set<MethodRef> unresolved = new HashSet<>();
				for (CallSite site : callSitesOf(vertex))
					if (!resolvedSites.contains(site) && !vertices.containsKey(site.methodRef()) && unresolved.add(site.methodRef()))
						neighbors.add(new Neighbor(site.methodRef(), null, 2, "  [unresolved]"));
			}
			neighbors.sort(Comparator.comparingInt(Neighbor::rank).thenComparing(neighbor -> reference(neighbor.method())));
			return neighbors;
		}

		/**
		 * @param vertex
		 * 		Method in the graph.
		 *
		 * @return Every call the code of the method makes, including those the graph could not resolve and so does not hold.
		 */
		@Nonnull
		private Set<CallSite> callSitesOf(@Nonnull MethodVertex vertex) {
			MethodMember method = vertex.getResolvedMethod();
			ClassInfo declaringClass = method == null ? null : method.getDeclaringClass();
			if (declaringClass == null)
				return Set.of();
			Map<String, Set<CallSite>> sitesByMethod = callSites.computeIfAbsent(declaringClass, info -> {
				Map<String, Set<CallSite>> sites = new HashMap<>();
				MethodCallScanner scanner = jvmScanner.supports(info) ? jvmScanner : androidScanner.supports(info) ? androidScanner : null;
				if (scanner != null)
					scanner.scan(info, (caller, site) ->
							sites.computeIfAbsent(caller.getName() + caller.getDescriptor(), key -> new LinkedHashSet<>()).add(site));
				return sites;
			});
			return sitesByMethod.getOrDefault(method.getName() + method.getDescriptor(), Set.of());
		}
	}
}
