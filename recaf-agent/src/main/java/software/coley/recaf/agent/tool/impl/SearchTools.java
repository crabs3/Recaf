package software.coley.recaf.agent.tool.impl;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import me.darknet.assembler.backend.jvm.printer.JvmPrinterUtil;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
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
import software.coley.recaf.info.FileInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.member.ClassMember;
import software.coley.recaf.path.AndroidInstructionPathNode;
import software.coley.recaf.path.AnnotationPathNode;
import software.coley.recaf.path.CatchPathNode;
import software.coley.recaf.path.ClassMemberPathNode;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.path.JvmInstructionPathNode;
import software.coley.recaf.path.LineNumberPathNode;
import software.coley.recaf.path.LocalVariablePathNode;
import software.coley.recaf.path.PathNode;
import software.coley.recaf.path.ThrowsPathNode;
import software.coley.recaf.services.search.AndroidClassSearchVisitor;
import software.coley.recaf.services.search.JvmClassSearchVisitor;
import software.coley.recaf.services.search.ResultSink;
import software.coley.recaf.services.search.SearchFeedback;
import software.coley.recaf.services.search.SearchService;
import software.coley.recaf.services.search.match.NumberPredicate;
import software.coley.recaf.services.search.match.NumberPredicateProvider;
import software.coley.recaf.services.search.match.StringPredicate;
import software.coley.recaf.services.search.match.StringPredicateProvider;
import software.coley.recaf.services.search.query.AndroidClassQuery;
import software.coley.recaf.services.search.query.DeclarationQuery;
import software.coley.recaf.services.search.query.JvmClassQuery;
import software.coley.recaf.services.search.query.NumberQuery;
import software.coley.recaf.services.search.query.Query;
import software.coley.recaf.services.search.query.ReferenceQuery;
import software.coley.recaf.services.search.query.StringQuery;
import software.coley.recaf.services.search.result.ClassReference;
import software.coley.recaf.services.search.result.MemberDeclaration;
import software.coley.recaf.services.search.result.MemberReference;
import software.coley.recaf.services.search.result.Result;
import software.coley.recaf.services.search.result.Results;
import software.coley.recaf.util.AccessFlag;
import software.coley.recaf.util.NumberUtil;
import software.coley.recaf.util.Types;
import software.coley.recaf.workspace.model.Workspace;
import software.coley.recaf.workspace.model.bundle.Bundle;
import software.coley.recaf.workspace.model.bundle.VersionedJvmClassBundle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

/**
 * Tools for finding things in a program that is too large to read end to end.
 *
 * @author Recaf agent tooling
 */
public final class SearchTools implements ToolProvider {
	private static final int DEFAULT_LIMIT = 50;
	private static final int MAX_LIMIT = 500;
	/** Upper bound of raw matches collected by one search, a backstop against queries that match nearly everything. */
	private static final int MAX_MATCHES = 20_000;
	private static final int SEARCH_TIMEOUT_SECONDS = 120;
	private static final int MAX_TEXT = 200;
	private static final int MAX_INSTRUCTION_LINES = 16;
	private static final String PACKAGE_DESCRIPTION = "Only search in this package and its sub-packages, for example 'com/example/net'. " +
			"A full class name limits the search to that class and its inner classes.";
	/** Register operands of a Dalvik instruction, which only get in the way of recognizing equal uses. */
	private static final Pattern DEX_REGISTERS = Pattern.compile("\\{[^\\}]*\\}, |\\b[vp]\\d+, ");
	private final ToolContext context;

	/**
	 * @param context
	 * 		Context the tools operate in.
	 */
	public SearchTools(@Nonnull ToolContext context) {
		this.context = context;
	}

	@Nonnull
	@Override
	public List<AgentTool> tools() {
		return List.of(searchStrings(), searchNumbers(), searchReferences(), searchDeclarations(),
				searchInstructions(), searchFiles());
	}

	@Nonnull
	private AgentTool searchStrings() {
		return AgentTool.builder("search_strings")
				.description("Find string constants in classes (field values, strings loaded by code, annotation values) " +
						"and matching lines of text files. Use it to locate URLs, keys, commands, file names, error messages " +
						"and other tell-tale text, and the code that uses them. " +
						"Each result line shows where the string is, as 'class.method(descriptor)', 'class.field descriptor' " +
						"or 'file:line', followed by the quoted string. Long strings are shortened around the match with their " +
						"full length noted. Strings that are built or decrypted at runtime are not constants and are not found.")
				.schema(ToolSchema.object()
						.string("query", "Text to look for, for example 'http' or 'password'. A regular expression when mode is 'regex'.")
						.optChoice("mode", "How 'query' is compared with a whole string constant, or a whole line of a text file. " +
								"Default 'contains'. With 'regex' a match anywhere in the string counts.",
								"contains", "equals", "starts_with", "ends_with", "regex")
						.optBool("case_sensitive", "Whether upper and lower case must match. Default false.")
						.optString("package", PACKAGE_DESCRIPTION + " Text files are limited to the directory of that name.")
						.paging(DEFAULT_LIMIT))
				.handler(args -> {
					Workspace workspace = context.workspace();
					String query = args.string("query");
					String mode = args.optString("mode", "contains").trim().toLowerCase(Locale.ROOT).replace('-', '_');
					boolean caseSensitive = args.optBool("case_sensitive", false);
					StringPredicateProvider predicates = context.service(StringPredicateProvider.class);
					StringPredicate predicate;
					ToIntFunction<String> matchStart;
					switch (mode) {
						case "contains" -> {
							predicate = predicates.newContainsPredicate(query, caseSensitive);
							matchStart = caseSensitive ? text -> text.indexOf(query) :
									text -> text.toLowerCase().indexOf(query.toLowerCase());
						}
						case "equals" -> {
							predicate = predicates.newEqualPredicate(query, caseSensitive);
							matchStart = text -> 0;
						}
						case "starts_with" -> {
							predicate = predicates.newStartsWithPredicate(query, caseSensitive);
							matchStart = text -> 0;
						}
						case "ends_with" -> {
							predicate = predicates.newEndsWithPredicate(query, caseSensitive);
							matchStart = text -> text.length() - query.length();
						}
						case "regex" -> {
							Pattern pattern = compile(query, caseSensitive, "query");
							predicate = regexPredicate(pattern);
							matchStart = text -> firstMatch(pattern, text);
						}
						default -> throw new ToolException("Unknown mode '" + mode +
								"'. Use contains, equals, starts_with, ends_with or regex.");
					}

					Scope scope = new Scope(args.optString("package", null), true);
					Results results = context.service(SearchService.class).search(workspace, new StringQuery(predicate), scope);
					context.checkCancelled();

					Map<Hit, Integer> hits = new HashMap<>();
					for (Result<?> result : results) {
						if (!(result.getValue() instanceof String text))
							continue;
						String detail = excerpt(text, matchStart.applyAsInt(text));
						if (result.getPath() instanceof AnnotationPathNode annotation)
							detail += " in annotation " + annotation.getValue().getDescriptor();
						hits.merge(hit(result.getPath(), detail), 1, Integer::sum);
					}
					if (hits.isEmpty())
						return "No string constant or text file line " +
								(mode.equals("regex") ? "matches the regular expression" : mode.replace('_', ' ')) +
								" " + Text.quote(query) + (caseSensitive ? " (case-sensitive)" : "") +
								" in " + scope.describe() + "." + scope.warning();
					return page(hits, args, "strings") + scope.warning();
				})
				.build();
	}

