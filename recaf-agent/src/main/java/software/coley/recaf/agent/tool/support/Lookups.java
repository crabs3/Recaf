package software.coley.recaf.agent.tool.support;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import software.coley.recaf.agent.tool.ToolException;
import software.coley.recaf.info.ClassInfo;
import software.coley.recaf.info.JvmClassInfo;
import software.coley.recaf.info.member.ClassMember;
import software.coley.recaf.info.member.FieldMember;
import software.coley.recaf.info.member.MethodMember;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.path.FilePathNode;
import software.coley.recaf.util.AccessFlag;
import software.coley.recaf.workspace.model.Workspace;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Resolves the names a model passes to tools into workspace content.
 * <p>
 * Models refer to classes in whatever form they last saw: {@code com.example.Foo}, {@code com/example/Foo},
 * {@code Lcom/example/Foo;} or {@code Foo.class}. These lookups accept all of them, and when nothing matches the
 * error lists the closest candidates so the next call can succeed.
 *
 * @author Recaf agent tooling
 */
public final class Lookups {
	private static final int MAX_SUGGESTIONS = 8;

	private Lookups() {}

	/**
	 * @param name
	 * 		Class name in any common form.
	 *
	 * @return The internal name, such as {@code com/example/Foo}.
	 */
	@Nonnull
	public static String internalName(@Nonnull String name) {
		String text = name.trim();
		if (text.length() > 2 && text.charAt(0) == 'L' && text.endsWith(";") && text.indexOf('/') > 0)
			text = text.substring(1, text.length() - 1);
		if (text.endsWith(".class"))
			text = text.substring(0, text.length() - ".class".length());
		else if (text.endsWith(".java"))
			text = text.substring(0, text.length() - ".java".length());
		// A name that already uses slashes is internal, any remaining dots belong to it.
		if (text.indexOf('/') < 0)
			text = text.replace('.', '/');
		return text;
	}

	/**
	 * @param workspace
	 * 		Workspace to search.
	 * @param name
	 * 		Class name in any common form.
	 *
	 * @return Path to the class.
	 *
	 * @throws ToolException
	 * 		When the workspace has no such class. The message suggests similarly named classes.
	 */
	@Nonnull
	public static ClassPathNode requireClass(@Nonnull Workspace workspace, @Nonnull String name) {
		return requireClass(workspace, name, false);
	}

