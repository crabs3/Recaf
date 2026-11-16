package software.coley.recaf.android.compat;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Provides class file outlines of the platform's runtime classes.
 * <p>
 * Desktop Recaf reads the real class files of the JDK it runs on to answer questions such as
 * <i>"what does {@code java/util/ArrayList} extend?"</i>. Android ships its runtime as DEX with no class files to read,
 * so equivalent code-less class files are synthesized on demand from reflection.
 *
 * @author Recaf Android port
 */
public final class RuntimeClassSynthesizer {
	private static final int CLASS_ACCESS_MASK = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_INTERFACE |
			Opcodes.ACC_ABSTRACT | Opcodes.ACC_ANNOTATION | Opcodes.ACC_ENUM | Opcodes.ACC_SYNTHETIC;
	private static final int MEMBER_ACCESS_MASK = Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_STATIC |
			Opcodes.ACC_FINAL | Opcodes.ACC_ABSTRACT | Opcodes.ACC_SYNCHRONIZED | Opcodes.ACC_NATIVE |
			Opcodes.ACC_VARARGS | Opcodes.ACC_VOLATILE | Opcodes.ACC_TRANSIENT;

	private RuntimeClassSynthesizer() {}

	/**
	 * @param internalName
	 * 		Internal name of class, such as {@code java/lang/String}.
	 *
	 * @return The platform class of the given name, or {@code null} if there is no such platform class.
	 */
	@Nullable
	public static Class<?> find(@Nonnull String internalName) {
		// Array and primitive names are never valid lookups here.
		if (internalName.isEmpty() || internalName.charAt(0) == '[')
			return null;
		try {
			return Class.forName(internalName.replace('/', '.'), false, AndroidCompat.platformClassLoader());
		} catch (Throwable t) {
			return null;
		}
	}

	/**
	 * @param internalName
	 * 		Internal name of class.
	 *
	 * @return {@code true} when the platform has a class of the given name.
	 */
	public static boolean exists(@Nonnull String internalName) {
		return find(internalName) != null;
	}

	/**
	 * @param internalName
	 * 		Internal name of class.
	 *
	 * @return Code-less class file modeling the platform class, or {@code null} if there is no such platform class.
	 */
	@Nullable
	public static byte[] getBytes(@Nonnull String internalName) {
		Class<?> cls = find(internalName);
		if (cls == null)
			return null;
		try {
			return synthesize(cls);
		} catch (Throwable t) {
			// Reflection on some platform classes can fail when their members reference unavailable types.
			return null;
		}
	}

	@Nonnull
	private static byte[] synthesize(@Nonnull Class<?> cls) {
		ClassWriter writer = new ClassWriter(0);
		Class<?> superClass = cls.getSuperclass();
		Class<?>[] interfaces = cls.getInterfaces();
		String[] interfaceNames = new String[interfaces.length];
		for (int i = 0; i < interfaces.length; i++)
			interfaceNames[i] = Type.getInternalName(interfaces[i]);

		int access = cls.getModifiers() & CLASS_ACCESS_MASK;
		if (!cls.isInterface())
			access |= Opcodes.ACC_SUPER;
		String superName = superClass != null ? Type.getInternalName(superClass) :
				(cls == Object.class ? null : "java/lang/Object");
		writer.visit(Opcodes.V1_8, access, Type.getInternalName(cls), null, superName, interfaceNames);

		for (Field field : cls.getDeclaredFields()) {
			int modifiers = field.getModifiers();
			if (isHidden(modifiers) || field.isSynthetic())
				continue;
			writer.visitField(modifiers & MEMBER_ACCESS_MASK, field.getName(),
					Type.getDescriptor(field.getType()), null, null).visitEnd();
		}
		for (Constructor<?> constructor : cls.getDeclaredConstructors()) {
			int modifiers = constructor.getModifiers();
			if (isHidden(modifiers) || constructor.isSynthetic())
				continue;
			writer.visitMethod(modifiers & MEMBER_ACCESS_MASK, "<init>", Type.getConstructorDescriptor(constructor),
					null, internalNames(constructor.getExceptionTypes())).visitEnd();
		}
		for (Method method : cls.getDeclaredMethods()) {
			int modifiers = method.getModifiers();
			if (isHidden(modifiers) || method.isSynthetic() || method.isBridge())
				continue;
			writer.visitMethod(modifiers & MEMBER_ACCESS_MASK, method.getName(), Type.getMethodDescriptor(method),
					null, internalNames(method.getExceptionTypes())).visitEnd();
		}
		writer.visitEnd();
		return writer.toByteArray();
	}

	private static boolean isHidden(int modifiers) {
		return !Modifier.isPublic(modifiers) && !Modifier.isProtected(modifiers);
	}

	@Nullable
	private static String[] internalNames(@Nonnull Class<?>[] types) {
		if (types.length == 0)
			return null;
		String[] names = new String[types.length];
		for (int i = 0; i < types.length; i++)
			names[i] = Type.getInternalName(types[i]);
		return names;
	}
}