	@Nonnull
	private AgentTool searchNumbers() {
		return AgentTool.builder("search_numbers")
				.description("Find numeric constants in class code, field values and annotations: magic numbers, crypto constants, " +
						"ports, sizes, flag masks. Pass either 'value' for an exact number, or 'min' and/or 'max' for a range. " +
						"Numbers compare by value whatever their type, so 1337 finds int, long, float and double constants. " +
						"A hexadecimal value also matches by bit pattern, so 0xCAFEBABE finds the int -889275714. " +
						"Each result line shows 'class.method(descriptor)' or 'class.field descriptor' followed by the number found. " +
						"Numbers in text files are not searched, use search_files for those. " +
						"Android (DEX) code stores float and double constants as raw bits: a decimal 'value' is also looked up " +
						"by its bits there, a range is not. The data of array initializers in DEX code is not searched.")
				.schema(ToolSchema.object()
						.optString("value", "Number to find, as text: decimal like '1337', '-5' or '2.5', or hexadecimal like '0x5DEECE66D'.")
						.optString("min", "Smallest number to find, inclusive. Same forms as 'value'.")
						.optString("max", "Largest number to find, inclusive. Same forms as 'value'.")
						.optString("package", PACKAGE_DESCRIPTION)
						.paging(DEFAULT_LIMIT))
				.handler(args -> {
					Workspace workspace = context.workspace();
					String valueText = blankToNull(args.optString("value", null));
					String minText = blankToNull(args.optString("min", null));
					String maxText = blankToNull(args.optString("max", null));
					NumberPredicateProvider predicates = context.service(NumberPredicateProvider.class);
					NumberPredicate predicate;
					String criteria;
					// Raw bit patterns of a requested floating point value, mapped to the value they stand for.
					Map<Number, String> rawBits = new HashMap<>();
					if (valueText != null) {
						if (minText != null || maxText != null)
							throw new ToolException("Pass either 'value' or a range with 'min' and/or 'max', not both.");
						List<Number> values = equalNumbers(valueText, Workspaces.androidClasses(workspace).findAny().isPresent(), rawBits);
						predicate = values.size() == 1 ? predicates.newEqualsPredicate(values.get(0)) : predicates.newAnyOfPredicate(values);
						criteria = "equal to " + valueText;
					} else if (minText != null && maxText != null) {
						Number min = parseNumber(minText, "min").value();
						Number max = parseNumber(maxText, "max").value();
						if (NumberUtil.cmp(min, max) > 0)
							throw new ToolException("'min' (" + minText + ") is larger than 'max' (" + maxText + ").");
						predicate = predicates.newRangePredicate(min, max);
						criteria = "between " + minText + " and " + maxText;
					} else if (minText != null) {
						predicate = predicates.newGreaterThanOrEqualPredicate(parseNumber(minText, "min").value());
						criteria = "of at least " + minText;
					} else if (maxText != null) {
						predicate = predicates.newLessThanOrEqualPredicate(parseNumber(maxText, "max").value());
						criteria = "of at most " + maxText;
					} else {
						throw new ToolException("Pass 'value' to find an exact number, or 'min' and/or 'max' to find a range.");
					}

					Scope scope = new Scope(args.optString("package", null), false);
					Results results = context.service(SearchService.class).search(workspace, new NumberQuery(predicate), scope);
					context.checkCancelled();

					Map<Hit, Integer> hits = new HashMap<>();
					for (Result<?> result : results) {
						if (!(result.getValue() instanceof Number number))
							continue;
						String detail = describe(number);
						String floating = number instanceof Integer || number instanceof Long ? rawBits.get(number) : null;
						if (floating != null) {
							// Only DEX code leaves floating point constants untyped, elsewhere this is an unrelated integer.
							ClassInfo declaringClass = result.getPath().getValueOfType(ClassInfo.class);
							if (declaringClass == null || !declaringClass.isAndroidClass())
								continue;
							detail += " = raw bits of " + floating;
						}
						hits.merge(hit(result.getPath(), detail), 1, Integer::sum);
					}
					if (hits.isEmpty())
						return "No numeric constant " + criteria + " in " + scope.describe() + "." + scope.warning();
					return page(hits, args, "numbers") + scope.warning();
				})
				.build();
	}

