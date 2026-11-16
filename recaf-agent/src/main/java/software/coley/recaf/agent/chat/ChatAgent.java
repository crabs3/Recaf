package software.coley.recaf.agent.chat;

import jakarta.annotation.Nonnull;

import java.util.Optional;

/**
 * An agent that works on the open workspace in conversation with the user: it sends the conversation to a model,
 * runs the tools the model asks for, and repeats until the model is done.
 * <p>
 * Implementations differ in the API they talk to. What they share is the contract below, which is all a host
 * application needs to show a conversation and to steer it.
 *
 * @author Recaf agent tooling
 */
public interface ChatAgent {
	/**
	 * Sends a user message and runs the agent until it is done. Blocks the calling thread.
	 *
	 * @param userMessage
	 * 		Message from the user.
	 *
	 * @return How the run ended.
	 */
	@Nonnull
	AgentTurn send(@Nonnull String userMessage);

	/**
	 * Continues the conversation from where it stopped, without a new user message. Use it after a run that was
	 * cancelled, failed for a transient reason or hit a limit. Blocks the calling thread.
	 *
	 * @return How the run ended.
	 */
	@Nonnull
	AgentTurn resume();

	/**
	 * Stops the agent as soon as possible. Safe to call from any thread.
	 * The conversation stays valid and can be continued with {@link #send(String)} or {@link #resume()}.
	 */
	void cancel();

	/**
	 * @return {@code true} while {@link #send(String)} or {@link #resume()} is in progress.
	 */
	boolean isRunning();

	/**
	 * @return Number of messages in the conversation.
	 */
	int messageCount();

	/**
	 * @return Token usage of the conversation so far.
	 */
	@Nonnull
	AgentListener.Usage usage();

	/**
	 * @return Text of the agent's most recent reply, or an empty optional when it has not replied yet.
	 */
	@Nonnull
	Optional<String> lastReply();

	/**
	 * @return The conversation as JSON, for persisting it across restarts of the host application.
	 * The format belongs to the implementation, a conversation can only be imported by the same kind of agent.
	 */
	@Nonnull
	String exportConversation();

	/**
	 * Replaces the conversation with one exported by {@link #exportConversation()}.
	 *
	 * @param json
	 * 		Exported conversation.
	 *
	 * @throws IllegalArgumentException
	 * 		When the JSON is not a conversation exported by this kind of agent.
	 */
	void importConversation(@Nonnull String json);
}
