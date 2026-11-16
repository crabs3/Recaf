package software.coley.recaf.agent.tool.impl;

import jakarta.annotation.Nonnull;
import software.coley.recaf.agent.tool.AgentTool;
import software.coley.recaf.agent.tool.ToolArgs;
import software.coley.recaf.agent.tool.ToolContext;
import software.coley.recaf.agent.tool.ToolException;
import software.coley.recaf.agent.tool.ToolProvider;
import software.coley.recaf.agent.tool.ToolSchema;
import software.coley.recaf.agent.tool.support.Lookups;
import software.coley.recaf.agent.tool.support.Text;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.services.mapping.IntermediateMappings;
import software.coley.recaf.services.mapping.data.ClassMapping;
import software.coley.recaf.services.transform.ClassTransformer;
import software.coley.recaf.services.transform.CollectionTransformer;
import software.coley.recaf.services.transform.JvmClassTransformer;
import software.coley.recaf.services.transform.JvmTransformResult;
import software.coley.recaf.services.transform.TransformationApplier;
import software.coley.recaf.services.transform.TransformationApplierService;
import software.coley.recaf.services.transform.TransformationException;
import software.coley.recaf.services.transform.TransformationFeedback;
import software.coley.recaf.services.transform.TransformationManager;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.ClassBundle;
import software.coley.recaf.workspace.model.resource.WorkspaceResource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static software.coley.recaf.agent.tool.impl.EditTools.count;
import static software.coley.recaf.agent.tool.impl.EditTools.listed;

/**
 * Tools for running Recaf's deobfuscation transformers.
 *
 * @author Recaf agent tooling
 */
public final class TransformTools implements ToolProvider {
	private static final int TIMEOUT_SECONDS = 600;
	private static final int DEFAULT_MAX_PASSES = 5;
	private static final int MAX_PASSES = 50;
	private static final int MAX_FAILURES = 5;
	/**
	 * What each of Recaf's transformers does, by identifier. The transformers themselves only carry an identifier,
	 * their descriptions are part of the translations of the desktop interface. These lines are derived from the
	 * documentation of the transformer classes.
	 */
	private static final Map<String, String> DESCRIPTIONS = Map.ofEntries(
			Map.entry("cleanup.cycle", "Removes classes whose inheritance is cyclic, such as a class that is its own super class. They exist to crash analysis tools."),
			Map.entry("cleanup.deadcode", "Removes instructions that can never be executed."),
			Map.entry("cleanup.duplicateanno", "Removes repeated annotations, keeping the first of each type on a class or member."),
			Map.entry("cleanup.frames", "Removes stack map frames. Only useful when the frames of the input are broken."),
			Map.entry("cleanup.illegalanno", "Removes annotations that are not valid in Java source."),
			Map.entry("cleanup.illegalname", "Renames classes, fields and methods whose names are not valid Java identifiers."),
			Map.entry("cleanup.illegalsignature", "Removes malformed generic signatures, a common trick to confuse or crash decompilers."),
			Map.entry("cleanup.illegalvarargs", "Removes the varargs flag from methods whose last parameter is not an array, which crashes some decompilers."),
			Map.entry("cleanup.longanno", "Removes extremely long annotations that only exist to slow down decompilers."),
			Map.entry("cleanup.longexception", "Removes extremely long exception names from 'throws' declarations that only exist to slow down decompilers."),
			Map.entry("cleanup.syntheticstrip", "Removes the synthetic and bridge flags, which obfuscators set to hide classes and members from decompilers."),
			Map.entry("cleanup.unknownattr", "Removes class file attributes that are not part of the class file format."),
			Map.entry("cleanup.vartable", "Gives all local variables consistent generated names, replacing obfuscated or clashing ones."),
			Map.entry("peephole.data.callinline", "Replaces calls to static methods whose result can be computed from constant arguments with that result. Decrypts the strings of many obfuscators."),
			Map.entry("peephole.data.constfold", "Replaces instruction sequences that always compute the same value with that constant."),
			Map.entry("peephole.data.exceptioncollect", "Collects which exception types exist, for other transformers. Changes nothing by itself."),
			Map.entry("peephole.data.indyinline", "Replaces obfuscator-generated invokedynamic calls with the field access or method call they resolve to."),
			Map.entry("peephole.data.staticcollect", "Collects the values assigned to static final fields, for static value inlining. Changes nothing by itself."),
			Map.entry("peephole.data.staticinline", "Replaces reads of static fields with their known constant value."),
			Map.entry("peephole.data.varfold", "Removes redundant stores to and loads from local variables."),
			Map.entry("peephole.flow.duplicatecatch", "Merges catch blocks that contain the same code."),
			Map.entry("peephole.flow.gotoinline", "Inlines redundant goto jumps, which straightens out scrambled control flow."),
			Map.entry("peephole.flow.predicate", "Removes opaque predicates: branches whose condition always evaluates to the same value."),
			Map.entry("peephole.flow.redundantcatch", "Removes try-catch blocks that can never catch anything."),
			Map.entry("restoration.enumname", "Renames obfuscated enum constants to the names that remain as strings in the static initializer of the enum."),
			Map.entry("restoration.kotlinmetadata", "Collects Kotlin metadata, for Kotlin name restoration. Changes nothing by itself."),
			Map.entry("restoration.kotlinname", "Renames Kotlin classes and members to the original names that remain in their @Metadata annotation."),
			Map.entry("restoration.sourcename", "Renames obfuscated classes to the name that remains in their SourceFile attribute."),
			Map.entry("specific.dasho.opaqueseed", "For DashO: replaces the random seed functions of its string encryption with constants, so that peephole.data.callinline can decrypt the strings."),
			Map.entry("specific.zkm.cleanup", "For Zelix KlassMaster: removes the junk that remains after its invokedynamic calls were inlined."),
			Map.entry("specific.zkm.methodparams", "For Zelix KlassMaster: resolves the decryption keys it passes along through extra method parameters.")
	);
	private final ToolContext context;