	@Nonnull
	private AgentTool searchReferences() {
		return AgentTool.builder("search_references")
				.description("Find where a class, field or method is used: the 'who uses this' tool. " +
						"It works for anything code can refer to, including classes of the runtime library and of libraries that " +
						"are not in the workspace, which makes it the way to find process execution (owner 'java/lang/Runtime', " +
						"name 'exec'), reflection, class loading, crypto, file and network access. " +
						"With only 'owner' it lists every use of the class: calls and field accesses on it, instantiation, casts, " +
						"use as a field, parameter, variable or exception type, annotations, and the classes extending or implementing it. " +
						"With 'name' it lists uses of that field or method only. " +
						"Each result line shows the using 'class.method(descriptor)' followed by the instruction or declaration that " +
						"makes the reference (Dalvik instructions are shown without registers). " +
						"Matching is on the owner written at the use site: a call made through a subclass names the subclass. " +
						"For calls to a method that respect inheritance use get_callers.")
				.schema(ToolSchema.object()
						.string("owner", "Class being referenced, or declaring the referenced member, for example 'java/lang/Runtime' or 'com.example.Config'.")
						.optString("name", "Name of the referenced field or method, for example 'exec'. Omit to find all uses of the class.")
						.optString("descriptor", "Descriptor of the referenced field or method, for example '(Ljava/lang/String;)Ljava/lang/Process;'. " +
								"Omit to match every overload.")
						.optString("package", PACKAGE_DESCRIPTION)
						.paging(DEFAULT_LIMIT))
				.handler(args -> {
					Workspace workspace = context.workspace();
					String ownerText = args.string("owner");
					String name = blankToNull(args.optString("name", null));
					String descriptor = blankToNull(args.optString("descriptor", null));
					if (name != null && descriptor == null) {
						// Accept the forms members are printed in: 'name(args)ret' and 'name descriptor'.
						// Anything else is the name as it is, obfuscated names may well contain spaces and parentheses.
						int split = name.indexOf('(') > 0 ? name.indexOf('(') : name.lastIndexOf(' ');
						String trailing = split > 0 ? name.substring(split).trim() : null;
						if (Types.isValidDesc(trailing) || Types.isPrimitive(trailing)) {
							descriptor = trailing;
							name = name.substring(0, split);
						}
					}

					// The owner does not have to exist in the workspace, code can refer to classes that are missing from it.
					String owner;
					ClassInfo ownerInfo = null;
					try {
						ownerInfo = Lookups.requireClass(workspace, ownerText, true).getValue();
						owner = ownerInfo.getName();
					} catch (ToolException unknown) {
						owner = Lookups.internalName(ownerText);
					}

					StringPredicateProvider predicates = context.service(StringPredicateProvider.class);
					StringPredicate ownerPredicate = predicates.newEqualPredicate(owner);
					boolean classSearch = name == null && descriptor == null;
					List<Query> queries = new ArrayList<>();
					if (classSearch)
						queries.add(new ReferenceQuery(ownerPredicate));
					queries.add(new ReferenceQuery(ownerPredicate,
							name == null ? null : predicates.newEqualPredicate(name),
							descriptor == null ? null : predicates.newEqualPredicate(descriptor)));

					Scope scope = new Scope(args.optString("package", null), false);
					Results results = context.service(SearchService.class).search(workspace, queries, scope);
					context.checkCancelled();

					// An instruction referring to a member whose descriptor mentions the class is one use, not two.
					Set<String> memberUses = new HashSet<>();
					for (Result<?> result : results)
						if (result.getValue() instanceof MemberReference)
							memberUses.add(instructionKey(result.getPath()));
					Map<Hit, Integer> hits = new HashMap<>();
					for (Result<?> result : results) {
						PathNode<?> path = result.getPath();
						if (result.getValue() instanceof MemberReference reference) {
							String target = reference.owner() + "." + reference.name() +
									(reference.isMethodReference() ? "" : " ") + reference.desc();
							hits.merge(hit(path, describeUse(path, target, "refers to")), 1, Integer::sum);
						} else if (result.getValue() instanceof ClassReference reference && !memberUses.contains(instructionKey(path))) {
							hits.merge(hit(path, describeUse(path, reference.name(), "uses")), 1, Integer::sum);
						}
					}
					if (classSearch) {
						// Uses that are part of a class declaration, which the reference query does not report.
						String finalOwner = owner;
						String annotation = "L" + owner + ";";
						Workspaces.classes(workspace).filter(node -> scope.includes(node.getValue().getName())).forEach(node -> {
							ClassInfo info = node.getValue();
							if (finalOwner.equals(info.getSuperName()))
								hits.merge(hit(node, "extends " + finalOwner), 1, Integer::sum);
							if (info.getInterfaces().contains(finalOwner))
								hits.merge(hit(node, "implements " + finalOwner), 1, Integer::sum);
							// The query does report the annotations of Android classes, only those of JVM classes are missing.
							if (info.isJvmClass() && (info.getAnnotations().stream().anyMatch(a -> a.getDescriptor().equals(annotation)) ||
									info.getTypeAnnotations().stream().anyMatch(a -> a.getDescriptor().equals(annotation))))
								hits.merge(hit(node, "annotated with " + finalOwner), 1, Integer::sum);
						});
					}

					String target = owner + (name == null ? "" : "." + name) + (descriptor == null ? "" : (descriptor.startsWith("(") ? "" : " ") + descriptor);
					if (hits.isEmpty()) {
						String message = "No references to " + target + " in " + scope.describe() + ".";
						String memberName = name;
						if (ownerInfo == null)
							message += " There is no class named '" + owner + "' in the workspace or the runtime library, check the name " +
									"(search_declarations finds classes by name).";
						else if (memberName != null && ownerInfo.getFields().stream().noneMatch(field -> field.getName().equals(memberName)) &&
								ownerInfo.getMethods().stream().noneMatch(method -> method.getName().equals(memberName)))
							message += " " + owner + " itself declares no field or method named '" + memberName + "': the name may be " +
									"misspelled (get_class_info lists the members), or the member is inherited and referenced through another class.";
						else if (!classSearch)
							message += " References name the class written at the use site, which can be a subclass of " + owner +
									": get_callers follows inheritance, or search with only 'owner' to see how the class is used.";
						return message + scope.warning();
					}
					return "References to " + target + ". " + page(hits, args, "uses") + scope.warning();
				})
				.build();
	}

