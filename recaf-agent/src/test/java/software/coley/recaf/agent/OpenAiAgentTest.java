package software.coley.recaf.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.coley.recaf.agent.chat.AgentListener;
import software.coley.recaf.agent.chat.AgentTurn;
import software.coley.recaf.agent.chat.ApprovalMode;
import software.coley.recaf.agent.chat.ModelInfo;
import software.coley.recaf.agent.chat.ToolApprover;
import software.coley.recaf.agent.openai.OpenAiAgent;
import software.coley.recaf.agent.openai.OpenAiClient;
import software.coley.recaf.agent.openai.OpenAiConfig;
import software.coley.recaf.agent.openai.OpenAiException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static software.coley.recaf.agent.MockOpenAiApi.*;

/**
 * Tests for {@link OpenAiAgent} and {@link OpenAiClient} against a scripted API.
 */
class OpenAiAgentTest extends AgentTestBase {
	private MockOpenAiApi api;
	private final List<String> events = new ArrayList<>();
	private final AgentListener listener = new AgentListener() {
		@Override
		public void onText(String text) {
			events.add("text:" + text);
		}

		@Override
		public void onThinking(String text) {
			events.add("thinking:" + text);
		}

		@Override
		public void onToolCallStarted(String callId, String toolName) {
			events.add("started:" + callId + ":" + toolName);
		}

		@Override
		public void onToolCallReady(String callId, String toolName, String argumentsJson) {
			events.add("ready:" + callId + ":" + argumentsJson);
		}

		@Override
		public void onToolCallFinished(String callId, String result, boolean error, long durationMillis) {
			events.add("finished:" + callId + ":" + (error ? "error" : "ok") + ":" + result);
		}
	};

	@BeforeEach
	void startApi() throws Exception {
		api = new MockOpenAiApi();
		openSample("hello.jar");
	}

	@AfterEach
	void stopApi() {
		api.close();
	}

	private OpenAiAgent agent(ApprovalMode mode, ToolApprover approver) {
		return new OpenAiAgent(toolbox, new OpenAiConfig(api.baseUrl(), "test-key", "test-model", mode), listener, approver);
	}