	/**
	 * @param workspace
	 * 		Workspace to search.
	 * @param name
	 * 		Class name in any common form.
	 * @param includeRuntime
	 *        {@code true} to also resolve classes of the runtime library <i>(such as {@code java/lang/String})</i>,
	 * 		which are not part of the analyzed program and cannot be modified.
	 *
	 * @return Path to the class.
	 *
	 * @throws ToolException
	 * 		When there is no such class. The message suggests similarly named classes.
	 */
	@Nonnull
	public static ClassPathNode requireClass(@Nonnull Workspace workspace, @Nonnull String name, boolean includeRuntime) {
		String internal = internalName(name);
		ClassPathNode path = workspace.findClass(includeRuntime, internal);
		if (path != null)
			return path;

		// Inner classes are often written with dots: 'Outer.Inner' -> 'Outer$Inner'
		String candidate = internal;
		int slash;
		while ((slash = candidate.lastIndexOf('/')) > 0) {
			candidate = candidate.substring(0, slash) + '$' + candidate.substring(slash + 1);
			path = workspace.findClass(includeRuntime, candidate);
			if (path != null)
				return path;
		}

		if (!includeRuntime && workspace.findClass(true, internal) != null)
			throw new ToolException("'" + internal + "' is a class of the runtime library, not of the analyzed program. " +
					"Its structure can be viewed with get_class_info, but it has no code to show and cannot be modified.");

		String simpleName = internal.substring(internal.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
		List<String> exact = new ArrayList<>();
		List<String> partial = new ArrayList<>();
		Workspaces.classes(workspace).forEach(node -> {
			String className = node.getValue().getName();
			String classSimple = className.substring(className.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
			if (classSimple.equals(simpleName))
				exact.add(className);
			else if (partial.size() < MAX_SUGGESTIONS && classSimple.contains(simpleName))
				partial.add(className);
		});
		List<String> suggestions = new ArrayList<>(exact);
		for (String name2 : partial)
			if (suggestions.size() < MAX_SUGGESTIONS)
				suggestions.add(name2);
		String message = "No class named '" + internal + "' in the workspace.";
		if (!suggestions.isEmpty())
			message += " Did you mean: " + String.join(", ", suggestions.subList(0, Math.min(MAX_SUGGESTIONS, suggestions.size()))) + "?";
		else
			message += " Use list_classes or search_declarations to find the right name.";
		throw new ToolException(message);
	}

	/**
	 * @param info
	 * 		Class of either kind.
	 *
	 * @return The class as a JVM class. Android classes are translated from DEX.
	 *
	 * @throws ToolException
	 * 		When an Android class cannot be translated.
	 */
	@Nonnull
	public static JvmClassInfo jvmView(@Nonnull ClassInfo info) {
		if (info.isJvmClass())
			return info.asJvmClass();
		try {
			return info.asAndroidClass().asJvmClass();
		} catch (Throwable t) {
			Throwable root = t;
			while (root.getCause() != null && root.getCause() != root)
				root = root.getCause();
			throw new ToolException("The Android class '" + info.getName() + "' could not be translated to JVM bytecode: " + root);
		}
	}

	/**
	 * @param workspace
	 * 		Workspace to search.
	 * @param name
	 * 		Path of the file within the workspace.
	 *
	 * @return Path to the file.
	 *
	 * @throws ToolException
	 * 		When the workspace has no such file. The message suggests similarly named files.
	 */
	@Nonnull
	public static FilePathNode requireFile(@Nonnull Workspace workspace, @Nonnull String name) {
		String trimmed = name.trim();
		while (trimmed.startsWith("/"))
			trimmed = trimmed.substring(1);
		FilePathNode path = workspace.findFile(trimmed);
		if (path != null)
			return path;

		String lower = trimmed.substring(trimmed.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
		List<String> suggestions = Workspaces.files(workspace)
				.map(node -> node.getValue().getName())
				.filter(fileName -> fileName.toLowerCase(Locale.ROOT).contains(lower))
				.limit(MAX_SUGGESTIONS)
				.collect(Collectors.toList());
		String message = "No file named '" + trimmed + "' in the workspace.";
		if (!suggestions.isEmpty())
			message += " Did you mean: " + String.join(", ", suggestions) + "?";
		else
			message += " Use list_files to see what is there.";
		throw new ToolException(message);
	}

	/**
	 * @param info
	 * 		Class declaring the method.
	 * @param name
	 * 		Method name.
	 * @param descriptor
	 * 		Method descriptor, or {@code null} when the name alone should identify the method.
	 *
	 * @return The method.
	 *
	 * @throws ToolException
	 * 		When there is no such method, or the name is ambiguous without a descriptor.
	 */
	@Nonnull
	public static MethodMember requireMethod(@Nonnull ClassInfo info, @Nonnull String name, @Nullable String descriptor) {
		String methodName = name.trim();
		// Accept 'name(args)ret' passed as the name.
		if (descriptor == null && methodName.indexOf('(') > 0) {
			descriptor = methodName.substring(methodName.indexOf('('));
			methodName = methodName.substring(0, methodName.indexOf('('));
		}
		if (descriptor != null && !descriptor.isBlank()) {
			MethodMember method = info.getDeclaredMethod(methodName, descriptor.trim());
			if (method != null)
				return method;
		}
		String finalName = methodName;
		List<MethodMember> named = info.getMethods().stream()
				.filter(method -> method.getName().equals(finalName))
				.collect(Collectors.toList());
		if (named.size() == 1 && (descriptor == null || descriptor.isBlank()))
			return named.get(0);
		if (named.isEmpty())
			throw new ToolException("Class '" + info.getName() + "' declares no method named '" + methodName +
					"'. Its methods are: " + info.getMethods().stream().map(Lookups::signature).collect(Collectors.joining(", ")));
		throw new ToolException("Class '" + info.getName() + "' declares " + named.size() + " methods named '" + methodName +
				"'. Pass the descriptor to pick one: " + named.stream().map(MethodMember::getDescriptor).collect(Collectors.joining(", ")));
	}

	/**
	 * @param info
	 * 		Class declaring the field.
	 * @param name
	 * 		Field name.
	 * @param descriptor
	 * 		Field descriptor, or {@code null} when the name alone should identify the field.
	 *
	 * @return The field.
	 *
	 * @throws ToolException
	 * 		When there is no such field, or the name is ambiguous without a descriptor.
	 */
	@Nonnull
	public static FieldMember requireField(@Nonnull ClassInfo info, @Nonnull String name, @Nullable String descriptor) {
		String fieldName = name.trim();
		if (descriptor != null && !descriptor.isBlank()) {
			FieldMember field = info.getDeclaredField(fieldName, descriptor.trim());
			if (field != null)
				return field;
		}
		List<FieldMember> named = info.getFields().stream()
				.filter(field -> field.getName().equals(fieldName))
				.collect(Collectors.toList());
		if (named.size() == 1 && (descriptor == null || descriptor.isBlank()))
			return named.get(0);
		if (named.isEmpty())
			throw new ToolException("Class '" + info.getName() + "' declares no field named '" + fieldName +
					"'. Its fields are: " + info.getFields().stream().map(Lookups::signature).collect(Collectors.joining(", ")));
		throw new ToolException("Class '" + info.getName() + "' declares " + named.size() + " fields named '" + fieldName +
				"'. Pass the descriptor to pick one: " + named.stream().map(FieldMember::getDescriptor).collect(Collectors.joining(", ")));
	}

	/**
	 * @param member
	 * 		Field or method.
	 *
	 * @return {@code name descriptor} for fields, {@code name(args)ret} for methods.
	 */
	@Nonnull
	public static String signature(@Nonnull ClassMember member) {
		return member.isField() ? member.getName() + " " + member.getDescriptor() : member.getName() + member.getDescriptor();
	}

	/**
	 * @param type
	 * 		Kind of the flagged element.
	 * @param access
	 * 		Access flag mask.
	 *
	 * @return Flag names in source order, such as {@code public static final}.
	 */
	@Nonnull
	public static String access(@Nonnull AccessFlag.Type type, int access) {
		return AccessFlag.sortAndToString(type, access);
	}
}