	@Nonnull
	private AgentTool searchDeclarations() {
		return AgentTool.builder("search_declarations")
				.description("Find classes, fields and methods by their own name. Use it to locate things by what they are called, " +
						"such as every method named like 'decrypt', every field named like 'key', or every class named like 'Loader'. " +
						"Each result line shows 'class', 'class.field descriptor' or 'class.method(descriptor)' followed by its kind " +
						"and modifiers. Class names are matched on the simple name (without package), " +
						"unless the pattern contains a '/' in which case the full name is matched. " +
						"Constructors (<init>) and static initializers (<clinit>) only match patterns containing '<'.")
				.schema(ToolSchema.object()
						.string("pattern", "Regular expression that must match somewhere in the name, for example 'crypt|cipher' or '^get[A-Z]'. " +
								"Case-insensitive, prefix it with (?-i) to make it case-sensitive.")
						.optChoice("kind", "What to look for. Default 'any'.", "class", "field", "method", "any")
						.optString("package", PACKAGE_DESCRIPTION)
						.paging(DEFAULT_LIMIT))
				.handler(args -> {
					Workspace workspace = context.workspace();
					String patternText = args.string("pattern");
					Pattern pattern = compile(patternText, false, "pattern");
					String kind = args.optString("kind", "any");
					if (!List.of("class", "field", "method", "any").contains(kind))
						throw new ToolException("Unknown kind '" + kind + "'. Use class, field, method or any.");
					boolean wantClasses = kind.equals("class") || kind.equals("any");
					boolean wantFields = kind.equals("field") || kind.equals("any");
					boolean wantMethods = kind.equals("method") || kind.equals("any");
					boolean wantInitializers = patternText.indexOf('<') >= 0;
					boolean fullClassNames = patternText.indexOf('/') >= 0;

					Scope scope = new Scope(args.optString("package", null), false);
					Map<Hit, Integer> hits = new HashMap<>();
					if (wantFields || wantMethods) {
						Results results = context.service(SearchService.class)
								.search(workspace, new DeclarationQuery(null, regexPredicate(pattern), null), scope);
						context.checkCancelled();
						for (Result<?> result : results) {
							if (!(result.getValue() instanceof MemberDeclaration declaration))
								continue;
							boolean isMethod = declaration.isMethodDeclaration();
							if (isMethod ? !wantMethods : !wantFields)
								continue;
							if (isMethod && declaration.name().startsWith("<") && !wantInitializers)
								continue;
							String modifiers = Lookups.access(isMethod ? AccessFlag.Type.METHOD : AccessFlag.Type.FIELD, declaration.access());
							String detail = "[" + (modifiers.isEmpty() ? "" : modifiers + " ") + (isMethod ? "method" : "field") + "]";
							hits.merge(hit(result.getPath(), detail), 1, Integer::sum);
						}
					}
					if (wantClasses) {
						Workspaces.classes(workspace).filter(node -> scope.includes(node.getValue().getName())).forEach(node -> {
							if (!wantFields && !wantMethods)
								scope.classes.incrementAndGet();
							String className = node.getValue().getName();
							String matched = fullClassNames ? className : className.substring(className.lastIndexOf('/') + 1);
							if (pattern.matcher(matched).find())
								hits.merge(hit(node, "[" + WorkspaceTools.kind(node.getValue()) + "]"), 1, Integer::sum);
						});
					}
					if (hits.isEmpty())
						return "No " + (kind.equals("any") ? "class, field or method" : kind) + " has a name matching '" + patternText +
								"' in " + scope.describe() + "." + scope.warning();
					return page(hits, args, "declarations") + scope.warning();
				})
				.build();
	}

