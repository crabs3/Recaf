package software.coley.recaf.agent;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.coley.recaf.agent.cli.AgentCli;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static software.coley.recaf.agent.MockAnthropicApi.text;
import static software.coley.recaf.agent.MockAnthropicApi.toolUse;

/**
 * Tests for the command line entry point.
 */
class AgentCliTest extends AgentTestBase {
	private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
	private final PrintStream out = new PrintStream(captured, true, StandardCharsets.UTF_8);
	private MockAnthropicApi api;

	@BeforeEach
	void startApi() throws Exception {
		api = new MockAnthropicApi();
	}

	@AfterEach
	void stopApi() {
		api.close();
	}

	@Test
	void rejectsCommandLinesThatMakeNoSense() {
		assertTrue(assertThrows(IllegalArgumentException.class, () -> AgentCli.Options.parse(new String[]{"--mcp"})).getMessage().contains("No input"));
		assertTrue(assertThrows(IllegalArgumentException.class, () -> AgentCli.Options.parse(new String[]{"a.jar"})).getMessage().contains("Nothing to do"));
		assertTrue(assertThrows(IllegalArgumentException.class, () -> AgentCli.Options.parse(new String[]{"a.jar", "--bogus"})).getMessage().contains("Unknown option"));
		assertTrue(assertThrows(IllegalArgumentException.class, () -> AgentCli.Options.parse(new String[]{"a.jar", "--mcp", "--port", "x"})).getMessage().contains("port"));
		assertTrue(assertThrows(IllegalArgumentException.class, () -> AgentCli.Options.parse(new String[]{"a.jar", "b.jar", "--mcp"})).getMessage().contains("Only one input"));
		// '--analyze' takes an optional task name and must not swallow the next option.
		assertDoesNotThrow(() -> AgentCli.Options.parse(new String[]{"a.jar", "--analyze", "--model", "claude-sonnet-5-5"}));
		assertDoesNotThrow(() -> AgentCli.Options.parse(new String[]{"a.jar", "--analyze", "triage"}));
	}

	@Test
	void servesTheOpenedFileOverMcp() throws Exception {
		String input = sample("hello.jar").toString();
		try (AgentCli cli = new AgentCli(AgentCli.Options.parse(new String[]{input, "--mcp", "--port", "0", "--token", "cli-token"}), out)) {
			cli.start();
			assertTrue(cli.mcpPort() > 0);
			HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
									URI.create("http://127.0.0.1:" + cli.mcpPort() + "/mcp"))
							.header("Authorization", "Bearer cli-token")
							.POST(HttpRequest.BodyPublishers.ofString("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\"," +
									"\"params\":{\"name\":\"workspace_info\",\"arguments\":{}}}"))
							.build(),
					HttpResponse.BodyHandlers.ofString());
			assertTrue(response.body().contains("hello.jar"), response.body());
		}
		String printed = captured.toString(StandardCharsets.UTF_8);
		assertTrue(printed.contains("Opened hello.jar") && printed.contains("claude mcp add") && printed.contains("cli-token"), printed);
	}

	@Test
	void runsAnAnalysisAndPrintsTheReportAndFindings() throws Exception {
		api.respond("tool_use", toolUse("toolu_1", "record_finding", "{\"severity\": \"high\", \"title\": \"Hardcoded token\", \"detail\": \"In sample/Hello\"}"),
						toolUse("toolu_2", "delete_class", "{\"class\": \"sample.Hello\"}"))
				.respond("end_turn", text("The program prints a greeting."));
		String input = sample("hello.jar").toString();
		String[] arguments = {input, "--analyze", "--base-url", api.baseUrl()};

		// Without an API key there is nothing to run with, and that is said rather than failing obscurely.
		try (AgentCli cli = new AgentCli(AgentCli.Options.parse(arguments).withApiKey(null), out)) {
			cli.start();
			assertFalse(cli.runAgent());
			assertTrue(captured.toString(StandardCharsets.UTF_8).contains("ANTHROPIC_API_KEY"));
		}

		try (AgentCli cli = new AgentCli(AgentCli.Options.parse(arguments).withApiKey("test-key"), out)) {
			cli.start();
			assertTrue(cli.runAgent());
			// Read-only unless --allow-changes is passed: the class is still there.
			JsonObject lookup = new JsonObject();
			lookup.addProperty("class", "sample.Hello");
			assertFalse(cli.toolbox().call("get_class_info", lookup).error());
		}
		String printed = captured.toString(StandardCharsets.UTF_8);
		assertTrue(printed.contains("The program prints a greeting."), printed);
		assertTrue(printed.contains("Hardcoded token"), printed);
		assertTrue(api.requests.get(1).toString().contains("read-only"), api.requests.get(1).toString());
	}
}
