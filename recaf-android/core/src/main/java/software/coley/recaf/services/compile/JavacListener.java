package software.coley.recaf.services.compile;

import jakarta.annotation.Nonnull;

/**
 * Listener for compiler feedback.
 * <p>
 * <b>Android variant</b>: The desktop type extends {@code javax.tools.DiagnosticListener}, which Android does not
 * have. Feedback is delivered as Recaf's own {@link CompilerDiagnostic} model instead.
 *
 * @author Matt Coley
 */
public interface JavacListener {
	/**
	 * @param diagnostic
	 * 		Message from the compiler.
	 */
	void report(@Nonnull CompilerDiagnostic diagnostic);
}