	@Nonnull
	private AgentTool searchInstructions() {
		return AgentTool.builder("search_instructions")
				.description("Find methods by the bytecode instructions they contain. Pass one regular expression per instruction: " +
						"a method matches where consecutive instructions match the expressions in order, so a single expression finds " +
						"one instruction and several find a sequence. Labels and line numbers are skipped, they are not instructions. " +
						"Instructions are matched in the text form of Recaf's assembler, for example " +
						"'invokestatic java/lang/System.getProperty (Ljava/lang/String;)Ljava/lang/String;', " +
						"'getstatic java/lang/System.out Ljava/io/PrintStream;', 'ldc \"text\"', 'sipush 1337', 'new java/lang/Thread', 'aload v0', 'ifeq A'. " +
						"Local variables always appear with generated names like v0 or i4 and every jump target as label A, so match those loosely. " +
						"Each result line shows 'class.method(descriptor)' followed by the first matching instructions, joined by ' ; '. " +
						"Android (DEX) classes are searched through their translation to JVM bytecode, which is slow the first time " +
						"on a large app: pass 'package' there. Prefer search_references and search_strings when they can answer the question.")
				.schema(ToolSchema.object()
						.stringList("instructions", "Regular expressions, each matching the text of one instruction, in the order the instructions " +
								"must follow each other. For example ['ldc \"AES', 'invokestatic javax/crypto/Cipher\\.getInstance'] or ['^athrow$']. " +
								"A match anywhere in the instruction text counts, anchor with ^ and $ to match it whole. " +
								"Case-insensitive, prefix an expression with (?-i) to make it case-sensitive.")
						.optString("package", PACKAGE_DESCRIPTION)
						.paging(DEFAULT_LIMIT))
				.handler(args -> {
					Workspace workspace = context.workspace();
					List<String> expressions = args.stringList("instructions");
					if (expressions.isEmpty())
						throw new ToolException("Missing required argument 'instructions': pass at least one regular expression.");
					if (expressions.size() > MAX_INSTRUCTION_LINES)
						throw new ToolException("At most " + MAX_INSTRUCTION_LINES + " consecutive instructions can be matched, " +
								expressions.size() + " were passed.");
					List<Pattern> patterns = new ArrayList<>();
					for (String expression : expressions)
						patterns.add(compile(expression, false, "instructions"));

					Scope scope = new Scope(args.optString("package", null), false);
					InstructionTextQuery query = new InstructionTextQuery(patterns);
					Results results = context.service(SearchService.class).search(workspace, query, scope);
					context.checkCancelled();

					// The query reports each method once: the number of matches in it, then the text of the first.
					Map<Hit, Integer> hits = new HashMap<>();
					for (Result<?> result : results) {
						if (!(result.getValue() instanceof String text))
							continue;
						int split = text.indexOf('\n');
						int matches = Integer.parseInt(text.substring(0, split));
						String detail = Text.shorten(text.substring(split + 1).replace("\n", " ; "), MAX_TEXT * 2) +
								(matches > 1 ? "  (+" + (matches - 1) + " more in this method)" : "");
						hits.merge(hit(result.getPath(), detail), 1, Integer::sum);
					}
					String skipped = query.untranslatable.get() == 0 ? "" : "\n[" + query.untranslatable.get() +
							" Android classes could not be translated to JVM bytecode and were not searched.]";
					if (hits.isEmpty())
						return "No method contains " + (patterns.size() == 1 ? "an instruction" : patterns.size() + " consecutive instructions") +
								" matching " + expressions + " in " + scope.describe() + "." + skipped + scope.warning();
					return page(hits, args, "methods") + skipped + scope.warning();
				})
				.build();
	}

	@Nonnull
	private AgentTool searchFiles() {
		return AgentTool.builder("search_files")
				.description("Search the content of the text files in the workspace with a regular expression, like grep. " +
						"Use it for configuration, manifests, properties, scripts, SQL, HTML and other resources packaged with the program. " +
						"Each result line shows 'path:line: text'. Long lines are shortened around the match. " +
						"Binary files are not searched, which includes compiled Android XML such as AndroidManifest.xml in an APK " +
						"(use get_android_manifest for that) and classes (use search_strings).")
				.schema(ToolSchema.object()
						.string("pattern", "Regular expression that must match somewhere in a line, for example 'https?://' or 'api[_-]?key'. " +
								"Case-insensitive, prefix it with (?-i) to make it case-sensitive.")
						.optString("path_pattern", "Only search files whose path contains a match of this regular expression, " +
								"for example '\\.properties$' or '^META-INF/'. Case-insensitive.")
						.paging(DEFAULT_LIMIT))
				.handler(args -> {
					Workspace workspace = context.workspace();
					Pattern pattern = compile(args.string("pattern"), false, "pattern");
					String pathText = blankToNull(args.optString("path_pattern", null));
					Pattern pathPattern = pathText == null ? null : compile(pathText, false, "path_pattern");

					List<Hit> hits = new ArrayList<>();
					int textFiles = 0;
					int otherFiles = 0;
					boolean overflowed = false;
					List<FileInfo> files = Workspaces.files(workspace).map(node -> node.getValue()).collect(Collectors.toList());
					for (FileInfo file : files) {
						context.checkCancelled();
						if (overflowed || (pathPattern != null && !pathPattern.matcher(file.getName()).find()))
							continue;
						if (!file.isTextFile()) {
							otherFiles++;
							continue;
						}
						textFiles++;
						String[] lines = file.asTextFile().getTextLines();
						for (int i = 0; i < lines.length && !overflowed; i++) {
							Matcher matcher = pattern.matcher(lines[i]);
							if (!matcher.find())
								continue;
							if (hits.size() >= MAX_MATCHES)
								overflowed = true;
							else
								hits.add(new Hit(file.getName(), i + 1, "", shortenAround(lines[i], matcher.start())));
						}
					}
					if (hits.isEmpty())
						return "No line matches '" + args.string("pattern") + "' in " + textFiles + " text files" +
								(pathText == null ? "" : " with a path matching '" + pathText + "'") +
								(otherFiles == 0 ? "" : " (" + otherFiles + " binary files were not searched)") + ".";
					String warning = overflowed ? "\n[The search stopped after " + MAX_MATCHES +
							" matching lines, so this list is incomplete. Use a more specific pattern or 'path_pattern'.]" : "";
					hits.sort(null);
					return Text.page(hits, args.offset(), args.limit(DEFAULT_LIMIT, MAX_LIMIT), "matching lines",
							hit -> hit.location() + ": " + hit.detail()) + warning;
				})
				.build();
	}

	/**
	 * @param hits
	 * 		Hits, with the number of times each was found.
	 * @param args
	 * 		Arguments holding the paging window.
	 * @param noun
	 * 		Plural noun describing the hits.
	 *
	 * @return One page of the hits in a stable order, one per line.
	 */
	@Nonnull
	private static String page(@Nonnull Map<Hit, Integer> hits, @Nonnull ToolArgs args, @Nonnull String noun) {
		List<Map.Entry<Hit, Integer>> entries = new ArrayList<>(new TreeMap<>(hits).entrySet());
		return Text.page(entries, args.offset(), args.limit(DEFAULT_LIMIT, MAX_LIMIT), noun, entry ->
				entry.getKey().location() + "  " + entry.getKey().detail() + (entry.getValue() > 1 ? "  (x" + entry.getValue() + ")" : ""));
	}