	@Test
	void runsToolsUntilTheModelIsDone() {
		// The arguments of a tool call arrive in pieces, as do the reply's words.
		api.stream(reasoning("Looking at the class first."),
						toolStart(0, "call_1", "get_class_info"),
						toolArguments(0, "{\"class\": \"sam"),
						toolArguments(0, "ple.Hello\"}"),
						finish("tool_calls"),
						usage(900, 30, 400))
				.stream(text("sample/Hello "), text("implements Runnable."), finish("stop"), usage(1000, 12, 900));
		OpenAiAgent agent = agent(ApprovalMode.ASK_BEFORE_CHANGES, null);

		AgentTurn turn = agent.send("What does Hello implement?");
		assertEquals(AgentTurn.Outcome.COMPLETED, turn.outcome(), turn.detail());
		assertEquals("sample/Hello implements Runnable.", agent.lastReply().orElseThrow());

		// What was sent
		assertEquals(List.of("/v1/chat/completions", "/v1/chat/completions"), api.paths);
		assertEquals("Bearer test-key", api.authorizations.get(0));
		JsonObject first = api.requests.get(0);
		assertEquals("test-model", first.get("model").getAsString());
		assertTrue(first.get("stream").getAsBoolean());
		assertTrue(first.getAsJsonObject("stream_options").get("include_usage").getAsBoolean());
		assertEquals(toolbox.tools().size(), first.getAsJsonArray("tools").size());
		JsonObject firstTool = first.getAsJsonArray("tools").get(0).getAsJsonObject();
		assertEquals("function", firstTool.get("type").getAsString());
		assertTrue(firstTool.getAsJsonObject("function").getAsJsonObject("parameters").has("properties"));
		JsonArray firstMessages = first.getAsJsonArray("messages");
		assertEquals("system", firstMessages.get(0).getAsJsonObject().get("role").getAsString());
		assertTrue(firstMessages.get(0).getAsJsonObject().get("content").getAsString().contains("Recaf"));
		assertEquals("What does Hello implement?", firstMessages.get(1).getAsJsonObject().get("content").getAsString());

		// The second request carries the call as the model wrote it, and its result
		JsonArray second = api.requests.get(1).getAsJsonArray("messages");
		assertEquals(4, second.size());
		JsonObject assistant = second.get(2).getAsJsonObject();
		assertEquals("assistant", assistant.get("role").getAsString());
		assertTrue(assistant.get("content").isJsonNull());
		JsonObject call = assistant.getAsJsonArray("tool_calls").get(0).getAsJsonObject();
		assertEquals("call_1", call.get("id").getAsString());
		assertEquals("{\"class\": \"sample.Hello\"}", call.getAsJsonObject("function").get("arguments").getAsString());
		JsonObject result = second.get(3).getAsJsonObject();
		assertEquals("tool", result.get("role").getAsString());
		assertEquals("call_1", result.get("tool_call_id").getAsString());
		assertTrue(result.get("content").getAsString().contains("java/lang/Runnable"), result.toString());

		// What the listener saw, in order
		assertEquals("thinking:Looking at the class first.", events.get(0));
		assertEquals("started:call_1:get_class_info", events.get(1));
		assertEquals("ready:call_1:{\"class\":\"sample.Hello\"}", events.get(2));
		assertTrue(events.get(3).startsWith("finished:call_1:ok:"), events.get(3));
		assertEquals(List.of("text:sample/Hello ", "text:implements Runnable."), events.subList(4, 6));

		AgentListener.Usage usage = agent.usage();
		assertEquals(1900 - 1300, usage.inputTokens());
		assertEquals(1300, usage.cacheReadTokens());
		assertEquals(42, usage.outputTokens());
		assertNull(usage.estimatedCost());
		assertEquals(4, agent.messageCount());
	}

	@Test
	void modifyingToolsFollowTheApprovalMode() {
		String comment = "{\"class\": \"sample.Hello\", \"comment\": \"entry\"}";
		String finding = "{\"severity\": \"info\", \"title\": \"t\", \"detail\": \"d\"}";
		api.stream(toolStart(0, "call_1", "set_comment"), toolArguments(0, comment),
						toolStart(1, "call_2", "record_finding"), toolArguments(1, finding), finish("tool_calls"))
				.stream(text("ok"), finish("stop"));
		agent(ApprovalMode.READ_ONLY, (tool, args) -> fail("Must not ask in read-only mode")).send("Comment it");
		JsonArray messages = api.requests.get(1).getAsJsonArray("messages");
		// Both calls are answered, in order: the change is declined, the note is taken.
		assertTrue(messages.get(3).getAsJsonObject().get("content").getAsString().startsWith("Error: Not run: the user has restricted you to read-only"));
		assertTrue(messages.get(4).getAsJsonObject().get("content").getAsString().contains("Recorded finding"));
		assertTrue(call("get_comments", "class", "sample.Hello").startsWith("No comments"));

		api.stream(toolStart(0, "call_3", "set_comment"), toolArguments(0, comment), finish("tool_calls"))
				.stream(text("ok"), finish("stop"));
		List<String> asked = new ArrayList<>();
		agent(ApprovalMode.ASK_BEFORE_CHANGES, (tool, args) -> {
			asked.add(tool.name());
			return ToolApprover.Decision.ALLOW;
		}).send("Comment it");
		assertEquals(List.of("set_comment"), asked);
		assertTrue(call("get_comments", "class", "sample.Hello").contains("entry"));
	}