	/**
	 * @param context
	 * 		Context the tools operate in.
	 */
	public TransformTools(@Nonnull ToolContext context) {
		this.context = context;
	}

	@Nonnull
	@Override
	public List<AgentTool> tools() {
		return List.of(listTransformers(), applyTransformers());
	}

	@Nonnull
	private AgentTool listTransformers() {
		return AgentTool.builder("list_transformers")
				.description("List Recaf's deobfuscation transformers: automated rewrites that undo common obfuscation, such as " +
						"removing dead code and opaque predicates, inlining decrypted strings and constants, or restoring names. " +
						"Each line shows the identifier to pass to apply_transformers and what the transformer does, " +
						"followed by the transformers it brings along and the ones it works best after. " +
						"Look here when decompiled code is cluttered or encrypted in a way a tool could clean up.")
				.handler(args -> {
					Available available = available();
					StringBuilder sb = new StringBuilder(count(available.byIdentifier().size(), "transformer"))
							.append(" for JVM classes. Pass identifiers to apply_transformers in the order they should run:\n");
					for (Map.Entry<String, JvmClassTransformer> entry : available.byIdentifier().entrySet()) {
						JvmClassTransformer transformer = entry.getValue();
						sb.append(entry.getKey()).append("  ").append(describe(transformer));
						String dependencies = identifiers(available, transformer.dependencies());
						if (!dependencies.isEmpty())
							sb.append(" [runs ").append(dependencies).append(" first on its own]");
						String predecessors = identifiers(available, transformer.recommendedPredecessors());
						if (!predecessors.isEmpty())
							sb.append(" [best after ").append(predecessors).append(']');
						String successors = identifiers(available, transformer.recommendedSuccessors());
						if (!successors.isEmpty())
							sb.append(" [best before ").append(successors).append(']');
						sb.append('\n');
					}
					return sb.toString().stripTrailing();
				})
				.build();
	}

