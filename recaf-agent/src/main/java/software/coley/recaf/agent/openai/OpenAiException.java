package software.coley.recaf.agent.openai;

import jakarta.annotation.Nonnull;

import java.io.IOException;

/**
 * An OpenAI-compatible API answered with an error.
 * Failures to reach the API at all are plain {@link IOException}s.
 *
 * @author Recaf agent tooling
 */
public final class OpenAiException extends IOException {
	private final int status;
	private final long retryAfterMillis;

	/**
	 * @param status
	 * 		HTTP status of the response. For an error reported inside a stream that had started with a success
	 * 		status, the closest equivalent.
	 * @param message
	 * 		What the API said.
	 * @param retryAfterMillis
	 * 		How long the API asked to wait before trying again, or {@code -1} when it did not say.
	 */
	public OpenAiException(int status, @Nonnull String message, long retryAfterMillis) {
		super(message);
		this.status = status;
		this.retryAfterMillis = retryAfterMillis;
	}

	/**
	 * @return HTTP status of the response.
	 */
	public int status() {
		return status;
	}

	/**
	 * @return How long the API asked to wait before trying again, or {@code -1} when it did not say.
	 */
	public long retryAfterMillis() {
		return retryAfterMillis;
	}

	/**
	 * @return {@code true} when the same request may succeed if it is sent again a little later.
	 */
	public boolean isTransient() {
		return status == 408 || status == 409 || status == 429 || status >= 500;
	}
}
