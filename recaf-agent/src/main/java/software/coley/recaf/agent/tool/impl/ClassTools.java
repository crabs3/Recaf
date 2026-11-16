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
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.InnerClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.annotation.AnnotationInfo;
import software.coley.recaf.info.member.FieldMember;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.services.decompile.DecompileResult;
import software.coley.recaf.services.decompile.DecompilerManager;
import software.coley.recaf.services.decompile.JvmDecompiler;
import software.coley.recaf.util.AccessFlag;
import software.coley.recaf.workspace.model.Workspace;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Tools for inspecting individual classes.
 *
 * @author Recaf agent tooling
 */
public final class ClassTools implements ToolProvider {
	private static final int DECOMPILE_TIMEOUT_SECONDS = 180;
	private static final int DEFAULT_SOURCE_LINES = 400;
	private final ToolContext context;

	/**
	 * @param context
	 * 		Context the tools operate in.
	 */
	public ClassTools(@Nonnull ToolContext context) {
		this.context = context;
	}

	@Nonnull
	@Override
	public List<AgentTool> tools() {
		return List.of(getClassInfo(), decompileClass());
	}

	@Nonnull
	private AgentTool getClassInfo() {
		return AgentTool.builder("get_class_info")
				.description("Show the structure of a class without its code: modifiers, super class, interfaces, " +
						"annotations, inner classes, and every field and method with its descriptor. " +
						"Much cheaper than decompiling, use it to decide which parts of a class deserve a closer look.")
				.schema(ToolSchema.object().string("class", "Name of the class, for example 'com/example/Main' or 'com.example.Main'."))
				.handler(args -> {
					Workspace workspace = context.workspace();
					ClassInfo info = Lookups.requireClass(workspace, args.string("class"), true).getValue();
					StringBuilder sb = new StringBuilder();

					sb.append(Lookups.access(AccessFlag.Type.CLASS, info.getAccess())).append(' ')
							.append(isTypeKeywordInAccess(info) ? "" : "class ").append(info.getName()).append('\n');
					sb.append("  extends ").append(info.getSuperName()).append('\n');
					if (!info.getInterfaces().isEmpty())
						sb.append("  implements ").append(String.join(", ", info.getInterfaces())).append('\n');
					if (info.isJvmClass())
						sb.append("  class file version ").append(info.asJvmClass().getVersion())
								.append(" (Java ").append(info.asJvmClass().getVersion() - JvmClassInfo.BASE_VERSION).append(")\n");
					else
						sb.append("  Android (DEX) class\n");
					if (info.getSourceFileName() != null)
						sb.append("  source file ").append(info.getSourceFileName()).append('\n');
					if (info.getSignature() != null)
						sb.append("  generic signature ").append(info.getSignature()).append('\n');
					if (info.getOuterClassName() != null)
						sb.append("  outer class ").append(info.getOuterClassName()).append('\n');
					String annotations = annotations(info.getAnnotations());
					if (annotations != null)
						sb.append("  annotations ").append(annotations).append('\n');
					if (WorkspaceTools.modifiedClassNames(workspace.getPrimaryResource()).contains(info.getName()))
						sb.append("  modified in this session\n");
					if (workspace.findClass(false, info.getName()) == null)
						sb.append("  runtime library class, not part of the analyzed program\n");

					List<InnerClassInfo> inners = info.getInnerClasses().stream()
							.filter(inner -> !inner.isExternalReference() && !inner.isSelfReference())
							.collect(Collectors.toList());
					if (!inners.isEmpty()) {
						sb.append("\nInner classes (").append(inners.size()).append("):\n");
						for (InnerClassInfo inner : inners)
							sb.append("  ").append(inner.getInnerClassName()).append('\n');
					}

					sb.append("\nFields (").append(info.getFields().size()).append("):\n");
					for (FieldMember field : info.getFields()) {
						sb.append("  ").append(prefix(Lookups.access(AccessFlag.Type.FIELD, field.getAccess())))
								.append(field.getName()).append(' ').append(field.getDescriptor());
						if (field.getDefaultValue() != null)
							sb.append(" = ").append(constant(field.getDefaultValue()));
						sb.append('\n');
					}

					sb.append("\nMethods (").append(info.getMethods().size()).append("):\n");
					for (MethodMember method : info.getMethods()) {
						sb.append("  ").append(prefix(Lookups.access(AccessFlag.Type.METHOD, method.getAccess())))
								.append(method.getName()).append(method.getDescriptor());
						if (!method.getThrownTypes().isEmpty())
							sb.append(" throws ").append(String.join(", ", method.getThrownTypes()));
						sb.append('\n');
					}
					return sb.toString().stripTrailing();
				})
				.build();
	}

