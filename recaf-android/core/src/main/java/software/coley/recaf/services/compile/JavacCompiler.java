package software.coley.recaf.services.compile;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.coley.recaf.services.Service;
import software.coley.recaf.workspace.model.Workspace;

import java.util.List;

/**
 * Wrapper for the Java compiler.
 * <p>
 * <b>Android variant</b>: Desktop Recaf drives {@code javac} through {@code javax.tools}, which only exists in a JDK.
 * Android has no Java source compiler, so this variant reports itself as unavailable and every compilation yields a
 * single explanatory error diagnostic. Class editing on Android goes through the bytecode assembler, which is
 * self-contained.
 *
 * @author Matt Coley
 */
@ApplicationScoped
public class JavacCompiler implements Service {
	public static final String SERVICE_ID = "java-compiler";
	public static final int MIN_DOWNSAMPLE_VER = 8;
	private static final String UNAVAILABLE_MESSAGE = "Java source compilation is not available on Android. " +
			"Edit classes with the bytecode assembler instead.";
	private final JavacCompilerConfig config;

	@Inject
	public JavacCompiler(@Nonnull JavacCompilerConfig config) {
		this.config = config;
	}

	/**
	 * @param arguments
	 * 		Wrapper of all arguments.
	 * @param workspace
	 * 		Optional workspace to include for additional classpath support.
	 * @param listener
	 * 		Optional listener to handle feedback with,
	 * 		mirroring what is reported by {@link CompilerResult#getDiagnostics()}
	 *
	 * @return Compilation result wrapper.
	 */
	@Nonnull
	public CompilerResult compile(@Nonnull JavacArguments arguments,
	                              @Nullable Workspace workspace,
	                              @Nullable JavacListener listener) {
		return compile(arguments, workspace, null, listener);
	}

	/**
	 * @param arguments
	 * 		Wrapper of all arguments.
	 * @param workspace
	 * 		Optional workspace to include for additional classpath support.
	 * @param supplementaryResources
	 * 		Optional resources to further extend the classpath. Unused on Android.
	 * @param listener
	 * 		Optional listener to handle feedback with,
	 * 		mirroring what is reported by {@link CompilerResult#getDiagnostics()}
	 *
	 * @return Compilation result wrapper.
	 */
	@Nonnull
	public CompilerResult compile(@Nonnull JavacArguments arguments,
	                              @Nullable Workspace workspace,
	                              @Nullable List<?> supplementaryResources,
	                              @Nullable JavacListener listener) {
		CompilerDiagnostic diagnostic = new CompilerDiagnostic(-1, -1, 0, UNAVAILABLE_MESSAGE,
				CompilerDiagnostic.Level.ERROR, null);
		if (listener != null)
			listener.report(diagnostic);
		return new CompilerResult(new CompileMap(java.util.Collections.emptyMap()), List.of(diagnostic));
	}

	/**
	 * @return {@code true} when the compiler can be invoked. Always {@code false} on Android.
	 */
	public static boolean isAvailable() {
		return false;
	}

	/**
	 * @return Minimum target version supported by the compiler.
	 */
	public static int getMinTargetVersion() {
		return MIN_DOWNSAMPLE_VER;
	}

	@Nonnull
	@Override
	public String getServiceId() {
		return SERVICE_ID;
	}

	@Nonnull
	@Override
	public JavacCompilerConfig getServiceConfig() {
		return config;
	}
}