	@Test
	void toleratesServersThatBendTheFormat() {
		// No stream at all, the reasoning inside the reply, a call without identifier, arguments as JSON instead of text.
		api.whole("{\"choices\":[{\"index\":0,\"finish_reason\":\"tool_calls\",\"message\":{\"role\":\"assistant\"," +
						"\"content\":\"<think>Which class?</think>\\nChecking.\",\"tool_calls\":[{\"type\":\"function\"," +
						"\"function\":{\"name\":\"get_class_info\",\"arguments\":{\"class\":\"sample.Hello\"}}}]}}]," +
						"\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}")
				// Reasoning tags split across pieces of a stream, and a call that the model wrote with broken JSON.
				.stream(text("<thi"), text("nk>Now the"), text(" answer</th"), text("ink>\n\nIt "), text("is a Runnable."),
						toolStart(0, "call_9", "get_class_info"), toolArguments(0, "{\"class\": "), finish("tool_calls"))
				.stream(text("Done."), finish("stop"));
		OpenAiAgent agent = agent(ApprovalMode.READ_ONLY, null);
		assertEquals(AgentTurn.Outcome.COMPLETED, agent.send("Go").outcome());

		assertEquals("thinking:Which class?", events.get(0));
		assertEquals("text:Checking.", events.get(1));
		assertTrue(events.get(2).startsWith("started:call_"), events.get(2));
		String generatedId = events.get(2).split(":")[1];

		// The reasoning is not sent back, the reply and the call are, under the identifier made up for it.
		JsonArray second = api.requests.get(1).getAsJsonArray("messages");
		JsonObject assistant = second.get(2).getAsJsonObject();
		assertEquals("Checking.", assistant.get("content").getAsString());
		assertEquals(generatedId, assistant.getAsJsonArray("tool_calls").get(0).getAsJsonObject().get("id").getAsString());
		assertEquals(generatedId, second.get(3).getAsJsonObject().get("tool_call_id").getAsString());
		assertTrue(second.get(3).getAsJsonObject().get("content").getAsString().contains("java/lang/Runnable"));

		assertTrue(events.contains("thinking:Now the"), events.toString());
		assertTrue(events.contains("text:It "), events.toString());
		JsonArray third = api.requests.get(2).getAsJsonArray("messages");
		assertEquals("It is a Runnable.", third.get(4).getAsJsonObject().get("content").getAsString());
		String broken = third.get(5).getAsJsonObject().get("content").getAsString();
		assertTrue(broken.startsWith("Error: The tool input was not a JSON object"), broken);
		assertEquals("Done.", agent.lastReply().orElseThrow());
	}

	@Test
	void doesWithoutUsageWhenTheServerRejectsTheOption() {
		api.fail(400, "{\"error\":{\"message\":\"Unrecognized request argument supplied: stream_options\",\"type\":\"invalid_request_error\"}}", null)
				.stream(text("Hello."), finish("stop"))
				.stream(text("Again."), finish("stop"));
		OpenAiAgent agent = agent(ApprovalMode.READ_ONLY, null);
		assertEquals(AgentTurn.Outcome.COMPLETED, agent.send("Hi").outcome());
		assertTrue(api.requests.get(0).has("stream_options"));
		assertFalse(api.requests.get(1).has("stream_options"));
		// Remembered for the rest of the conversation.
		assertEquals(AgentTurn.Outcome.COMPLETED, agent.send("Once more").outcome());
		assertFalse(api.requests.get(2).has("stream_options"));
		assertEquals(3, api.requests.size());
	}