	/**
	 * @param path
	 * 		Path of a search result.
	 * @param detail
	 * 		What was found there.
	 *
	 * @return Hit at the class, member or file line the path points into.
	 */
	@Nonnull
	private static Hit hit(@Nonnull PathNode<?> path, @Nonnull String detail) {
		ClassInfo declaringClass = path.getValueOfType(ClassInfo.class);
		if (declaringClass != null) {
			ClassMember member = path.getValueOfType(ClassMember.class);
			// A multi-release JAR holds several variants of a class under the same name, tell them apart.
			if (path.getValueOfType(Bundle.class) instanceof VersionedJvmClassBundle versioned)
				detail += "  [variant in META-INF/versions/" + versioned.version() + "]";
			return new Hit(declaringClass.getName(), 0, member == null ? "" : Lookups.signature(member), detail);
		}
		FileInfo file = path.getValueOfType(FileInfo.class);
		int line = path instanceof LineNumberPathNode linePath ? linePath.getValue() : 0;
		return new Hit(file == null ? String.valueOf(path.getValue()) : file.getName(), line, "", detail);
	}

	/**
	 * @param path
	 * 		Path of a reference search result.
	 *
	 * @return Key that is equal for results found at the same instruction, or {@code null} when the path is not an instruction.
	 */
	@Nullable
	private static String instructionKey(@Nonnull PathNode<?> path) {
		int index;
		if (path instanceof JvmInstructionPathNode instruction)
			index = instruction.getInstructionIndex();
		else if (path instanceof AndroidInstructionPathNode instruction)
			index = instruction.getInstructionIndex();
		else
			return null;
		// Variants of a class in a multi-release JAR share their name, but not their identity.
		return System.identityHashCode(path.getValueOfType(ClassInfo.class)) + ":" + hit(path, "").location() + '#' + index;
	}

	/**
	 * @param path
	 * 		Path of a reference search result.
	 * @param target
	 * 		The referenced class or member.
	 * @param verb
	 * 		Verb describing the reference when the path says nothing more specific.
	 *
	 * @return Description of how the target is used at the path.
	 */
	@Nonnull
	private static String describeUse(@Nonnull PathNode<?> path, @Nonnull String target, @Nonnull String verb) {
		return switch (path) {
			case JvmInstructionPathNode instruction ->
					Text.shorten(JvmPrinterUtil.toString(instruction.getValue()).replace('\n', ' '), MAX_TEXT * 2);
			case AndroidInstructionPathNode instruction ->
					Text.shorten(DEX_REGISTERS.matcher(instruction.getValue().toString()).replaceAll(""), MAX_TEXT * 2);
			case ThrowsPathNode ignored -> "declares throws " + target;
			case CatchPathNode ignored -> "catches " + target;
			case LocalVariablePathNode variable -> "local variable " + variable.getValue().getName() + " of type " + target;
			case AnnotationPathNode annotation -> "annotation " + annotation.getValue().getDescriptor() + " " + verb + " " + target;
			case ClassMemberPathNode member when member.getValue().getDescriptor().contains("L" + target + ";") ->
					(member.isField() ? "field of type " : "signature mentions ") + target;
			default -> verb + " " + target + " in an annotation or constant value";
		};
	}

	/**
	 * @param text
	 * 		Regular expression.
	 * @param caseSensitive
	 * 		Whether case must match.
	 * @param argument
	 * 		Name of the argument the expression was passed in.
	 *
	 * @return Compiled pattern.
	 *
	 * @throws ToolException
	 * 		When the expression is invalid.
	 */
	@Nonnull
	private static Pattern compile(@Nonnull String text, boolean caseSensitive, @Nonnull String argument) {
		try {
			return Pattern.compile(text, caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
		} catch (PatternSyntaxException ex) {
			throw new ToolException("Invalid regular expression '" + text + "' in '" + argument + "': " + ex.getDescription());
		}
	}

	/**
	 * @param pattern
	 * 		Pattern to match.
	 *
	 * @return Predicate for Recaf's queries that accepts text containing a match of the pattern.
	 */
	@Nonnull
	private static StringPredicate regexPredicate(@Nonnull Pattern pattern) {
		return new StringPredicate(StringPredicateProvider.KEY_REGEX_PARTIAL, text -> pattern.matcher(text).find());
	}

	private static int firstMatch(@Nonnull Pattern pattern, @Nonnull String text) {
		Matcher matcher = pattern.matcher(text);
		return matcher.find() ? matcher.start() : 0;
	}

	/**
	 * @param text
	 * 		Matched string.
	 * @param matchStart
	 * 		Index of the match within the string.
	 *
	 * @return The string as a quoted literal. A long string is cut down to the part around the match, with its full length noted.
	 */
	@Nonnull
	private static String excerpt(@Nonnull String text, int matchStart) {
		if (text.length() <= MAX_TEXT)
			return Text.quote(text);
		return Text.quote(shortenAround(text, matchStart)) + " (" + text.length() + " chars)";
	}

	/**
	 * @param text
	 * 		Text to shorten.
	 * @param matchStart
	 * 		Index of the part of interest.
	 *
	 * @return The text if it is short, otherwise a window of it that begins shortly before the part of interest.
	 */
	@Nonnull
	private static String shortenAround(@Nonnull String text, int matchStart) {
		if (text.length() <= MAX_TEXT)
			return text;
		int start = Math.max(0, Math.min(matchStart, text.length()) - MAX_TEXT / 4);
		return (start > 0 ? "…" : "") + Text.shorten(text.substring(start), MAX_TEXT);
	}

	/**
	 * @param text
	 * 		Number as passed by the model.
	 * @param argument
	 * 		Name of the argument the number was passed in.
	 *
	 * @return The parsed number.
	 *
	 * @throws ToolException
	 * 		When the text is not a number.
	 */
	@Nonnull
	private static ParsedNumber parseNumber(@Nonnull String text, @Nonnull String argument) {
		String cleaned = text.trim().replace("_", "");
		try {
			boolean negative = cleaned.startsWith("-");
			String unsigned = negative || cleaned.startsWith("+") ? cleaned.substring(1) : cleaned;
			String lower = unsigned.toLowerCase(Locale.ROOT);
			if (lower.startsWith("0x")) {
				String digits = lower.endsWith("l") ? lower.substring(2, lower.length() - 1) : lower.substring(2);
				long value = Long.parseUnsignedLong(digits, 16);
				if (negative)
					value = -value;
				return new ParsedNumber(narrow(value), true);
			}
			boolean wholeDigits = !lower.isEmpty() && lower.chars().allMatch(c -> c >= '0' && c <= '9');
			if (wholeDigits)
				return new ParsedNumber(narrow(Long.parseLong(cleaned)), false);
			if (lower.endsWith("l"))
				return new ParsedNumber(Long.parseLong(cleaned.substring(0, cleaned.length() - 1)), false);
			if (lower.endsWith("f"))
				return new ParsedNumber(Float.parseFloat(cleaned), false);
			return new ParsedNumber(Double.parseDouble(cleaned), false);
		} catch (NumberFormatException ex) {
			throw new ToolException("Argument '" + argument + "' is not a number: '" + text + "'. " +
					"Use decimal like 1337, -5 or 2.5, or hexadecimal like 0xCAFEBABE.");
		}
	}

	@Nonnull
	private static Number narrow(long value) {
		if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE)
			return (int) value;
		return value;
	}