	@Nonnull
	private AgentTool decompileClass() {
		return AgentTool.builder("decompile_class")
				.description("Decompile a class to Java source. Android classes are translated from DEX first. " +
						"Long classes are returned in pages of lines. If the output looks wrong or incomplete, " +
						"which happens with obfuscated code, try another decompiler or read the bytecode with disassemble_method.")
				.schema(ToolSchema.object()
						.string("class", "Name of the class, for example 'com/example/Main' or 'com.example.Main'.")
						.optString("decompiler", "Decompiler to use: CFR, Procyon or Vineflower. Defaults to the configured one.")
						.optInteger("start_line", "First line of the source to return, starting at 1. Default 1.")
						.optInteger("max_lines", "Maximum number of lines to return. Default " + DEFAULT_SOURCE_LINES + "."))
				.handler(args -> {
					Workspace workspace = context.workspace();
					ClassInfo info = Lookups.requireClass(workspace, args.string("class")).getValue();
					JvmClassInfo jvmClass = Lookups.jvmView(info);
					DecompilerManager manager = context.service(DecompilerManager.class);
					JvmDecompiler decompiler = pickDecompiler(manager, args.optString("decompiler", null));
					String source = decompile(manager, decompiler, workspace, jvmClass);
					int startLine = Math.max(1, args.optInt("start_line", 1));
					int maxLines = Math.max(1, Math.min(2000, args.optInt("max_lines", DEFAULT_SOURCE_LINES)));
					return "// " + info.getName() + " decompiled with " + decompiler.getName() + "\n" +
							Text.lines(source, startLine, maxLines, false);
				})
				.build();
	}

	/**
	 * @param manager
	 * 		Decompiler manager.
	 * @param name
	 * 		Requested decompiler name, or {@code null} for the configured default.
	 *
	 * @return The decompiler to use.
	 *
	 * @throws ToolException
	 * 		When there is no decompiler of the requested name.
	 */
	@Nonnull
	static JvmDecompiler pickDecompiler(@Nonnull DecompilerManager manager, @Nullable String name) {
		if (name == null || name.isBlank())
			return manager.getTargetJvmDecompiler();
		for (JvmDecompiler decompiler : manager.getJvmDecompilers())
			if (decompiler.getName().equalsIgnoreCase(name.trim()))
				return decompiler;
		throw new ToolException("There is no decompiler named '" + name + "'. Available: " +
				manager.getJvmDecompilers().stream().map(JvmDecompiler::getName).collect(Collectors.joining(", ")));
	}

	/**
	 * @param manager
	 * 		Decompiler manager.
	 * @param decompiler
	 * 		Decompiler to use.
	 * @param workspace
	 * 		Workspace the class belongs to.
	 * @param jvmClass
	 * 		Class to decompile.
	 *
	 * @return Decompiled source.
	 *
	 * @throws ToolException
	 * 		When decompilation fails or takes too long.
	 */
	@Nonnull
	static String decompile(@Nonnull DecompilerManager manager, @Nonnull JvmDecompiler decompiler,
	                        @Nonnull Workspace workspace, @Nonnull JvmClassInfo jvmClass) throws Exception {
		DecompileResult result;
		try {
			result = manager.decompile(decompiler, workspace, jvmClass).get(DECOMPILE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
		} catch (TimeoutException ex) {
			throw new ToolException(decompiler.getName() + " did not finish decompiling '" + jvmClass.getName() + "' within " +
					DECOMPILE_TIMEOUT_SECONDS + " seconds. Try another decompiler, or read the bytecode with disassemble_method.");
		}
		String text = result.getText();
		if (result.getType() != DecompileResult.ResultType.SUCCESS || text == null) {
			Throwable error = result.getException();
			throw new ToolException(decompiler.getName() + " failed to decompile '" + jvmClass.getName() + "'" +
					(error == null ? "." : ": " + error) + " Try another decompiler, or read the bytecode with disassemble_method.");
		}
		return text;
	}

	private static boolean isTypeKeywordInAccess(@Nonnull ClassInfo info) {
		int access = info.getAccess();
		return AccessFlag.isInterface(access) || AccessFlag.isEnum(access) || AccessFlag.isAnnotation(access) || AccessFlag.isModule(access);
	}

	@Nonnull
	private static String prefix(@Nonnull String flags) {
		return flags.isEmpty() ? "" : flags + " ";
	}

	@Nullable
	private static String annotations(@Nonnull List<AnnotationInfo> annotations) {
		if (annotations.isEmpty())
			return null;
		return annotations.stream().map(AnnotationInfo::getDescriptor).collect(Collectors.joining(", "));
	}

	@Nonnull
	private static String constant(@Nonnull Object value) {
		if (value instanceof String text)
			return Text.quote(Text.shorten(text, 200));
		return String.valueOf(value);
	}
}