	@Test
	void errorsAreReportedAsOutcomes() {
		api.fail(401, "{\"error\":{\"message\":\"Incorrect API key provided\"}}", null);
		AgentTurn unauthorized = agent(ApprovalMode.READ_ONLY, null).send("Hi");
		assertEquals(AgentTurn.Outcome.AUTH_ERROR, unauthorized.outcome());
		assertTrue(unauthorized.detail().contains("Incorrect API key"), unauthorized.detail());

		// Rate limits are waited out, as long as the server asks for no more than a moment.
		api.fail(429, "{\"error\":{\"message\":\"Slow down\"}}", "0").stream(text("Fine."), finish("stop"));
		assertEquals(AgentTurn.Outcome.COMPLETED, agent(ApprovalMode.READ_ONLY, null).send("Hi").outcome());
		api.fail(429, "{\"error\":\"Slow down\"}", "0").fail(429, "{\"error\":\"Slow down\"}", "0").fail(429, "{\"error\":\"Slow down\"}", "0");
		AgentTurn busy = agent(ApprovalMode.READ_ONLY, null).send("Hi");
		assertEquals(AgentTurn.Outcome.BUSY, busy.outcome());
		assertTrue(busy.outcome().canResume());

		api.fail(400, "{\"error\":{\"message\":\"registry.example/tiny:latest does not support tools\"}}", null);
		AgentTurn noTools = agent(ApprovalMode.READ_ONLY, null).send("Hi");
		assertEquals(AgentTurn.Outcome.INVALID_REQUEST, noTools.outcome());
		assertTrue(noTools.detail().contains("Pick a model that supports tool calling"), noTools.detail());

		api.fail(400, "{\"detail\":\"This model's maximum context length is 8192 tokens\"}", null);
		AgentTurn tooLong = agent(ApprovalMode.READ_ONLY, null).send("Hi");
		assertTrue(tooLong.detail().contains("larger context window"), tooLong.detail());

		// An error that arrives inside a stream which had started successfully.
		api.stream("{\"error\":{\"message\":\"Upstream provider failed\",\"code\":400}}");
		AgentTurn midStream = agent(ApprovalMode.READ_ONLY, null).send("Hi");
		assertEquals(AgentTurn.Outcome.INVALID_REQUEST, midStream.outcome());
		assertTrue(midStream.detail().contains("Upstream provider failed"), midStream.detail());

		// Nothing listening at the address.
		OpenAiAgent unreachable = new OpenAiAgent(toolbox, new OpenAiConfig("http://127.0.0.1:1/v1", null, "m", ApprovalMode.READ_ONLY), listener, null);
		CompletableFuture<AgentTurn> pending = CompletableFuture.supplyAsync(() -> unreachable.send("Hi"));
		// Connection failures are retried with pauses in between, stopping the agent ends the wait.
		unreachable.cancel();
		AgentTurn refused = pending.join();
		assertTrue(refused.outcome() == AgentTurn.Outcome.CANCELLED || refused.outcome() == AgentTurn.Outcome.NETWORK_ERROR, refused.toString());
	}

	@Test
	void cutOffAndEmptyResponsesAreNotMistakenForAnswers() {
		api.stream(toolStart(0, "call_1", "record_finding"), toolArguments(0, "{\"severity\": \"info\", \"ti"), finish("length"))
				.stream(text("Recovered."), finish("stop"));
		OpenAiAgent agent = agent(ApprovalMode.ALLOW_CHANGES, null);
		assertEquals(AgentTurn.Outcome.COMPLETED, agent.send("Go").outcome());
		String answer = api.requests.get(1).getAsJsonArray("messages").get(3).getAsJsonObject().get("content").getAsString();
		assertTrue(answer.contains("cut off"), answer);
		assertTrue(toolbox.context().state().findings().isEmpty());

		api.stream(text("A long answer that"), finish("length"));
		assertEquals(AgentTurn.Outcome.TRUNCATED, agent.send("More").outcome());

		api.stream(finish("stop"));
		int before = agent.messageCount();
		AgentTurn empty = agent.send("And?");
		assertEquals(AgentTurn.Outcome.ERROR, empty.outcome());
		// The question stays, no empty reply is put after it, so that continuing asks again.
		assertEquals(before + 1, agent.messageCount());

		api.stream(finish("content_filter"));
		assertEquals(AgentTurn.Outcome.REFUSED, agent(ApprovalMode.READ_ONLY, null).send("Hi").outcome());
	}

