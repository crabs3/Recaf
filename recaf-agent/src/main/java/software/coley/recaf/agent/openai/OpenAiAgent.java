package software.coley.recaf.agent.openai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import software.coley.recaf.agent.chat.AgentListener;
import software.coley.recaf.agent.chat.AgentTurn;
import software.coley.recaf.agent.chat.ChatAgent;
import software.coley.recaf.agent.chat.ToolApprover;
import software.coley.recaf.agent.chat.ToolCallRunner;
import software.coley.recaf.agent.prompt.Playbooks;
import software.coley.recaf.agent.tool.AgentTool;
import software.coley.recaf.agent.tool.AgentToolbox;
import software.coley.recaf.agent.tool.ToolResult;
import software.coley.recaf.analytics.logging.Logging;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * An agent that analyzes the open workspace with a model reached through an OpenAI-compatible API: OpenAI itself,
 * a gateway such as OpenRouter, or a model server on the local network such as Ollama, LM Studio or vLLM.
 * <p>
 * It works like the Claude agent and gives the model the same tools and instructions. What differs is the wire
 * format and how much can be relied on. These APIs share a format, not a behavior: servers differ in which
 * optional parts they accept and in how faithfully they produce tool calls, so this agent sends only what is
 * widely understood, adapts once when a server turns an optional part down, and treats every tool call as
 * something that may be malformed.
 *
 * @author Recaf agent tooling
 */
public final class OpenAiAgent implements ChatAgent {
	/** Upper bound of model requests for a single user message, as a guard against runaway loops. */
	private static final int MAX_STEPS = 300;
	/** How often a cut-off response is retried before giving up. */
	private static final int MAX_RECOVERIES = 3;
	/** How often a request is sent again after a failure that may pass. */
	private static final int MAX_RETRIES = 2;
	private static final long MAX_RETRY_WAIT_MILLIS = 30_000;
	private static final Pattern NO_TOOL_SUPPORT = Pattern.compile(
			"does not support tool|tools? (is|are) not supported|tool.{0,20}not supported|function calling|does not support function",
			Pattern.CASE_INSENSITIVE);
	private static final Pattern CONTEXT_EXCEEDED = Pattern.compile(
			"context.{0,12}(length|window|size)|maximum context|too many tokens|prompt is too long|exceeds? the.{0,30}(token|context)",
			Pattern.CASE_INSENSITIVE);
	private static final Logger logger = Logging.get(OpenAiAgent.class);
	private final List<JsonObject> history = new ArrayList<>();
	private final AgentToolbox toolbox;
	private final OpenAiConfig config;
	private final AgentListener listener;
	private final ToolCallRunner toolRunner;
	private final OpenAiClient client;
	private final JsonArray tools;
	private volatile boolean cancelled;
	private volatile boolean running;
	private volatile String lastReply;
	/** Whether to ask for token usage in the stream. Switched off for servers that reject the option. */
	private boolean requestUsage = true;
	private long inputTokens, outputTokens, cachedTokens;

	/**
	 * @param toolbox
	 * 		Tools the agent works with.
	 * @param config
	 * 		Agent settings.
	 * @param listener
	 * 		Receiver of the agent's activity.
	 * @param approver
	 * 		Asked before running modifying tools when the configured approval mode requires it.
	 * 		When {@code null}, such tools are declined in that mode.
	 */
	public OpenAiAgent(@Nonnull AgentToolbox toolbox, @Nonnull OpenAiConfig config,
	                   @Nonnull AgentListener listener, @Nullable ToolApprover approver) {
		this.toolbox = toolbox;
		this.config = config;
		this.listener = listener;
		this.toolRunner = new ToolCallRunner(toolbox, config.approvalMode(), listener, approver, () -> cancelled);
		this.client = new OpenAiClient(config.baseUrl(), config.apiKey());
		this.tools = buildTools(toolbox);
	}

	@Nonnull
	@Override
	public AgentTurn send(@Nonnull String userMessage) {
		synchronized (history) {
			history.add(message("user", userMessage));
		}
		return run();
	}

	@Nonnull
	@Override
	public AgentTurn resume() {
		synchronized (history) {
			if (history.isEmpty())
				return new AgentTurn(AgentTurn.Outcome.ERROR, "There is no conversation to continue.");
			// A conversation that ended on the agent's reply needs a user turn for the model to respond to.
			JsonObject last = history.get(history.size() - 1);
			if ("assistant".equals(role(last)))
				history.add(message("user", "Continue."));
		}
		return run();
	}