	@Nonnull
	private AgentTool applyTransformers() {
		return AgentTool.builder("apply_transformers")
				.description("Run deobfuscation transformers over the JVM classes of the analyzed program, in the given order. " +
						"By default this is a dry run that changes nothing and reports which classes would be modified, removed or renamed. " +
						"Check that report, then call again with dry_run=false to apply. " +
						"The run can be limited to a package or to individual classes, which is also the way to try a transformer on one class first. " +
						"Renames that a transformer comes up with are applied to references in the whole program. " +
						"Returns the number of affected classes per transformer and the list of modified classes in pages. " +
						"Large programs can take minutes. Decompile a modified class afterwards to see the effect, and use undo_change on a class if it got worse.")
				.schema(ToolSchema.object()
						.stringList("transformers", "Identifiers of the transformers to run, in order, as shown by list_transformers. " +
								"For example ['peephole.flow.predicate', 'cleanup.deadcode'].")
						.optBool("dry_run", "true to only report what would change, false to apply the changes. Default true.")
						.optString("package", "Only transform classes in this package and its sub-packages, for example 'com/example/core'.")
						.optStringList("classes", "Only transform these classes and their inner classes, for example ['com/example/Main']. " +
								"When 'package' is given too, classes matching either are transformed.")
						.optInteger("max_passes", "How often the transformers are repeated while they still find something to change. " +
								"Default " + DEFAULT_MAX_PASSES + ".")
						.paging(50))
				.mutating()
				.handler(args -> {
					Workspace workspace = context.workspace();
					WorkspaceResource primary = workspace.getPrimaryResource();
					List<String> programClasses = primary.jvmAllClassBundleStreamRecursive()
							.flatMap(bundle -> bundle.keySet().stream())
							.collect(Collectors.toList());
					if (programClasses.isEmpty())
						throw new ToolException("The analyzed program has no JVM classes. Recaf's transformers work on JVM bytecode only, " +
								"they cannot be applied to Android (DEX) classes.");

					Available available = available();
					List<Class<? extends JvmClassTransformer>> types = new ArrayList<>();
					List<String> identifiers = new ArrayList<>();
					for (String name : args.stringList("transformers")) {
						String identifier = resolve(available, name);
						if (identifiers.contains(identifier))
							throw new ToolException("The transformer '" + identifier + "' is listed more than once. " +
									"Repetition is controlled by 'max_passes', list each transformer once.");
						identifiers.add(identifier);
						types.add(available.types().get(identifier));
					}
					if (types.isEmpty())
						throw new ToolException("Pass at least one transformer in 'transformers'. Use list_transformers to see what there is.");

					Predicate<String> scope = scope(workspace, args);
					long inScope = programClasses.stream().filter(scope).count();
					if (inScope == 0)
						throw new ToolException("No JVM class of the analyzed program is in the given 'package' or 'classes', " +
								"so there is nothing to transform. Use list_classes to check the names.");
					boolean dryRun = args.optBool("dry_run", true);
					int maxPasses = Math.max(1, Math.min(MAX_PASSES, args.optInt("max_passes", DEFAULT_MAX_PASSES)));

					TransformationApplier applier = context.service(TransformationApplierService.class).newApplier(workspace);
					applier.setMaxPasses(maxPasses);
					JvmTransformResult result = run(applier, types, new Feedback(scope));
					context.checkCancelled();

					List<String> modified = result.getTransformedClasses().values().stream()
							.map(ClassInfo::getName).distinct().sorted().collect(Collectors.toList());
					List<String> removed = result.getClassesToRemove().stream()
							.map(path -> path.getValue().getName()).distinct().sorted().collect(Collectors.toList());
					IntermediateMappings mappings = result.getMappingsToApply();
					int renames = mappings.getClasses().size() + size(mappings.getFields()) + size(mappings.getMethods()) + size(mappings.getVariables());
					String scopeText = inScope == programClasses.size() ? count((int) inScope, "class") :
							count((int) inScope, "class") + " in scope";
					if (modified.isEmpty() && removed.isEmpty() && renames == 0)
						return (dryRun ? "Dry run: the" : "The") + " transformers found nothing to change in the " + scopeText +
								". Nothing was changed." + failures(available, result);

					// Recaf applies the renames of transformers as they are. Two classes sharing a name would overwrite each other.
					List<String> clashes = renames == 0 ? List.of() : MappingTools.problems(workspace, null, mappings);
					if (!dryRun) {
						if (!clashes.isEmpty())
							throw new ToolException("Nothing was changed. " + clashes(clashes));
						result.apply();
					}

					StringBuilder sb = new StringBuilder(dryRun ? "Dry run, nothing was changed yet. Of the " : "Applied. Of the ")
							.append(scopeText).append(", ").append(modified.size()).append(dryRun ? " would be modified" : (modified.size() == 1 ? " was modified" : " were modified"));
					if (!removed.isEmpty())
						sb.append(" and ").append(removed.size()).append(dryRun ? " would be removed" : " removed").append(" (").append(listed(removed)).append(')');
					sb.append('.');
					if (renames > 0) {
						sb.append(' ').append(count(renames, "rename")).append(dryRun ? " would be applied across the program" : " were applied across the program")
								.append(": ").append(count(mappings.getClasses().size(), "class")).append(", ")
								.append(count(size(mappings.getFields()), "field")).append(", ")
								.append(count(size(mappings.getMethods()), "method")).append('.');
						List<String> classRenames = mappings.getClasses().values().stream()
								.sorted(Comparator.comparing(ClassMapping::getOldName))
								.map(TransformTools::describe)
								.collect(Collectors.toList());
						if (!classRenames.isEmpty())
							sb.append(" Class renames: ").append(listed(classRenames)).append('.');
						if (!dryRun)
							sb.append(" export_mappings shows all renames.");
						if (!clashes.isEmpty())
							sb.append('\n').append(clashes(clashes));
					}
					sb.append('\n').append(perTransformer(available, result, dryRun));
					sb.append(failures(available, result));
					if (!modified.isEmpty())
						sb.append('\n').append(Text.page(modified, args.offset(), args.limit(50, 500),
								dryRun ? "classes that would be modified" : "modified classes", name -> name));
					if (dryRun && clashes.isEmpty())
						sb.append("\n[Call again with dry_run=false to apply these changes.]");
					return sb.toString();
				})
				.build();
	}