	@Test
	void stoppingBreaksOffAStalledResponseAndTheConversationContinues() throws Exception {
		api.stall(text("Thinking about"), text(" it"), finish("stop")).stream(text("Here it is."), finish("stop"));
		OpenAiAgent agent = agent(ApprovalMode.READ_ONLY, null);
		CompletableFuture<AgentTurn> pending = CompletableFuture.supplyAsync(() -> agent.send("Hi"));
		assertTrue(api.stalled.await(10, TimeUnit.SECONDS));
		agent.cancel();
		assertEquals(AgentTurn.Outcome.CANCELLED, pending.get(15, TimeUnit.SECONDS).outcome());
		// Nothing of the broken-off response was kept.
		assertEquals(1, agent.messageCount());

		assertEquals(AgentTurn.Outcome.COMPLETED, agent.resume().outcome());
		assertEquals(1, api.requests.get(1).getAsJsonArray("messages").size() - 1);
		assertEquals("Here it is.", agent.lastReply().orElseThrow());

		// A conversation moves to another agent of the same kind.
		OpenAiAgent successor = agent(ApprovalMode.READ_ONLY, null);
		successor.importConversation(agent.exportConversation());
		assertEquals(2, successor.messageCount());
		assertThrows(IllegalArgumentException.class, () -> successor.importConversation("{\"not\":\"a list\"}"));
	}

	@Test
	void listsTheModelsOfAnApi() throws Exception {
		OpenAiClient.ModelList models = new OpenAiClient(api.baseUrl(), "test-key").listModels();
		assertEquals(List.of("alpha-large", "zeta-small"), models.models().stream().map(ModelInfo::id).toList());
		assertEquals(api.baseUrl(), models.baseUrl());
		assertEquals("Bearer test-key", api.authorizations.get(0));

		// The address of the server without its version segment: found one level down.
		OpenAiClient.ModelList corrected = new OpenAiClient(api.root() + "/", null).listModels();
		assertEquals(api.baseUrl(), corrected.baseUrl());
		assertEquals(2, corrected.models().size());
		assertNull(api.authorizations.get(api.authorizations.size() - 1));

		// Other shapes servers answer with.
		api.modelsBody = "[{\"name\":\"local-a\"},\"local-b\",{\"id\":\"models/local-c\",\"display_name\":\"Local C\"}]";
		List<ModelInfo> loose = new OpenAiClient(api.baseUrl(), null).listModels().models();
		assertEquals(List.of("local-a", "local-b", "local-c"), loose.stream().map(ModelInfo::id).toList());
		assertEquals("Local C", loose.get(2).name());

		api.modelsStatus = 401;
		api.modelsBody = "{\"error\":{\"message\":\"Invalid key\"}}";
		OpenAiException rejected = assertThrows(OpenAiException.class, () -> new OpenAiClient(api.baseUrl(), "bad").listModels());
		assertEquals(401, rejected.status());
		assertEquals("Invalid key", rejected.getMessage());

		api.modelsStatus = 200;
		api.modelsBody = "<html>Welcome</html>";
		assertTrue(assertThrows(java.io.IOException.class, () -> new OpenAiClient(api.baseUrl(), null).listModels())
				.getMessage().contains("not with a model list"));
	}

	@Test
	void tidiesAddressesPeopleEnter() {
		assertEquals("https://api.openai.com/v1", OpenAiConfig.normalizeBaseUrl(" https://api.openai.com/v1/ "));
		assertEquals("https://api.openai.com/v1", OpenAiConfig.normalizeBaseUrl("https://api.openai.com/v1/chat/completions"));
		assertEquals("https://openrouter.ai/api/v1", OpenAiConfig.normalizeBaseUrl("openrouter.ai/api/v1"));
		assertEquals("http://192.168.1.20:11434/v1", OpenAiConfig.normalizeBaseUrl("192.168.1.20:11434/v1"));
		assertEquals("http://localhost:1234/v1", OpenAiConfig.normalizeBaseUrl("localhost:1234/v1/models"));
		assertEquals("", OpenAiConfig.normalizeBaseUrl("  "));
		assertTrue(OpenAiConfig.isPlainHttp("http://nas.local:8080/v1"));
		assertFalse(OpenAiConfig.isPlainHttp("https://api.openai.com/v1"));
	}
}
