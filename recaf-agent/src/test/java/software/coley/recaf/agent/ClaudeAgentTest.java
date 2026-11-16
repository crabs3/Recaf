package software.coley.recaf.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.coley.recaf.agent.chat.ApprovalMode;
import software.coley.recaf.agent.claude.AgentConfig;
import software.coley.recaf.agent.chat.AgentListener;
import software.coley.recaf.agent.chat.AgentTurn;
import software.coley.recaf.agent.claude.ClaudeAgent;
import software.coley.recaf.agent.chat.ToolApprover;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static software.coley.recaf.agent.MockAnthropicApi.*;

/**
 * Tests of the agent loop against a scripted stand-in for the Anthropic API, exercised through the real SDK.
 */
class ClaudeAgentTest extends AgentTestBase {
	private MockAnthropicApi api;
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
		api = new MockAnthropicApi();
		openSample("hello.jar");
	}

	@AfterEach
	void stopApi() {
		api.close();
	}

	private ClaudeAgent agent(ApprovalMode mode, ToolApprover approver) {
		AgentConfig config = new AgentConfig("test-key", api.baseUrl(), "claude-opus-5-5", "medium", 64_000, true, mode);
		return new ClaudeAgent(toolbox, config, listener, approver);
	}

	@Test
	void runsToolsUntilTheModelIsDone() {
		api.respond("tool_use", thinking("Looking at the class first."),
						toolUse("toolu_1", "get_class_info", "{\"class\": \"sample.Hello\"}"))
				.respond("end_turn", text("sample/Hello implements Runnable."));
		ClaudeAgent agent = agent(ApprovalMode.ASK_BEFORE_CHANGES, null);

		AgentTurn turn = agent.send("What does Hello implement?");
		assertEquals(AgentTurn.Outcome.COMPLETED, turn.outcome(), String.valueOf(turn.detail()));
		assertEquals("sample/Hello implements Runnable.", agent.lastReply().orElseThrow());
		assertEquals(2, api.requests.size());

		// The request carries the fixed prefix: system prompt, tools, and the options the agent relies on.
		JsonObject first = api.requests.get(0);
		assertEquals("claude-opus-5-5", first.get("model").getAsString());
		assertEquals(64_000, first.get("max_tokens").getAsInt());
		assertTrue(first.get("stream").getAsBoolean());
		assertEquals("adaptive", first.getAsJsonObject("thinking").get("type").getAsString());
		assertEquals("summarized", first.getAsJsonObject("thinking").get("display").getAsString());
		assertEquals("medium", first.getAsJsonObject("output_config").get("effort").getAsString());
		assertEquals("ephemeral", first.getAsJsonObject("cache_control").get("type").getAsString());
		assertEquals("default", first.get("fallbacks").getAsString());
		assertTrue(api.betaHeaders.get(0).contains("server-side-fallback-2026-07-01"), api.betaHeaders.get(0));
		assertEquals("test-key", api.apiKeys.get(0));
		JsonObject system = first.getAsJsonArray("system").get(0).getAsJsonObject();
		assertTrue(system.get("text").getAsString().contains("Recaf"));
		assertEquals("ephemeral", system.getAsJsonObject("cache_control").get("type").getAsString());
		JsonArray tools = first.getAsJsonArray("tools");
		assertEquals(toolbox.tools().size(), tools.size());
		for (int i = 0; i < tools.size(); i++) {
			JsonObject tool = tools.get(i).getAsJsonObject();
			assertTrue(tool.get("eager_input_streaming").getAsBoolean());
			assertEquals("object", tool.getAsJsonObject("input_schema").get("type").getAsString());
			assertFalse(tool.getAsJsonObject("input_schema").get("additionalProperties").getAsBoolean());
		}
		assertFalse(first.has("tool_choice"), "Forced tool use is rejected by current models");
		assertFalse(first.has("temperature"));

		// The second request replays the first verbatim and appends the response and all tool results.
		JsonObject second = api.requests.get(1);
		assertEquals(first.get("system"), second.get("system"));
		assertEquals(first.get("tools"), second.get("tools"));
		JsonArray messages = second.getAsJsonArray("messages");
		assertEquals(3, messages.size());
		assertEquals(first.getAsJsonArray("messages").get(0), messages.get(0));
		JsonArray assistant = messages.get(1).getAsJsonObject().getAsJsonArray("content");
		assertEquals("thinking", assistant.get(0).getAsJsonObject().get("type").getAsString());
		assertEquals("c2lnbmF0dXJl", assistant.get(0).getAsJsonObject().get("signature").getAsString(), "Thinking is passed back unchanged");
		assertEquals("tool_use", assistant.get(1).getAsJsonObject().get("type").getAsString());
		JsonObject result = messages.get(2).getAsJsonObject().getAsJsonArray("content").get(0).getAsJsonObject();
		assertEquals("tool_result", result.get("type").getAsString());
		assertEquals("toolu_1", result.get("tool_use_id").getAsString());
		assertTrue(result.get("content").getAsString().contains("implements java/lang/Runnable"));

		// The listener saw the turn unfold in order.
		assertEquals("thinking:Looking at the class first.", events.get(0));
		assertEquals("started:toolu_1:get_class_info", events.get(1));
		assertTrue(events.get(2).startsWith("ready:toolu_1:{\"class\":\"sample.Hello\"}"), events.get(2));
		assertTrue(events.get(3).startsWith("finished:toolu_1:ok:"), events.get(3));
		assertEquals("text:sample/Hello implements Runnable.", events.get(4) + events.get(5).substring(5));

		AgentListener.Usage usage = agent.usage();
		assertEquals(200, usage.inputTokens());
		assertEquals(50, usage.outputTokens());
		assertEquals(80, usage.cacheReadTokens());
		assertNotNull(usage.estimatedCost());
	}

	@Test
	void answersEveryToolCallOfAResponseInOneMessage() {
		api.respond("tool_use",
						toolUse("toolu_a", "get_class_info", "{\"class\": \"sample/Hello\"}"),
						toolUse("toolu_b", "get_class_info", "{\"class\": \"missing/Type\"}"),
						toolUse("toolu_c", "no_such_tool", "{}"))
				.respond("end_turn", text("Done."));
		assertEquals(AgentTurn.Outcome.COMPLETED, agent(ApprovalMode.ALLOW_CHANGES, null).send("Go").outcome());

		JsonArray results = api.requests.get(1).getAsJsonArray("messages").get(2).getAsJsonObject().getAsJsonArray("content");
		assertEquals(3, results.size());
		assertFalse(results.get(0).getAsJsonObject().has("is_error") && results.get(0).getAsJsonObject().get("is_error").getAsBoolean());
		assertTrue(results.get(1).getAsJsonObject().get("is_error").getAsBoolean());
		assertTrue(results.get(1).getAsJsonObject().get("content").getAsString().contains("No class named"));
		assertTrue(results.get(2).getAsJsonObject().get("content").getAsString().contains("Unknown tool"));
	}

	@Test
	void modifyingToolsFollowTheApprovalMode() {
		String comment = "{\"class\": \"sample.Hello\", \"comment\": \"entry\"}";
		// Declined by the user
		api.respond("tool_use", toolUse("toolu_1", "set_comment", comment)).respond("end_turn", text("ok"));
		List<String> asked = new ArrayList<>();
		ClaudeAgent declining = agent(ApprovalMode.ASK_BEFORE_CHANGES, (tool, args) -> {
			asked.add(tool.name() + ":" + args.string("comment"));
			return new ToolApprover.Decision(false, "not now");
		});
		declining.send("Comment it");
		assertEquals(List.of("set_comment:entry"), asked);
		String declined = toolResult(1);
		assertTrue(declined.contains("declined") && declined.contains("not now"), declined);
		assertTrue(call("get_comments", "class", "sample.Hello").startsWith("No comments"));

		// Approved by the user
		api.respond("tool_use", toolUse("toolu_2", "set_comment", comment)).respond("end_turn", text("ok"));
		agent(ApprovalMode.ASK_BEFORE_CHANGES, (tool, args) -> ToolApprover.Decision.ALLOW).send("Comment it");
		assertTrue(call("get_comments", "class", "sample.Hello").contains("entry"));

		// Read-only mode never asks. Reading still works, and so does taking notes: findings are not changes.
		String record = "{\"severity\": \"info\", \"title\": \"t\", \"detail\": \"d\"}";
		api.respond("tool_use", toolUse("toolu_3", "set_comment", comment.replace("entry", "changed")),
						toolUse("toolu_4", "record_finding", record), toolUse("toolu_5", "list_findings", "{}"))
				.respond("end_turn", text("ok"));
		agent(ApprovalMode.READ_ONLY, (tool, args) -> fail("Must not ask in read-only mode")).send("Comment it");
		JsonArray results = api.requests.get(5).getAsJsonArray("messages").get(2).getAsJsonObject().getAsJsonArray("content");
		assertTrue(results.get(0).getAsJsonObject().get("content").getAsString().contains("read-only"));
		assertTrue(results.get(1).getAsJsonObject().get("content").getAsString().contains("Recorded finding"));
		assertTrue(results.get(2).getAsJsonObject().get("content").getAsString().contains("1 finding"));
		assertFalse(call("get_comments", "class", "sample.Hello").contains("changed"));
		assertEquals(1, toolbox.context().state().findings().size());
	}

	@Test
	void listsTheModelsAKeyCanUse() throws Exception {
		List<software.coley.recaf.agent.chat.ModelInfo> models = software.coley.recaf.agent.claude.ClaudeModels.list("test-key", api.baseUrl());
		assertEquals(List.of("claude-opus-5-5", "claude-haiku-5-5"), models.stream().map(software.coley.recaf.agent.chat.ModelInfo::id).toList());
		assertEquals("Claude Opus 5.5", models.get(0).name());

		api.modelsStatus = 401;
		assertThrows(software.coley.recaf.agent.claude.ClaudeModels.InvalidKeyException.class,
				() -> software.coley.recaf.agent.claude.ClaudeModels.list("wrong-key", api.baseUrl()));
		// An address nothing listens on is a different failure than a key that is not accepted.
		java.io.IOException unreachable = assertThrows(java.io.IOException.class,
				() -> software.coley.recaf.agent.claude.ClaudeModels.list("test-key", "http://127.0.0.1:1"));
		assertFalse(unreachable instanceof software.coley.recaf.agent.claude.ClaudeModels.InvalidKeyException);
	}

	@Test
	void refusedResponsesAreNotActedOn() {
		api.respond("refusal", toolUse("toolu_1", "record_finding", "{\"severity\": \"info\", \"title\": \"t\", \"detail\": \"d\"}"))
				.respond("end_turn", text("Here is a different answer."));
		ClaudeAgent agent = agent(ApprovalMode.ALLOW_CHANGES, null);
		AgentTurn turn = agent.send("Analyze");
		assertEquals(AgentTurn.Outcome.REFUSED, turn.outcome());
		assertTrue(turn.detail().contains("cyber"), turn.detail());
		assertTrue(toolbox.context().state().findings().isEmpty(), "Tools of a declined response must not run");
		assertEquals(1, agent.messageCount(), "A declined response is not added to the conversation");

		// The conversation remains usable.
		assertEquals(AgentTurn.Outcome.COMPLETED, agent.send("Try differently").outcome());
		assertEquals(2, api.requests.get(1).getAsJsonArray("messages").size());
	}

	@Test
	void cutOffToolCallsAreReportedBackInsteadOfRun() {
		api.respond("max_tokens", toolUse("toolu_1", "record_finding", "{\"severity\": \"info\", \"title\": \"t\"}"))
				.respond("end_turn", text("Recovered."));
		ClaudeAgent agent = agent(ApprovalMode.ALLOW_CHANGES, null);
		assertEquals(AgentTurn.Outcome.COMPLETED, agent.send("Go").outcome());
		assertTrue(toolResult(1).contains("cut off"));
		assertTrue(toolbox.context().state().findings().isEmpty());

		api.respond("max_tokens", text("A very long answer that"));
		assertEquals(AgentTurn.Outcome.TRUNCATED, agent.send("More").outcome());
	}

	@Test
	void cancellingMidResponseKeepsTheConversationUsable() throws Exception {
		api.respondAndStall(text("This reply never finishes"), text("because the user stops it."));
		ClaudeAgent agent = agent(ApprovalMode.ALLOW_CHANGES, null);
		CompletableFuture<AgentTurn> running = CompletableFuture.supplyAsync(() -> agent.send("Start"));
		assertTrue(api.stalled.await(20, TimeUnit.SECONDS));
		assertTrue(agent.isRunning());
		agent.cancel();
		AgentTurn turn = running.get(20, TimeUnit.SECONDS);
		assertEquals(AgentTurn.Outcome.CANCELLED, turn.outcome());
		assertFalse(agent.isRunning());
		assertEquals(1, agent.messageCount(), "Nothing of the interrupted response is kept");

		api.respond("end_turn", text("Picking up again."));
		assertEquals(AgentTurn.Outcome.COMPLETED, agent.resume().outcome());
		assertEquals("Picking up again.", agent.lastReply().orElseThrow());
	}

	@Test
	void apiFailuresMapToOutcomesAndAllowRetry() {
		ClaudeAgent agent = agent(ApprovalMode.ALLOW_CHANGES, null);
		api.fail(401, "authentication_error", "invalid x-api-key");
		AgentTurn unauthorized = agent.send("Hello");
		assertEquals(AgentTurn.Outcome.AUTH_ERROR, unauthorized.outcome());
		assertTrue(unauthorized.detail().contains("invalid x-api-key"), unauthorized.detail());

		api.fail(400, "invalid_request_error", "model not found");
		assertEquals(AgentTurn.Outcome.INVALID_REQUEST, agent.resume().outcome());

		api.fail(429, "rate_limit_error", "slow down");
		AgentTurn busy = agent.resume();
		assertEquals(AgentTurn.Outcome.BUSY, busy.outcome());
		assertTrue(busy.outcome().canResume());

		api.respond("end_turn", text("Finally."));
		assertEquals(AgentTurn.Outcome.COMPLETED, agent.resume().outcome());
		assertEquals(1, api.requests.get(api.requests.size() - 1).getAsJsonArray("messages").size(), "Failed requests leave no trace");
	}

	@Test
	void conversationsSurviveExportAndImport() {
		api.respond("tool_use", thinking("Checking."), toolUse("toolu_1", "workspace_info", "{}"))
				.respond("end_turn", text("It has two classes."));
		ClaudeAgent original = agent(ApprovalMode.ALLOW_CHANGES, null);
		original.send("How many classes?");
		String exported = original.exportConversation();

		ClaudeAgent restored = agent(ApprovalMode.ALLOW_CHANGES, null);
		restored.importConversation(exported);
		assertEquals(original.messageCount(), restored.messageCount());
		api.respond("end_turn", text("Yes."));
		restored.send("Sure?");
		JsonArray replayed = api.requests.get(2).getAsJsonArray("messages");
		assertEquals(api.requests.get(1).getAsJsonArray("messages"), slice(replayed, 3), "The restored prefix is identical");
		assertThrows(IllegalArgumentException.class, () -> restored.importConversation("not json"));
	}

	private String toolResult(int requestIndex) {
		JsonArray messages = api.requests.get(requestIndex).getAsJsonArray("messages");
		return messages.get(messages.size() - 1).getAsJsonObject().getAsJsonArray("content").get(0).getAsJsonObject().get("content").getAsString();
	}

	private static JsonArray slice(JsonArray array, int length) {
		JsonArray result = new JsonArray();
		for (int i = 0; i < length; i++)
			result.add(array.get(i));
		return result;
	}
}
