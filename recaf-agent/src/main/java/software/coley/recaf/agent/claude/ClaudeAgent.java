package software.coley.recaf.agent.claude;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.core.http.StreamResponse;
import com.anthropic.errors.AnthropicException;
import com.anthropic.errors.AnthropicInvalidDataException;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.BadRequestException;
import com.anthropic.errors.NotFoundException;
import com.anthropic.errors.OverloadedException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.RateLimitException;
import com.anthropic.errors.UnauthorizedException;
import com.anthropic.helpers.BetaMessageAccumulator;
import com.anthropic.models.beta.AnthropicBeta;
import com.anthropic.models.beta.messages.BetaCacheControlEphemeral;
import com.anthropic.models.beta.messages.BetaContentBlock;
import com.anthropic.models.beta.messages.BetaContentBlockParam;
import com.anthropic.models.beta.messages.BetaMessage;
import com.anthropic.models.beta.messages.BetaMessageParam;
import com.anthropic.models.beta.messages.BetaOutputConfig;
import com.anthropic.models.beta.messages.BetaRawMessageStreamEvent;
import com.anthropic.models.beta.messages.BetaStopReason;
import com.anthropic.models.beta.messages.BetaTextBlockParam;
import com.anthropic.models.beta.messages.BetaThinkingConfigAdaptive;
import com.anthropic.models.beta.messages.BetaTool;
import com.anthropic.models.beta.messages.BetaToolResultBlockParam;
import com.anthropic.models.beta.messages.BetaToolUseBlock;
import com.anthropic.models.beta.messages.BetaUsage;
import com.anthropic.models.beta.messages.MessageCreateParams;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An agent that analyzes and edits the open workspace by driving an {@link AgentToolbox} with a Claude model.
 * <p>
 * One instance is one conversation. {@link #send(String)} adds a user message and runs the agent until it has
 * nothing left to do: it requests a response from the model, runs the tools the model asked for, feeds the results
 * back, and repeats. Everything the agent does along the way is reported to an {@link AgentListener}.
 *
 * <h2>Why this loop is written by hand</h2>
 * The Anthropic SDK ships a tool runner that automates this cycle. This class drives the cycle itself because the
 * host applications need control the runner's per-turn hooks do not give: stopping in the middle of a response or
 * of a tool call while leaving a conversation that can be continued, suspending on a user's approval for as long
 * as it takes, and keeping the conversation in a form that survives the process being restarted. Accumulating
 * stream events into messages is still left to the SDK ({@link BetaMessageAccumulator}).
 *
 * <h2>Rules the loop keeps</h2>
 * <ul>
 *     <li><b>The conversation is append-only.</b> The system prompt and tool list are fixed for the lifetime of the
 *     agent and no earlier message is ever edited or removed. Responses are appended exactly as received, thinking
 *     blocks included. Current models bind their reasoning to the conversation that produced it and reject or drop
 *     it when the earlier conversation was altered, and an unchanged prefix is also what makes prompt caching work.</li>
 *     <li><b>Every tool call is answered.</b> All results of one response go back in a single message, including
 *     for calls that were declined, cancelled or could not be parsed.</li>
 *     <li><b>A response's stop reason is checked before its content is used.</b> Tools requested in a response that
 *     was declined by a safety classifier or cut off by the size limit are not run.</li>
 * </ul>
 *
 * @author Recaf agent tooling
 */
public final class ClaudeAgent implements ChatAgent {
	/** Upper bound of model requests for a single user message, as a guard against runaway loops. */
	private static final int MAX_STEPS = 300;
	/** How often a malformed or cut-off response is retried before giving up. */
	private static final int MAX_RECOVERIES = 3;
	private static final Logger logger = Logging.get(ClaudeAgent.class);
	private static final Gson GSON = new Gson();
	private final List<BetaMessageParam> history = new ArrayList<>();
	private final AgentToolbox toolbox;
	private final AgentConfig config;
	private final ClaudeModel model;
	private final AgentListener listener;
	private final ToolCallRunner toolRunner;
	private final AnthropicClient client;
	private final List<BetaTool> tools;
	private final List<BetaTextBlockParam> system;
	private final Object streamLock = new Object();
	private StreamResponse<BetaRawMessageStreamEvent> activeStream;
	private volatile boolean cancelled;
	private volatile boolean running;
	private volatile String lastReply;
	private long inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens;

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
	public ClaudeAgent(@Nonnull AgentToolbox toolbox, @Nonnull AgentConfig config,
	                   @Nonnull AgentListener listener, @Nullable ToolApprover approver) {
		this.toolbox = toolbox;
		this.config = config;
		this.model = ClaudeModel.of(config.model());
		this.listener = listener;
		this.toolRunner = new ToolCallRunner(toolbox, config.approvalMode(), listener, approver, () -> cancelled);

		AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
				.apiKey(config.apiKey())
				// Single requests of an agentic turn can run for many minutes while streaming.
				.timeout(Duration.ofMinutes(30));
		if (config.baseUrl() != null && !config.baseUrl().isBlank())
			builder.baseUrl(config.baseUrl());
		this.client = builder.build();

		// Built once and reused for every request, see "The conversation is append-only" above.
		this.tools = buildTools(toolbox);
		this.system = List.of(BetaTextBlockParam.builder()
				.text(Playbooks.SYSTEM_PROMPT)
				// Cache breakpoint covering the tool definitions and the system prompt.
				.cacheControl(BetaCacheControlEphemeral.builder().build())
				.build());
	}

	@Override
	@Nonnull
	public AgentTurn send(@Nonnull String userMessage) {
		synchronized (history) {
			history.add(BetaMessageParam.ofUser(userMessage));
		}
		return run();
	}

	@Override
	@Nonnull
	public AgentTurn resume() {
		synchronized (history) {
			if (history.isEmpty())
				return new AgentTurn(AgentTurn.Outcome.ERROR, "There is no conversation to continue.");
			// A conversation that ended on the agent's reply needs a user turn for the model to respond to.
			if (history.get(history.size() - 1).isAssistant())
				history.add(BetaMessageParam.ofUser("Continue."));
		}
		return run();
	}

	@Override
	public void cancel() {
		cancelled = true;
		toolbox.context().setCancelled(true);
		synchronized (streamLock) {
			if (activeStream != null) {
				try {
					activeStream.close();
				} catch (RuntimeException ignored) {
					// The reading side reports the interruption.
				}
			}
		}
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

	@Override
	@Nonnull
	public AgentListener.Usage usage() {
		Double cost = null;
		if (model.inputPrice() > 0) {
			// Cache writes are billed at a premium over regular input, 1.25x for the default five minute lifetime.
			cost = (inputTokens * model.inputPrice()
					+ cacheWriteTokens * model.inputPrice() * 1.25
					+ cacheReadTokens * model.cacheReadPrice()
					+ outputTokens * model.outputPrice()) / 1_000_000.0;
		}
		return new AgentListener.Usage(inputTokens, outputTokens, cacheReadTokens, cacheWriteTokens, cost);
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
		} catch (UnauthorizedException | PermissionDeniedException ex) {
			return new AgentTurn(AgentTurn.Outcome.AUTH_ERROR, "The API key was not accepted: " + describe(ex));
		} catch (RateLimitException | OverloadedException ex) {
			return new AgentTurn(AgentTurn.Outcome.BUSY, "The API is rate limiting or overloaded, try again shortly: " + describe(ex));
		} catch (BadRequestException | NotFoundException ex) {
			return new AgentTurn(AgentTurn.Outcome.INVALID_REQUEST, "The API rejected the request: " + describe(ex));
		} catch (AnthropicServiceException ex) {
			return new AgentTurn(ex.statusCode() >= 500 ? AgentTurn.Outcome.BUSY : AgentTurn.Outcome.ERROR,
					"The API returned an error (HTTP " + ex.statusCode() + "): " + describe(ex));
		} catch (AnthropicIoException ex) {
			if (cancelled)
				return new AgentTurn(AgentTurn.Outcome.CANCELLED, null);
			return new AgentTurn(AgentTurn.Outcome.NETWORK_ERROR, "Could not reach the API: " + describe(ex));
		} catch (AnthropicException ex) {
			if (cancelled)
				return new AgentTurn(AgentTurn.Outcome.CANCELLED, null);
			return new AgentTurn(AgentTurn.Outcome.ERROR, describe(ex));
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
	private AgentTurn loop() {
		int recoveries = 0;
		for (int step = 0; step < MAX_STEPS; step++) {
			if (cancelled)
				return new AgentTurn(AgentTurn.Outcome.CANCELLED, null);

			BetaMessage message;
			try {
				message = request();
			} catch (AnthropicInvalidDataException ex) {
				// The stream carried something that could not be assembled into a message, typically a tool input
				// that is not valid JSON. There is no complete tool call to answer, so the request is issued again.
				if (cancelled)
					return new AgentTurn(AgentTurn.Outcome.CANCELLED, null);
				if (++recoveries > MAX_RECOVERIES)
					return new AgentTurn(AgentTurn.Outcome.ERROR, "The model kept producing malformed responses: " + describe(ex));
				logger.warn("Discarding malformed response and retrying", ex);
				continue;
			}
			if (message == null)
				return new AgentTurn(AgentTurn.Outcome.CANCELLED, null);
			record(message.usage());
			reportFallback(message);

			BetaStopReason stopReason = message.stopReason().orElse(null);
			if (BetaStopReason.REFUSAL.equals(stopReason)) {
				// A declined response is not appended and nothing in it is acted on.
				String category = message.stopDetails().flatMap(details -> details.category()).map(c -> c.asString()).orElse(null);
				String explanation = message.stopDetails().flatMap(details -> details.explanation()).orElse(null);
				return new AgentTurn(AgentTurn.Outcome.REFUSED, "The request was declined by a safety classifier" +
						(category == null ? "" : " (" + category + ")") + "." +
						(explanation == null ? "" : " " + explanation));
			}

			synchronized (history) {
				history.add(message.toParam());
			}
			List<BetaToolUseBlock> toolCalls = new ArrayList<>();
			StringBuilder reply = new StringBuilder();
			for (BetaContentBlock block : message.content()) {
				block.toolUse().ifPresent(toolCalls::add);
				block.text().ifPresent(text -> reply.append(text.text()));
			}
			if (reply.length() > 0)
				lastReply = reply.toString();

			if (BetaStopReason.PAUSE_TURN.equals(stopReason))
				continue; // The API resumes a paused turn when the conversation is sent back unchanged.
			if (toolCalls.isEmpty()) {
				if (BetaStopReason.MAX_TOKENS.equals(stopReason))
					return new AgentTurn(AgentTurn.Outcome.TRUNCATED, "The reply was cut off by the response size limit.");
				return new AgentTurn(AgentTurn.Outcome.COMPLETED, null);
			}

			// A response that ran into the size limit may end in a tool call with half of its arguments.
			boolean cutOff = BetaStopReason.MAX_TOKENS.equals(stopReason);
			if (cutOff && ++recoveries > MAX_RECOVERIES)
				return new AgentTurn(AgentTurn.Outcome.TRUNCATED, "The agent's tool calls kept exceeding the response size limit.");

			List<BetaContentBlockParam> results = new ArrayList<>(toolCalls.size());
			for (BetaToolUseBlock call : toolCalls) {
				ToolResult result = cutOff ?
						ToolResult.error("This tool call was cut off by the response size limit and was not run. " +
								"Call it again with smaller arguments, or split the work into several calls.") :
						execute(call);
				results.add(BetaContentBlockParam.ofToolResult(BetaToolResultBlockParam.builder()
						.toolUseId(call.id())
						.content(result.text())
						.isError(result.error())
						.build()));
			}
			synchronized (history) {
				history.add(BetaMessageParam.ofUserOfContentBlockParams(results));
			}
		}
		return new AgentTurn(AgentTurn.Outcome.STEP_LIMIT, "The agent stopped after " + MAX_STEPS +
				" steps without finishing. Continue to let it keep going.");
	}

	/**
	 * Requests the next response and reports it to the listener while it streams in.
	 *
	 * @return The complete response, or {@code null} when the agent was cancelled before it completed.
	 * Nothing of a cancelled response is kept.
	 */
	@Nullable
	private BetaMessage request() {
		MessageCreateParams.Builder params = MessageCreateParams.builder()
				.model(config.model())
				.maxTokens(config.maxTokens())
				.systemOfBetaTextBlockParams(system)
				// Thinking depth is governed by effort. Summaries are requested so that the user sees what the agent
				// is doing during long stretches of tool use, the default would return empty thinking blocks.
				.thinking(BetaThinkingConfigAdaptive.builder()
						.display(BetaThinkingConfigAdaptive.Display.SUMMARIZED)
						.build())
				.outputConfig(BetaOutputConfig.builder()
						.effort(BetaOutputConfig.Effort.of(config.effort()))
						.build())
				// Automatic cache breakpoint on the conversation tail. Each request then re-reads everything before
				// the newest messages from the cache instead of paying for it again.
				.cacheControl(BetaCacheControlEphemeral.builder().build());
		for (BetaTool tool : tools)
			params.addTool(tool);
		if (config.refusalFallback() && model.refusalFallback()) {
			// If a safety classifier declines a request, the API retries it on the model it recommends for that
			// category instead of failing the turn. Reverse engineering reads like offense to a classifier often
			// enough that this is worth having on by default.
			params.addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01).fallbacksDefault();
		}
		synchronized (history) {
			params.messages(new ArrayList<>(history));
		}

		BetaMessageAccumulator accumulator = BetaMessageAccumulator.create();
		StreamResponse<BetaRawMessageStreamEvent> stream = client.beta().messages().createStreaming(params.build());
		synchronized (streamLock) {
			activeStream = stream;
		}
		try {
			for (BetaRawMessageStreamEvent event : stream) {
				if (cancelled)
					return null;
				accumulator.accumulate(event);
				event.contentBlockStart().ifPresent(start ->
						start.contentBlock().toolUse().ifPresent(call -> listener.onToolCallStarted(call.id(), call.name())));
				event.contentBlockDelta().ifPresent(delta -> {
					delta.delta().text().ifPresent(text -> listener.onText(text.text()));
					delta.delta().thinking().ifPresent(thinking -> listener.onThinking(thinking.thinking()));
				});
			}
			if (cancelled)
				return null;
			return accumulator.message();
		} catch (RuntimeException ex) {
			// Closing the stream from cancel() surfaces here as a read failure.
			if (cancelled)
				return null;
			throw ex;
		} finally {
			synchronized (streamLock) {
				activeStream = null;
			}
			stream.close();
		}
	}

	/**
	 * Runs one tool call requested by the model, subject to the approval policy.
	 */
	@Nonnull
	private ToolResult execute(@Nonnull BetaToolUseBlock call) {
		// Tool inputs are streamed without server-side validation, so the input is not trusted to be an object.
		JsonElement input;
		try {
			input = GSON.toJsonTree(call._input().convert(Object.class));
		} catch (RuntimeException ex) {
			input = null;
		}
		return toolRunner.run(call.id(), call.name(), input, String.valueOf(call._input()));
	}

	private void record(@Nonnull BetaUsage usage) {
		inputTokens += usage.inputTokens();
		outputTokens += usage.outputTokens();
		cacheReadTokens += usage.cacheReadInputTokens().orElse(0L);
		cacheWriteTokens += usage.cacheCreationInputTokens().orElse(0L);
		listener.onUsage(usage());
	}

	/**
	 * Tells the listener when a response was produced by a fallback model after the configured one declined.
	 */
	private void reportFallback(@Nonnull BetaMessage message) {
		for (BetaContentBlock block : message.content()) {
			block.fallback().ifPresent(fallback -> {
				try {
					listener.onModelFallback(fallback.from().model().asString(), fallback.to().model().asString());
				} catch (RuntimeException ex) {
					// The notice is informational, a shape this SDK version does not model must not fail the turn.
					logger.debug("Could not read fallback details", ex);
				}
			});
		}
	}

	/**
	 * @param toolbox
	 * 		Toolbox to describe to the model.
	 *
	 * @return The tool definitions sent with every request.
	 */
	@Nonnull
	private static List<BetaTool> buildTools(@Nonnull AgentToolbox toolbox) {
		List<BetaTool> tools = new ArrayList<>();
		for (AgentTool tool : toolbox.tools()) {
			JsonObject schema = tool.inputSchema();
			BetaTool.InputSchema.Properties.Builder properties = BetaTool.InputSchema.Properties.builder();
			JsonObject schemaProperties = schema.getAsJsonObject("properties");
			for (Map.Entry<String, JsonElement> property : schemaProperties.entrySet())
				properties.putAdditionalProperty(property.getKey(), toJsonValue(property.getValue()));
			List<String> required = new ArrayList<>();
			JsonArray schemaRequired = schema.getAsJsonArray("required");
			for (JsonElement name : schemaRequired)
				required.add(name.getAsString());

			tools.add(BetaTool.builder()
					.name(tool.name())
					.description(tool.description())
					.inputSchema(BetaTool.InputSchema.builder()
							.properties(properties.build())
							.required(required)
							.putAdditionalProperty("additionalProperties", JsonValue.from(false))
							.build())
					// Stream tool inputs as they are generated rather than after the API has buffered and validated
					// them. Assembly sources and scripts can be long, and without this nothing would be shown while
					// they are written. The price is that inputs must be validated here, which the toolbox does.
					.eagerInputStreaming(true)
					.build());
		}
		return tools;
	}

	@Nonnull
	private static JsonValue toJsonValue(@Nonnull JsonElement element) {
		Object plain = GSON.fromJson(element, new TypeToken<Object>() {}.getType());
		return JsonValue.from(plain);
	}

	@Nonnull
	private static String describe(@Nonnull Throwable t) {
		String message = t.getMessage();
		return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
	}

	@Override
	@Nonnull
	public String exportConversation() {
		synchronized (history) {
			try {
				return com.anthropic.core.ObjectMappers.jsonMapper().writeValueAsString(history);
			} catch (Exception ex) {
				throw new IllegalStateException("Failed to serialize the conversation", ex);
			}
		}
	}

	@Override
	public void importConversation(@Nonnull String json) {
		List<BetaMessageParam> restored;
		try {
			restored = com.anthropic.core.ObjectMappers.jsonMapper().readValue(json,
					new com.fasterxml.jackson.core.type.TypeReference<List<BetaMessageParam>>() {});
		} catch (Exception ex) {
			throw new IllegalArgumentException("Not an exported conversation", ex);
		}
		synchronized (history) {
			history.clear();
			history.addAll(restored);
		}
	}

	@Override
	@Nonnull
	public Optional<String> lastReply() {
		return Optional.ofNullable(lastReply);
	}
}
