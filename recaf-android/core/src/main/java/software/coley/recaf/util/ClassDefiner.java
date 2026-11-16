package software.coley.recaf.util;

import jakarta.annotation.Nonnull;
import software.coley.collections.Maps;

import java.util.Map;

/**
 * Classloader implementation for loading a class from raw {@code byte[]}.
 * <p>
 * <b>Android variant</b>: Android's runtime executes DEX rather than JVM class files, so the classes given here
 * cannot be defined. The loader still resolves names so that tooling which only needs a class <i>hierarchy</i>
 * <i>(such as ASM's frame computation)</i> can use it, but asking it to define one of its own classes fails
 * with a {@link ClassNotFoundException} that says why.
 *
 * @author Matt Coley
 */
public class ClassDefiner extends ClassLoader {
	private final Map<String, byte[]> classes;

	/**
	 * @param name
	 * 		Name of class.
	 * @param bytecode
	 * 		Bytecode of class.
	 */
	public ClassDefiner(@Nonnull String name, @Nonnull byte[] bytecode) {
		this(Maps.of(name, bytecode));
	}

	/**
	 * @param classes
	 * 		Map of classes.
	 */
	public ClassDefiner(@Nonnull Map<String, byte[]> classes) {
		super(ClassDefiner.class.getClassLoader());
		this.classes = classes;
	}

	@Override
	public final Class<?> findClass(String name) throws ClassNotFoundException {
		if (classes.containsKey(name))
			throw new ClassNotFoundException("Cannot define '" + name + "': JVM class files cannot be loaded by the Android runtime");
		return super.findClass(name);
	}
}
