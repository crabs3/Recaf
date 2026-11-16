package software.coley.recaf.agent;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.coley.recaf.agent.mcp.McpHttpServer;
import software.coley.recaf.agent.mcp.McpServer;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the MCP protocol handler and its HTTP transport.
 */
class McpServerTest extends AgentTestBase {
	private static final String TOKEN = "test-token";
	private final HttpClient http = HttpClient.newHttpClient();
	private McpHttpServer httpServer;
	private String endpoint;

	@BeforeEach
	void startServer() throws Exception {
		httpServer = new McpHttpServer(new McpServer(toolbox), TOKEN);
		httpServer.start(0, true);
		endpoint = "http://127.0.0.1:" + httpServer.getPort() + McpHttpServer.ENDPOINT;
	}

	@AfterEach
	void stopServer() {
		httpServer.close();
		assertFalse(httpServer.isRunning());
	}

	@Test
	void initializeNegotiatesProtocolVersion() throws Exception {
		JsonObject result = rpc("initialize", "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}");
		assertEquals("2025-06-18", result.get("protocolVersion").getAsString());
		assertEquals("recaf", result.getAsJsonObject("serverInfo").get("name").getAsString());
		assertTrue(result.getAsJsonObject("capabilities").has("tools"));
		assertTrue(result.get("instructions").getAsString().contains("workspace_info"));
		// Unknown revisions are answered with the newest one the server speaks.
		JsonObject fallback = rpc("initialize", "{\"protocolVersion\":\"1999-01-01\"}");
		assertEquals(McpServer.SUPPORTED_PROTOCOL_VERSIONS.get(0), fallback.get("protocolVersion").getAsString());
	}

	@Test
	void listsAndCallsTools() throws Exception {
		JsonArray tools = rpc("tools/list", "{}").getAsJsonArray("tools");
		Set<String> names = new HashSet<>();
		tools.forEach(tool -> {
			JsonObject json = tool.getAsJsonObject();
			names.add(json.get("name").getAsString());
			assertEquals("object", json.getAsJsonObject("inputSchema").get("type").getAsString());
			assertTrue(json.getAsJsonObject("annotations").has("readOnlyHint"));
		});
		assertTrue(names.contains("workspace_info") && names.contains("decompile_class") && names.contains("run_script"), names.toString());

		openSample("hello.jar");
		JsonObject call = rpc("tools/call", "{\"name\":\"get_class_info\",\"arguments\":{\"class\":\"sample.Hello\"}}");
		assertFalse(call.get("isError").getAsBoolean());
		assertTrue(call.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString().contains("implements java/lang/Runnable"));

		// A failing tool is a result for the model to read, not a protocol error.
		JsonObject failed = rpc("tools/call", "{\"name\":\"get_class_info\",\"arguments\":{\"class\":\"nope\"}}");
		assertTrue(failed.get("isError").getAsBoolean());
	}

	@Test
	void protocolErrorsUseJsonRpcErrors() throws Exception {
		assertEquals(-32601, rpcRaw("no/such/method", "{}").getAsJsonObject("error").get("code").getAsInt());
		assertEquals(-32602, rpcRaw("tools/call", "{\"name\":\"no_such_tool\"}").getAsJsonObject("error").get("code").getAsInt());
		HttpResponse<String> garbage = post("{not json", TOKEN, null);
		assertEquals(200, garbage.statusCode());
		assertEquals(-32700, JsonParser.parseString(garbage.body()).getAsJsonObject().getAsJsonObject("error").get("code").getAsInt());
	}

	@Test
	void notificationsAreAcceptedWithoutBody() throws Exception {
		HttpResponse<String> response = post("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", TOKEN, null);
		assertEquals(202, response.statusCode());
		assertTrue(response.body().isEmpty());
		assertEquals("{}", rpc("ping", "{}").toString());
	}

	@Test
	void promptsExposeThePlaybooks() throws Exception {
		JsonArray prompts = rpc("prompts/list", "{}").getAsJsonArray("prompts");
		assertTrue(prompts.size() >= 4);
		JsonObject prompt = rpc("prompts/get", "{\"name\":\"analyze\"}");
		assertEquals("user", prompt.getAsJsonArray("messages").get(0).getAsJsonObject().get("role").getAsString());
		assertEquals(-32602, rpcRaw("prompts/get", "{\"name\":\"nope\"}").getAsJsonObject("error").get("code").getAsInt());
	}