	/**
	 * @param text
	 * 		Number as passed by the model.
	 * @param withRawBits
	 * 		Whether a floating point number should also be found by its raw bits, the form DEX constants take.
	 * @param rawBits
	 * 		Receives the raw bit patterns that were added, mapped to the floating point number they stand for.
	 *
	 * @return The numbers a constant may equal to count as a match.
	 */
	@Nonnull
	private static List<Number> equalNumbers(@Nonnull String text, boolean withRawBits, @Nonnull Map<Number, String> rawBits) {
		ParsedNumber parsed = parseNumber(text, "value");
		List<Number> values = new ArrayList<>();
		values.add(parsed.value());
		// 0xCAFEBABE is written as a positive number but stored in an int as a negative one.
		if (parsed.hex() && parsed.value() instanceof Long wide && wide > Integer.MAX_VALUE && wide <= 0xFFFFFFFFL)
			values.add((int) wide.longValue());
		if (parsed.value() instanceof Double wide) {
			// A float constant holds the nearest float, which differs from the double of the same text.
			float single = (float) wide.doubleValue();
			boolean asFloat = !text.trim().toLowerCase(Locale.ROOT).endsWith("d");
			if (asFloat)
				values.add(single);
			if (withRawBits) {
				addRawBits(values, rawBits, Double.doubleToRawLongBits(wide), "double " + wide);
				if (asFloat)
					addRawBits(values, rawBits, Float.floatToRawIntBits(single), "float " + single);
			}
		} else if (parsed.value() instanceof Float single && withRawBits) {
			addRawBits(values, rawBits, Float.floatToRawIntBits(single), "float " + single);
		}
		return values;
	}

	private static void addRawBits(@Nonnull List<Number> values, @Nonnull Map<Number, String> rawBits,
	                               @Nonnull Number bits, @Nonnull String meaning) {
		// Small whole numbers are not credible bit patterns, and would match unrelated constants.
		if (bits.longValue() >= -0xFFFF && bits.longValue() <= 0xFFFF)
			return;
		values.add(bits);
		rawBits.put(bits, meaning);
	}

	/**
	 * @param number
	 * 		Number found by a search.
	 *
	 * @return The number as a Java literal, with its hexadecimal form when that is likely to be the recognizable one.
	 */
	@Nonnull
	private static String describe(@Nonnull Number number) {
		if (number instanceof Float)
			return number + "F";
		if (number instanceof Double)
			return number.toString();
		long value = number.longValue();
		boolean isLong = number instanceof Long;
		String text = value + (isLong ? "L" : "");
		if (value >= 0 && value <= 9)
			return text;
		String hex = isLong ? Long.toHexString(value) : Integer.toHexString((int) value);
		return text + " (0x" + hex.toUpperCase(Locale.ROOT) + ")";
	}

	@Nullable
	private static String blankToNull(@Nullable String text) {
		return text == null || text.isBlank() ? null : text.trim();
	}

	/**
	 * A parsed numeric argument.
	 *
	 * @param value
	 * 		The number.
	 * @param hex
	 *        {@code true} when it was written in hexadecimal.
	 */
	private record ParsedNumber(@Nonnull Number value, boolean hex) {}

	/**
	 * A place something was found: a class, a member of a class, or a line of a file.
	 *
	 * @param owner
	 * 		Name of the class, or path of the file.
	 * @param line
	 * 		Line of the file, or 0 for a class.
	 * @param member
	 * 		Signature of the member of the class, or an empty string.
	 * @param detail
	 * 		What was found there.
	 */
	private record Hit(@Nonnull String owner, int line, @Nonnull String member, @Nonnull String detail) implements Comparable<Hit> {
		@Nonnull
		String location() {
			if (line > 0)
				return owner + ":" + line;
			return member.isEmpty() ? owner : owner + "." + member;
		}

		@Override
		public int compareTo(@Nonnull Hit other) {
			int result = owner.compareTo(other.owner);
			if (result == 0) result = Integer.compare(line, other.line);
			if (result == 0) result = member.compareTo(other.member);
			if (result == 0) result = detail.compareTo(other.detail);
			return result;
		}
	}

	/**
	 * Limits a search to a package, bounds how many results it may collect and how long it may take,
	 * and stops it when the user cancels.
	 */
	private final class Scope implements SearchFeedback {
		private final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SEARCH_TIMEOUT_SECONDS);
		private final AtomicInteger accepted = new AtomicInteger();
		private final AtomicInteger classes = new AtomicInteger();
		private final AtomicInteger files = new AtomicInteger();
		private final String prefix;
		private final boolean includeFiles;
		private volatile boolean overflowed;
		private volatile boolean timedOut;