	/**
	 * Runs transformers without applying their result, giving up after a generous amount of time.
	 *
	 * @param applier
	 * 		Applier for the workspace.
	 * @param types
	 * 		Transformers to run, in order.
	 * @param feedback
	 * 		Scope of the run and the means to stop it.
	 *
	 * @return What the transformers would change.
	 *
	 * @throws ToolException
	 * 		When the transformers cannot be run or take too long.
	 */
	@Nonnull
	private JvmTransformResult run(@Nonnull TransformationApplier applier, @Nonnull List<Class<? extends JvmClassTransformer>> types,
	                               @Nonnull Feedback feedback) throws Exception {
		CompletableFuture<JvmTransformResult> future = new CompletableFuture<>();
		Thread worker = new Thread(() -> {
			try {
				future.complete(applier.transformJvm(types, feedback));
			} catch (Throwable t) {
				future.completeExceptionally(t);
			}
		}, "recaf-agent-transform");
		worker.setDaemon(true);
		worker.start();

		// The applier checks for cancellation between classes. Waiting in short steps also ends the call when a
		// transformer is stuck inside a single class.
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
		while (true) {
			try {
				return future.get(250, TimeUnit.MILLISECONDS);
			} catch (TimeoutException ex) {
				if (context.isCancelled() || System.nanoTime() >= deadline) {
					feedback.stop();
					context.checkCancelled();
					throw new ToolException("The transformers did not finish within " + TIMEOUT_SECONDS + " seconds and were stopped. " +
							"Nothing was changed. Run fewer transformers at once, or limit the run with 'package' or 'classes'.");
				}
			} catch (InterruptedException ex) {
				feedback.stop();
				throw ex;
			} catch (ExecutionException ex) {
				if (ex.getCause() instanceof TransformationException failure)
					throw new ToolException("The transformers could not be run: " + chain(failure) + ". Nothing was changed.");
				throw ex;
			}
		}
	}

