package software.coley.recaf.services.script;

import jakarta.annotation.Nonnull;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.coley.recaf.services.compile.CompilerDiagnostic;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Basic implementation of {@link ScriptEngine}.
 * <p>
 * <b>Android variant</b>: Desktop Recaf scripts are Java sources which get compiled with {@code javac} and loaded
 * as JVM classes. Android offers neither a Java compiler nor JVM class loading, so Java scripts always fail to
 * "compile" here with a diagnostic pointing at the alternative: the JavaScript based scripting of the agent tooling,
 * which has the same access to Recaf's services.
 *
 * @author Matt Coley
 */
@ApplicationScoped
public class JavacScriptEngine implements ScriptEngine {
	private static final String UNAVAILABLE_MESSAGE = "Java scripts cannot be compiled on Android. " +
			"Use a JavaScript script instead, it has access to the same Recaf services.";
	private final ScriptEngineConfig config;

	@Inject
	public JavacScriptEngine(@Nonnull ScriptEngineConfig config) {
		this.config = config;
	}

	@Nonnull
	@Override
	public String getServiceId() {
		return SERVICE_ID;
	}

	@Nonnull
	@Override
	public ScriptEngineConfig getServiceConfig() {
		return config;
	}

	@Nonnull
	@Override
	public CompletableFuture<ScriptResult> run(@Nonnull String script) {
		return compile(script).thenCompose(this::run);
	}

	@Nonnull
	@Override
	public CompletableFuture<ScriptResult> run(@Nonnull GenerateResult result) {
		return CompletableFuture.completedFuture(new ScriptResult(result.diagnostics()));
	}

	@Nonnull
	@Override
	public CompletableFuture<GenerateResult> compile(@Nonnull String scriptSource) {
		CompilerDiagnostic diagnostic = new CompilerDiagnostic(-1, -1, 0, UNAVAILABLE_MESSAGE,
				CompilerDiagnostic.Level.ERROR, null);
		return CompletableFuture.completedFuture(new GenerateResult(null, List.of(diagnostic)));
	}
}