	@Override
	public void cancel() {
		cancelled = true;
		toolbox.context().setCancelled(true);
		client.abort();
	}

	@Override
	public boolean isRunning() {
		return running;
	}

	@Override
	public int messageCount() {
		synchronized (history) {
			return history.size();
		}
	}

	@Nonnull
	@Override
	public AgentListener.Usage usage() {
		// What a request costs depends on the provider, which this agent knows nothing about.
		return new AgentListener.Usage(Math.max(0, inputTokens - cachedTokens), outputTokens, cachedTokens, 0, null);
	}

	@Nonnull
	@Override
	public Optional<String> lastReply() {
		return Optional.ofNullable(lastReply);
	}

	@Nonnull
	@Override
	public String exportConversation() {
		JsonArray array = new JsonArray();
		synchronized (history) {
			for (JsonObject message : history)
				array.add(message);
		}
		return array.toString();
	}

	@Override
	public void importConversation(@Nonnull String json) {
		List<JsonObject> restored = new ArrayList<>();
		try {
			for (JsonElement element : JsonParser.parseString(json).getAsJsonArray()) {
				JsonObject message = element.getAsJsonObject();
				String role = role(message);
				if (!"user".equals(role) && !"assistant".equals(role) && !"tool".equals(role))
					throw new IllegalArgumentException("Not an exported conversation: unexpected role " + role);
				restored.add(message);
			}
		} catch (JsonParseException | IllegalStateException ex) {
			throw new IllegalArgumentException("Not an exported conversation", ex);
		}
		synchronized (history) {
			history.clear();
			history.addAll(restored);
		}
	}

	@Nonnull
	private AgentTurn run() {
		if (running)
			return new AgentTurn(AgentTurn.Outcome.ERROR, "The agent is already running.");
		running = true;
		cancelled = false;
		toolbox.context().setCancelled(false);
		try {
			return loop();
		} catch (OpenAiException ex) {
			if (cancelled)
				return new AgentTurn(AgentTurn.Outcome.CANCELLED, null);
			return describe(ex);
		} catch (IOException ex) {
			if (cancelled)
				return new AgentTurn(AgentTurn.Outcome.CANCELLED, null);
			return new AgentTurn(AgentTurn.Outcome.NETWORK_ERROR, "Could not reach the API at " + config.baseUrl() + ": " + message(ex));
		} catch (RuntimeException ex) {
			if (cancelled)
				return new AgentTurn(AgentTurn.Outcome.CANCELLED, null);
			logger.error("Agent failed unexpectedly", ex);
			return new AgentTurn(AgentTurn.Outcome.ERROR, "Unexpected failure: " + ex);
		} finally {
			running = false;
		}
	}

