package software.coley.recaf.agent.tool;

import jakarta.annotation.Nonnull;

/**
 * Thrown by a tool to report a problem <i>to the model</i>, for instance a class that does not exist or an
 * argument that makes no sense. The message is returned as an error result, so it should say what went wrong and,
 * where possible, what to do instead.
 *
 * @author Recaf agent tooling
 */
public class ToolException extends RuntimeException {
	/**
	 * @param message
	 * 		Explanation written for the model.
	 */
	public ToolException(@Nonnull String message) {
		super(message);
	}

	/**
	 * @param message
	 * 		Explanation written for the model.
	 * @param cause
	 * 		Underlying problem.
	 */
	public ToolException(@Nonnull String message, @Nonnull Throwable cause) {
		super(message, cause);
	}
}
