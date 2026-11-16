package software.coley.recaf.agent.claude;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

/**
 * How a run of the agent ended.
 *
 * @param outcome
 * 		Kind of ending.
 * @param detail
 * 		Explanation for the user where the outcome needs one, such as an error message.
 *
 * @author Recaf agent tooling
 */
public record AgentTurn(@Nonnull Outcome outcome, @Nullable String detail) {
	/**
	 * Kind of ending.
	 */
	public enum Outcome {
		/** The agent finished its reply. */
		COMPLETED,
		/** The reply was cut off by the response size limit. */
		TRUNCATED,
		/** The user stopped the agent. */
		CANCELLED,
		/** A safety classifier declined the request and no fallback model answered. */
		REFUSED,
		/** The agent used the maximum number of tool rounds without finishing. */
		STEP_LIMIT,
		/** The API key was rejected. */
		AUTH_ERROR,
		/** The request was rate limited or the API was overloaded, after the built-in retries. */
		BUSY,
		/** The API rejected the request as invalid, for instance an unknown model. */
		INVALID_REQUEST,
		/** The API could not be reached. */
		NETWORK_ERROR,
		/** Any other failure. */
		ERROR;

		/**
		 * @return {@code true} when calling {@link ClaudeAgent#resume()} can reasonably be expected to make progress.
		 */
		public boolean canResume() {
			return this == TRUNCATED || this == CANCELLED || this == STEP_LIMIT || this == BUSY || this == NETWORK_ERROR || this == ERROR;
		}
	}
}
