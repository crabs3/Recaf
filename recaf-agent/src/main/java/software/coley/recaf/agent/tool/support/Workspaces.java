package software.coley.recaf.agent.tool.support;

import jakarta.annotation.Nonnull;
import software.coley.recaf.path.ClassPathNode;
import software.coley.recaf.path.FilePathNode;
import software.coley.recaf.workspace.model.Workspace;

import java.util.stream.Stream;

/**
 * Views of a workspace's content as tools should see it.
 * <p>
 * Every Recaf workspace carries an internal resource modeling the runtime library <i>(tens of thousands of JDK
 * classes on desktop)</i>. The no-argument stream methods of {@link Workspace} include it, which is never what a
 * tool listing or searching "the program" wants. Use these instead.
 *
 * @author Recaf agent tooling
 */
public final class Workspaces {
	private Workspaces() {}

	/**
	 * @param workspace
	 * 		Workspace to read.
	 *
	 * @return All JVM and Android classes of the analyzed program and its supporting libraries.
	 */
	@Nonnull
	public static Stream<ClassPathNode> classes(@Nonnull Workspace workspace) {
		return workspace.classesStream(false);
	}

	/**
	 * @param workspace
	 * 		Workspace to read.
	 *
	 * @return All JVM classes of the analyzed program and its supporting libraries.
	 */
	@Nonnull
	public static Stream<ClassPathNode> jvmClasses(@Nonnull Workspace workspace) {
		return workspace.jvmClassesStream(false);
	}

	/**
	 * @param workspace
	 * 		Workspace to read.
	 *
	 * @return All Android classes of the analyzed program and its supporting libraries.
	 */
	@Nonnull
	public static Stream<ClassPathNode> androidClasses(@Nonnull Workspace workspace) {
		return workspace.androidClassesStream();
	}

	/**
	 * @param workspace
	 * 		Workspace to read.
	 *
	 * @return All files of the analyzed program and its supporting libraries.
	 */
	@Nonnull
	public static Stream<FilePathNode> files(@Nonnull Workspace workspace) {
		return workspace.filesStream();
	}
}