	/**
	 * @param workspace
	 * 		Open workspace.
	 * @param args
	 * 		Arguments with the optional {@code package} and {@code classes} limits.
	 *
	 * @return Test for the names of the classes that the transformers may change.
	 */
	@Nonnull
	private static Predicate<String> scope(@Nonnull Workspace workspace, @Nonnull ToolArgs args) {
		String packageName = args.optString("package", null);
		String packagePrefix = packageName == null || packageName.isBlank() ? null :
				Lookups.internalName(packageName).replaceAll("/+$", "") + "/";
		Set<String> classNames = new HashSet<>();
		for (String name : args.stringList("classes")) {
			ClassInfo info = EditTools.requireEditableClass(workspace, name).getValue();
			if (!info.isJvmClass())
				throw new ToolException("'" + info.getName() + "' is an Android (DEX) class. Recaf's transformers work on JVM bytecode only.");
			classNames.add(info.getName());
		}
		if (packagePrefix == null && classNames.isEmpty())
			return name -> true;
		return name -> {
			if (packagePrefix != null && name.startsWith(packagePrefix))
				return true;
			if (classNames.contains(name))
				return true;
			for (int dollar = name.indexOf('$'); dollar > 0; dollar = name.indexOf('$', dollar + 1))
				if (classNames.contains(name.substring(0, dollar)))
					return true;
			return false;
		};
	}

	/**
	 * @return The transformers Recaf has for JVM classes.
	 */
	@Nonnull
	private Available available() throws TransformationException {
		TransformationManager manager = context.service(TransformationManager.class);
		Map<String, JvmClassTransformer> byIdentifier = new TreeMap<>();
		Map<String, Class<? extends JvmClassTransformer>> types = new HashMap<>();
		Map<Class<?>, String> identifiers = new HashMap<>();
		for (Class<? extends JvmClassTransformer> type : manager.getJvmClassTransformers()) {
			JvmClassTransformer transformer = manager.newJvmTransformer(type);
			String identifier = transformer.identifier();
			byIdentifier.put(identifier, transformer);
			types.put(identifier, type);
			identifiers.put(type, identifier);
			identifiers.put(transformer.getClass(), identifier);
		}
		return new Available(byIdentifier, types, identifiers);
	}

	/**
	 * @param available
	 * 		The transformers Recaf has.
	 * @param name
	 * 		Name of a transformer as passed by the model.
	 *
	 * @return Identifier of the transformer.
	 *
	 * @throws ToolException
	 * 		When no transformer goes by that name.
	 */
	@Nonnull
	private static String resolve(@Nonnull Available available, @Nonnull String name) {
		String wanted = name.trim().toLowerCase(Locale.ROOT);
		if (available.byIdentifier().containsKey(wanted))
			return wanted;

		// Also accept the class name of a transformer, and the last part of an identifier where that is unambiguous.
		List<String> matches = new ArrayList<>();
		for (Map.Entry<String, JvmClassTransformer> entry : available.byIdentifier().entrySet()) {
			String simpleName = entry.getValue().getClass().getSimpleName().toLowerCase(Locale.ROOT);
			if (simpleName.equals(wanted) || simpleName.equals(wanted + "transformer") || entry.getKey().endsWith("." + wanted))
				matches.add(entry.getKey());
		}
		if (matches.size() == 1)
			return matches.get(0);
		List<String> similar = matches.isEmpty() ? available.byIdentifier().keySet().stream()
				.filter(identifier -> !wanted.isEmpty() && identifier.contains(wanted))
				.collect(Collectors.toList()) : matches;
		throw new ToolException("There is no transformer named '" + name + "'." +
				(similar.isEmpty() ? "" : " Did you mean: " + listed(similar) + "?") +
				" Use list_transformers to see the identifiers.");
	}

	@Nonnull
	private static String describe(@Nonnull JvmClassTransformer transformer) {
		String description = DESCRIPTIONS.get(transformer.identifier());
		if (description != null)
			return description;
		// A transformer added by a plugin. Its class name is all there is to go by.
		return "No description available, provided by " + transformer.getClass().getName() + "." +
				(transformer instanceof CollectionTransformer ? " Collects data for other transformers." : "");
	}

	@Nonnull
	private static String describe(@Nonnull ClassMapping mapping) {
		return mapping.getOldName() + " -> " + mapping.getNewName();
	}

	@Nonnull
	private static String identifiers(@Nonnull Available available, @Nonnull Collection<Class<? extends ClassTransformer>> types) {
		return types.stream()
				.map(type -> available.identifiers().getOrDefault(type, type.getSimpleName()))
				.sorted()
				.collect(Collectors.joining(", "));
	}