	@Test
	void requiresTokenAndLoopbackOrigin() throws Exception {
		String ping = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";
		assertEquals(401, post(ping, null, null).statusCode());
		assertEquals(401, post(ping, "wrong", null).statusCode());
		assertEquals(403, post(ping, TOKEN, "https://evil.example").statusCode());
		assertEquals(200, post(ping, TOKEN, "http://localhost:6274").statusCode());
		// The liveness probe needs no token and reveals nothing.
		HttpResponse<String> health = http.send(HttpRequest.newBuilder(URI.create(endpoint.replace("/mcp", "/health"))).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(200, health.statusCode());
		assertEquals("ok", health.body());
		// The optional server-to-client stream is declined.
		HttpResponse<String> get = http.send(HttpRequest.newBuilder(URI.create(endpoint)).header("Authorization", "Bearer " + TOKEN).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(405, get.statusCode());
	}

	@Test
	void refusesNetworkExposureWithoutToken() {
		McpHttpServer open = new McpHttpServer(new McpServer(toolbox), null);
		assertThrows(IllegalStateException.class, () -> open.start(0, false));
		assertFalse(open.isRunning());
	}

	@Test
	void handlesChunkedBodiesAndKeepAlive() throws Exception {
		String body = "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ping\"}";
		try (Socket socket = new Socket("127.0.0.1", httpServer.getPort())) {
			socket.setSoTimeout(10_000);
			OutputStream out = socket.getOutputStream();
			InputStream in = socket.getInputStream();
			// First request: chunked transfer encoding, split into two chunks.
			String first = body.substring(0, 10), second = body.substring(10);
			out.write(("POST /mcp HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer " + TOKEN + "\r\nTransfer-Encoding: chunked\r\n\r\n" +
					Integer.toHexString(first.length()) + "\r\n" + first + "\r\n" +
					Integer.toHexString(second.length()) + "\r\n" + second + "\r\n0\r\n\r\n").getBytes(StandardCharsets.UTF_8));
			out.flush();
			assertTrue(readResponse(in).contains("\"id\":7"));
			// Second request on the same connection.
			out.write(("POST /mcp HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer " + TOKEN + "\r\nContent-Length: " + body.length() + "\r\n\r\n" + body)
					.getBytes(StandardCharsets.UTF_8));
			out.flush();
			assertTrue(readResponse(in).contains("\"id\":7"));
		}
	}

	private static String readResponse(InputStream in) throws Exception {
		StringBuilder head = new StringBuilder();
		while (!head.toString().endsWith("\r\n\r\n")) {
			int b = in.read();
			assertTrue(b >= 0, "Connection closed early: " + head);
			head.append((char) b);
		}
		assertTrue(head.toString().startsWith("HTTP/1.1 200"), head.toString());
		int length = Integer.parseInt(head.toString().lines().filter(l -> l.toLowerCase().startsWith("content-length:"))
				.findFirst().orElseThrow().substring(15).trim());
		return new String(in.readNBytes(length), StandardCharsets.UTF_8);
	}

	private JsonObject rpc(String method, String params) throws Exception {
		JsonObject response = rpcRaw(method, params);
		assertTrue(response.has("result"), "Expected a result: " + response);
		return response.getAsJsonObject("result");
	}

	private JsonObject rpcRaw(String method, String params) throws Exception {
		HttpResponse<String> response = post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":" + params + "}", TOKEN, null);
		assertEquals(200, response.statusCode(), response.body());
		assertTrue(response.headers().firstValue("content-type").orElse("").startsWith("application/json"));
		JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
		assertEquals("2.0", json.get("jsonrpc").getAsString());
		assertEquals(1, json.get("id").getAsInt());
		return json;
	}

	private HttpResponse<String> post(String body, String token, String origin) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(endpoint))
				.header("Content-Type", "application/json")
				.header("Accept", "application/json, text/event-stream")
				.POST(HttpRequest.BodyPublishers.ofString(body));
		if (token != null)
			request.header("Authorization", "Bearer " + token);
		if (origin != null)
			request.header("Origin", origin);
		return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}
}