	@Nonnull
	private AgentTurn loop() throws IOException {
		int recoveries = 0;
		for (int step = 0; step < MAX_STEPS; step++) {
			if (cancelled)
				return new AgentTurn(AgentTurn.Outcome.CANCELLED, null);

			OpenAiClient.Completion completion = request();
			if (cancelled)
				return new AgentTurn(AgentTurn.Outcome.CANCELLED, null);
			inputTokens += completion.promptTokens();
			outputTokens += completion.completionTokens();
			cachedTokens += completion.cachedTokens();
			listener.onUsage(usage());

			String text = completion.text();
			List<OpenAiClient.ToolCall> toolCalls = completion.toolCalls();
			boolean cutOff = "length".equals(completion.finishReason());
			if (text.isBlank() && toolCalls.isEmpty()) {
				// Nothing to add to the conversation, an empty assistant message would be rejected on the next request.
				if ("content_filter".equals(completion.finishReason()))
					return new AgentTurn(AgentTurn.Outcome.REFUSED, "The provider's content filter declined the request.");
				if (cutOff)
					return new AgentTurn(AgentTurn.Outcome.TRUNCATED,
							"The response was cut off by the model's output limit before it produced an answer.");
				return new AgentTurn(AgentTurn.Outcome.ERROR, "The model returned an empty response.");
			}

			synchronized (history) {
				history.add(assistantMessage(text, toolCalls));
			}
			if (!text.isBlank())
				lastReply = text;
			if (toolCalls.isEmpty()) {
				if (cutOff)
					return new AgentTurn(AgentTurn.Outcome.TRUNCATED, "The reply was cut off by the model's output limit.");
				return new AgentTurn(AgentTurn.Outcome.COMPLETED, null);
			}

			// A response that ran into the output limit may end in a tool call with half of its arguments.
			if (cutOff && ++recoveries > MAX_RECOVERIES)
				return new AgentTurn(AgentTurn.Outcome.TRUNCATED, "The agent's tool calls kept exceeding the model's output limit.");

			// Every call is answered, in order, whether it ran or not: the API rejects a conversation otherwise.
			List<JsonObject> results = new ArrayList<>(toolCalls.size());
			for (OpenAiClient.ToolCall call : toolCalls) {
				ToolResult result = cutOff ?
						ToolResult.error("This tool call was cut off by the output limit and was not run. " +
								"Call it again with smaller arguments, or split the work into several calls.") :
						toolRunner.run(call.id(), call.name(), parseArguments(call.arguments()), call.arguments());
				JsonObject message = new JsonObject();
				message.addProperty("role", "tool");
				message.addProperty("tool_call_id", call.id());
				// The format has no flag for a failed call, so the text has to say it.
				message.addProperty("content", result.error() && !result.text().startsWith("Error") ?
						"Error: " + result.text() : result.text());
				results.add(message);
			}
			synchronized (history) {
				history.addAll(results);
			}
		}
		return new AgentTurn(AgentTurn.Outcome.STEP_LIMIT, "The agent stopped after " + MAX_STEPS +
				" steps without finishing. Continue to let it keep going.");
	}

	/**
	 * Requests the next response, reporting it to the listener while it streams in. A request that fails for a
	 * reason that may pass is sent again, as long as nothing of it has reached the listener.
	 */
	@Nonnull
	private OpenAiClient.Completion request() throws IOException {
		for (int attempt = 0; ; attempt++) {
			boolean[] emitted = {false};
			OpenAiClient.StreamListener streamListener = new OpenAiClient.StreamListener() {
				@Override
				public void onText(@Nonnull String text) {
					emitted[0] = true;
					listener.onText(text);
				}

				@Override
				public void onReasoning(@Nonnull String text) {
					emitted[0] = true;
					listener.onThinking(text);
				}

				@Override
				public void onToolCallStarted(@Nonnull String id, @Nonnull String name) {
					emitted[0] = true;
					listener.onToolCallStarted(id, name);
				}
			};
			try {
				return client.chat(requestBody(), streamListener);
			} catch (OpenAiException ex) {
				if (cancelled)
					throw ex;
				if (requestUsage && (ex.status() == 400 || ex.status() == 422) && mentionsStreamOptions(ex)) {
					// The server does not know the option that asks for usage numbers. Do without them.
					requestUsage = false;
					attempt--;
					continue;
				}
				if (!ex.isTransient() || emitted[0] || attempt >= MAX_RETRIES)
					throw ex;
				pause(ex.retryAfterMillis() >= 0 ? ex.retryAfterMillis() : backoff(attempt));
			} catch (IOException ex) {
				if (cancelled || emitted[0] || attempt >= MAX_RETRIES)
					throw ex;
				pause(backoff(attempt));
			}
			if (cancelled)
				throw new IOException("The request was cancelled.");
		}
	}

	@Nonnull
	private JsonObject requestBody() {
		JsonObject body = new JsonObject();
		body.addProperty("model", config.model());
		JsonArray messages = new JsonArray();
		messages.add(message("system", Playbooks.SYSTEM_PROMPT));
		synchronized (history) {
			for (JsonObject message : history)
				messages.add(message);
		}
		body.add("messages", messages);
		body.add("tools", tools);
		body.addProperty("stream", true);
		if (requestUsage) {
			JsonObject options = new JsonObject();
			options.addProperty("include_usage", true);
			body.add("stream_options", options);
		}
		return body;
	}