	@Nonnull
	private static String clashes(@Nonnull List<String> clashes) {
		StringBuilder sb = new StringBuilder("The renames the transformers came up with cannot be applied, because they clash:");
		for (int i = 0; i < Math.min(MAX_FAILURES, clashes.size()); i++)
			sb.append("\n  - ").append(clashes.get(i));
		if (clashes.size() > MAX_FAILURES)
			sb.append("\n  [").append(clashes.size() - MAX_FAILURES).append(" more not shown.]");
		sb.append("\nLimit the run with 'classes' or 'package' so that it does not cover both sides of a clash, give one of them " +
				"another name first, or leave out the transformer that renames.");
		return sb.toString();
	}

	@Nonnull
	private static String perTransformer(@Nonnull Available available, @Nonnull JvmTransformResult result, boolean dryRun) {
		Map<String, Integer> counts = new TreeMap<>();
		result.getModifiedClassesPerTransformer().forEach((type, paths) ->
				counts.put(available.identifiers().getOrDefault(type, type.getSimpleName()), paths.size()));
		if (counts.isEmpty())
			return "No transformer " + (dryRun ? "would modify" : "modified") + " the code of a class.";
		return "Classes " + (dryRun ? "each transformer would work on: " : "each transformer worked on: ") + counts.entrySet().stream()
				.map(entry -> entry.getKey() + " " + entry.getValue())
				.collect(Collectors.joining(", ")) + ".";
	}

	@Nonnull
	private static String failures(@Nonnull Available available, @Nonnull JvmTransformResult result) {
		List<String> failures = new ArrayList<>();
		result.getTransformerFailures().forEach((path, perTransformer) -> perTransformer.forEach((type, error) ->
				failures.add(available.identifiers().getOrDefault(type, type.getSimpleName()) + " on " + path.getValue().getName() + ": " + chain(error))));
		if (failures.isEmpty())
			return "";
		failures.sort(null);
		StringBuilder sb = new StringBuilder("\n").append(count(failures.size(), "failure"))
				.append(". A transformer that fails on a class leaves that class as it was:");
		for (int i = 0; i < Math.min(MAX_FAILURES, failures.size()); i++)
			sb.append("\n  ").append(Text.shorten(failures.get(i), 300));
		if (failures.size() > MAX_FAILURES)
			sb.append("\n  [").append(failures.size() - MAX_FAILURES).append(" more failures not shown.]");
		return sb.toString();
	}

	@Nonnull
	private static String chain(@Nonnull Throwable t) {
		StringBuilder sb = new StringBuilder();
		Throwable current = t;
		for (int depth = 0; current != null && depth < 3; depth++) {
			if (depth > 0)
				sb.append(", caused by ");
			sb.append(current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage());
			current = current.getCause() == current ? null : current.getCause();
		}
		return sb.toString();
	}

	private static int size(@Nonnull Map<String, ? extends List<?>> entries) {
		return entries.values().stream().mapToInt(List::size).sum();
	}

	/**
	 * Limits a run to the classes in scope and stops it when the user cancels or the tool gives up waiting.
	 */
	private final class Feedback implements TransformationFeedback {
		private final Predicate<String> scope;
		private volatile boolean stopped;

		private Feedback(@Nonnull Predicate<String> scope) {
			this.scope = scope;
		}

		private void stop() {
			stopped = true;
		}

		@Override
		public boolean hasRequestedCancellation() {
			return stopped || context.isCancelled();
		}

		@Override
		public boolean shouldTransform(@Nonnull Workspace workspace, @Nonnull WorkspaceResource resource,
		                               @Nonnull ClassBundle<?> bundle, @Nonnull ClassInfo classInfo,
		                               @Nonnull ClassTransformer transformer, int pass) {
			// Transformers that only collect data for others need to see the whole program to be of use.
			return transformer instanceof CollectionTransformer || scope.test(classInfo.getName());
		}
	}

	/**
	 * The transformers Recaf has for JVM classes.
	 *
	 * @param byIdentifier
	 * 		An instance of each transformer by identifier, sorted.
	 * @param types
	 * 		The registered type of each transformer by identifier.
	 * @param identifiers
	 * 		The identifier of each transformer by type.
	 */
	private record Available(@Nonnull Map<String, JvmClassTransformer> byIdentifier,
	                         @Nonnull Map<String, Class<? extends JvmClassTransformer>> types,
	                         @Nonnull Map<Class<?>, String> identifiers) {}
}