		/**
		 * @param packageName
		 * 		Package or class to limit the search to, or {@code null} to search everything.
		 * @param includeFiles
		 * 		Whether files are searched in addition to classes.
		 */
		private Scope(@Nullable String packageName, boolean includeFiles) {
			String name = blankToNull(packageName);
			this.prefix = name == null ? null : Lookups.internalName(name).replaceAll("/+$", "");
			this.includeFiles = includeFiles;
		}

		/**
		 * @param name
		 * 		Class name or file path.
		 *
		 * @return {@code true} when it lies within the scope.
		 */
		private boolean includes(@Nonnull String name) {
			if (prefix == null || prefix.isEmpty())
				return true;
			return name.startsWith(prefix) && (name.length() == prefix.length() ||
					name.charAt(prefix.length()) == '/' || name.charAt(prefix.length()) == '$');
		}

		/**
		 * @return Description of what was searched, such as {@code 12 classes and 3 files in sample/util}.
		 */
		@Nonnull
		private String describe() {
			return classes.get() + " classes" + (includeFiles ? " and " + files.get() + " files" : "") +
					(prefix == null || prefix.isEmpty() ? "" : " in '" + prefix + "'");
		}

		/**
		 * @return Note on why the results are incomplete, or an empty string when the search ran to completion.
		 */
		@Nonnull
		private String warning() {
			if (overflowed)
				return "\n[The search stopped after " + MAX_MATCHES + " matches, so this list is incomplete and may differ between calls. " +
						"Narrow it with 'package' or a more specific query.]";
			if (timedOut)
				return "\n[The search was stopped after " + SEARCH_TIMEOUT_SECONDS + " seconds, so this list is incomplete. " +
						"Narrow it with 'package'.]";
			return "";
		}

		@Override
		public boolean hasRequestedCancellation() {
			if (overflowed || timedOut || context.isCancelled())
				return true;
			if (System.nanoTime() > deadline)
				timedOut = true;
			return timedOut;
		}

		@Override
		public boolean doVisitClass(@Nonnull ClassInfo cls) {
			if (!includes(cls.getName()))
				return false;
			classes.incrementAndGet();
			return true;
		}

		@Override
		public boolean doVisitFile(@Nonnull FileInfo file) {
			if (!includeFiles || !includes(file.getName()))
				return false;
			files.incrementAndGet();
			return true;
		}

		@Override
		public boolean doAcceptResult(@Nonnull Result<?> result) {
			if (accepted.incrementAndGet() <= MAX_MATCHES)
				return true;
			overflowed = true;
			return false;
		}
	}

	/**
	 * Query for methods containing consecutive instructions whose disassembled text matches a list of patterns.
	 * <p>
	 * Recaf's own {@link software.coley.recaf.services.search.query.InstructionQuery} compares the same text, but it
	 * counts labels and line numbers as instructions, which breaks a sequence at every statement boundary, it never
	 * tests the last instruction of a method, and it does not visit Android classes. This query runs through the
	 * same {@link SearchService} without those limits.
	 * <p>
	 * Each method with a match yields one result: the number of matches in the method, a line break, and the text of
	 * the instructions of the first match, one per line.
	 */
	private static final class InstructionTextQuery implements JvmClassQuery, AndroidClassQuery {
		private final AtomicInteger untranslatable = new AtomicInteger();
		private final List<Pattern> patterns;

		/**
		 * @param patterns
		 * 		Patterns, each matching the text of one instruction.
		 */
		private InstructionTextQuery(@Nonnull List<Pattern> patterns) {
			this.patterns = patterns;
		}

		@Nonnull
		@Override
		public JvmClassSearchVisitor visitor(@Nullable JvmClassSearchVisitor delegate) {
			return (resultSink, classPath, classInfo) -> {
				if (delegate != null)
					delegate.visit(resultSink, classPath, classInfo);
				scan(resultSink, classPath, classInfo);
			};
		}

		@Nonnull
		@Override
		public AndroidClassSearchVisitor visitor(@Nullable AndroidClassSearchVisitor delegate) {
			return (resultSink, classPath, classInfo) -> {
				if (delegate != null)
					delegate.visit(resultSink, classPath, classInfo);
				JvmClassInfo translated;
				try {
					translated = Lookups.jvmView(classInfo);
				} catch (ToolException ex) {
					untranslatable.incrementAndGet();
					return;
				}
				scan(resultSink, classPath, translated);
			};
		}

		private void scan(@Nonnull ResultSink resultSink, @Nonnull ClassPathNode classPath, @Nonnull JvmClassInfo jvmClass) {
			ClassNode node = new ClassNode();
			jvmClass.getClassReader().accept(node, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
			int length = patterns.size();
			for (MethodNode method : node.methods) {
				ClassMemberPathNode memberPath = classPath.child(method.name, method.desc);
				if (memberPath == null)
					continue;
				List<AbstractInsnNode> instructions = new ArrayList<>(method.instructions.size());
				for (AbstractInsnNode instruction : method.instructions)
					if (instruction.getOpcode() >= 0)
						instructions.add(instruction);
				String[] text = new String[instructions.size()];
				int matches = 0;
				int first = -1;
				for (int start = 0; start + length <= text.length; start++) {
					boolean matched = true;
					for (int i = 0; i < length && matched; i++) {
						int index = start + i;
						if (text[index] == null)
							text[index] = JvmPrinterUtil.toString(instructions.get(index));
						matched = patterns.get(i).matcher(text[index]).find();
					}
					if (matched && matches++ == 0)
						first = start;
				}
				// One result per method keeps a pattern that matches most instructions from flooding the search.
				if (matches > 0)
					resultSink.accept(memberPath.childInsn(instructions.get(first), first),
							matches + "\n" + String.join("\n", Arrays.asList(text).subList(first, first + length)));
			}
		}
	}
}