	@Nonnull
	private AgentTurn describe(@Nonnull OpenAiException ex) {
		String message = message(ex);
		int status = ex.status();
		if (status == 401 || status == 403)
			return new AgentTurn(AgentTurn.Outcome.AUTH_ERROR, "The API key was not accepted: " + message);
		if (status == 429 || status >= 500)
			return new AgentTurn(AgentTurn.Outcome.BUSY, "The API is rate limiting or overloaded, try again shortly: " + message);
		String hint = "";
		if (NO_TOOL_SUPPORT.matcher(message).find())
			hint = " This model cannot call tools, which the agent depends on. Pick a model that supports tool calling.";
		else if (CONTEXT_EXCEEDED.matcher(message).find())
			hint = " The conversation no longer fits into what this model can read at once. Start a new conversation, " +
					"or use a model with a larger context window.";
		else if (status == 404)
			hint = " Check the API address and the model name.";
		return new AgentTurn(AgentTurn.Outcome.INVALID_REQUEST, "The API rejected the request (HTTP " + status + "): " + message + hint);
	}

	private void pause(long millis) {
		long remaining = Math.min(Math.max(millis, 0), MAX_RETRY_WAIT_MILLIS);
		try {
			// In short steps, so that stopping the agent does not have to wait for the pause to end.
			while (remaining > 0 && !cancelled) {
				long step = Math.min(remaining, 100);
				Thread.sleep(step);
				remaining -= step;
			}
		} catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	private static long backoff(int attempt) {
		return 1_000L << Math.min(attempt, 4);
	}

	private static boolean mentionsStreamOptions(@Nonnull OpenAiException ex) {
		String message = message(ex).toLowerCase();
		return message.contains("stream_options") || message.contains("include_usage");
	}

	/**
	 * @param arguments
	 * 		Arguments of a tool call as the model wrote them.
	 *
	 * @return The parsed arguments, or {@code null} when they are not valid JSON.
	 */
	@Nullable
	private static JsonElement parseArguments(@Nonnull String arguments) {
		if (arguments.isBlank())
			return new JsonObject(); // A call without arguments is commonly sent as an empty text.
		try {
			JsonElement parsed = JsonParser.parseString(arguments);
			// Some models wrap the arguments in a second layer of JSON text.
			if (parsed.isJsonPrimitive() && parsed.getAsJsonPrimitive().isString())
				parsed = JsonParser.parseString(parsed.getAsString());
			return parsed;
		} catch (JsonParseException ex) {
			return null;
		}
	}

	@Nonnull
	private static JsonObject assistantMessage(@Nonnull String text, @Nonnull List<OpenAiClient.ToolCall> toolCalls) {
		JsonObject message = new JsonObject();
		message.addProperty("role", "assistant");
		if (text.isEmpty() && !toolCalls.isEmpty())
			message.add("content", com.google.gson.JsonNull.INSTANCE);
		else
			message.addProperty("content", text);
		if (!toolCalls.isEmpty()) {
			JsonArray calls = new JsonArray();
			for (OpenAiClient.ToolCall call : toolCalls) {
				JsonObject function = new JsonObject();
				function.addProperty("name", call.name());
				// Sent back as the model wrote it. Arguments it got wrong are answered with an error result.
				function.addProperty("arguments", call.arguments().isBlank() ? "{}" : call.arguments());
				JsonObject entry = new JsonObject();
				entry.addProperty("id", call.id());
				entry.addProperty("type", "function");
				entry.add("function", function);
				calls.add(entry);
			}
			message.add("tool_calls", calls);
		}
		return message;
	}

	@Nonnull
	private static JsonObject message(@Nonnull String role, @Nonnull String content) {
		JsonObject message = new JsonObject();
		message.addProperty("role", role);
		message.addProperty("content", content);
		return message;
	}

	@Nullable
	private static String role(@Nonnull JsonObject message) {
		JsonElement role = message.get("role");
		return role != null && role.isJsonPrimitive() ? role.getAsString() : null;
	}

	/**
	 * @param toolbox
	 * 		Toolbox to describe to the model.
	 *
	 * @return The tool definitions sent with every request.
	 */
	@Nonnull
	private static JsonArray buildTools(@Nonnull AgentToolbox toolbox) {
		JsonArray tools = new JsonArray();
		for (AgentTool tool : toolbox.tools()) {
			JsonObject function = new JsonObject();
			function.addProperty("name", tool.name());
			function.addProperty("description", tool.description());
			function.add("parameters", tool.inputSchema().deepCopy());
			JsonObject entry = new JsonObject();
			entry.addProperty("type", "function");
			entry.add("function", function);
			tools.add(entry);
		}
		return tools;
	}

	@Nonnull
	private static String message(@Nonnull Throwable t) {
		String message = t.getMessage();
		return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
	}
}
