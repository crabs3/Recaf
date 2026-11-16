package software.coley.recaf.util;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import software.coley.collections.tree.SortedTree;
import software.coley.collections.tree.SortedTreeImpl;
import software.coley.collections.tree.Tree;
import software.coley.recaf.android.compat.AndroidCompat;
import software.coley.recaf.android.compat.BootClassIndex;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NavigableSet;
import java.util.TreeSet;

/**
 * Classpath utility.
 * <p>
 * <b>Android variant</b>: Desktop Recaf enumerates the classes of the JDK through the module system. On Android
 * the names of platform classes are read from the DEX files of the boot classpath instead, see
 * {@link BootClassIndex}. There is no application classpath of {@code .class} files to enumerate.
 *
 * @author Matt Coley
 */
public class ClasspathUtil {
	/**
	 * The system classloader, provided by the runtime.
	 */
	public static final ClassLoader scl = ClassLoader.getSystemClassLoader();
	private static volatile Tree<String, String> classTree;
	private static volatile NavigableSet<String> classSet;
	private static volatile List<String> systemPackages;

	/**
	 * Returns the class associated with the specified name, using
	 * {@linkplain #scl the system class loader}.
	 * <br> The class will not be initialized if it has not been initialized earlier.
	 *
	 * @param className
	 * 		The fully qualified class name.
	 *
	 * @return class object representing the desired class
	 *
	 * @throws ClassNotFoundException
	 * 		if the class cannot be located by the system class loader
	 */
	@Nonnull
	public static Class<?> getSystemClass(@Nonnull String className) throws ClassNotFoundException {
		return Class.forName(className, false, AndroidCompat.platformClassLoader());
	}

	/**
	 * Check if a class by the given name exists and is accessible by the system classloader.
	 *
	 * @param name
	 * 		The fully qualified class name.
	 *
	 * @return {@code true} if the class exists, {@code false} otherwise.
	 */
	public static boolean classExists(@Nonnull String name) {
		try {
			getSystemClass(name);
			return true;
		} catch (Throwable t) {
			return false;
		}
	}

	/**
	 * Check if a resource exists in the current classpath.
	 *
	 * @param path
	 * 		Path to resource.
	 *
	 * @return {@code true} if resource exists. {@code false} otherwise.
	 */
	public static boolean resourceExists(@Nonnull String path) {
		if (!path.startsWith("/"))
			path = "/" + path;
		return ClasspathUtil.class.getResource(path) != null;
	}

	/**
	 * Fetch a resource as a stream in the current classpath.
	 *
	 * @param path
	 * 		Path to resource.
	 *
	 * @return Stream of resource.
	 */
	@Nullable
	public static InputStream resource(String path) {
		if (!path.startsWith("/"))
			path = "/" + path;
		return ClasspathUtil.class.getResourceAsStream(path);
	}

	/**
	 * @return List of packages of the platform's public class library.
	 */
	@Nonnull
	public static List<String> getSystemPackages() {
		if (systemPackages == null) {
			synchronized (ClasspathUtil.class) {
				if (systemPackages == null) {
					NavigableSet<String> packages = new TreeSet<>();
					for (String name : getSystemClassSet()) {
						int end = name.lastIndexOf('/');
						if (end > 0)
							packages.add(name.substring(0, end).replace('/', '.'));
					}
					systemPackages = Collections.unmodifiableList(new ArrayList<>(packages));
				}
			}
		}
		return systemPackages;
	}

	/**
	 * @return Set of the internal names of the platform's classes.
	 */
	@Nonnull
	public static NavigableSet<String> getSystemClassSet() {
		if (classSet == null) {
			synchronized (ClasspathUtil.class) {
				if (classSet == null)
					classSet = BootClassIndex.readPlatformClassNames();
			}
		}
		return classSet;
	}

	/**
	 * @return Tree representation of all system classes.
	 */
	@Nonnull
	public static Tree<String, String> getSystemClassTree() {
		if (classTree == null) {
			synchronized (ClasspathUtil.class) {
				if (classTree == null) {
					Tree<String, String> tree = new SortedTreeImpl<>();
					for (String name : getSystemClassSet())
						addClassToTree(tree, name);
					classTree = tree;
				}
			}
		}
		return classTree;
	}

	/**
	 * @return Set of the internal names of classes on the application classpath. Always empty on Android.
	 */
	@Nonnull
	public static NavigableSet<String> getClasspathClassSet() {
		return Collections.emptyNavigableSet();
	}

	/**
	 * @return Tree representation of classes on the application classpath. Always empty on Android.
	 */
	@Nonnull
	public static Tree<String, String> getClasspathClassTree() {
		return new SortedTreeImpl<>();
	}

	private static void addClassToTree(@Nonnull Tree<String, String> tree, @Nonnull String className) {
		Tree<String, String> path = tree;
		for (String section : StringUtil.fastSplit(className, false, '/')) {
			SortedTree<String, String> copy = (SortedTree<String, String>) path;
			path = path.computeIfAbsent(section, x -> new SortedTreeImpl<>(copy, x));
		}
	}
}
