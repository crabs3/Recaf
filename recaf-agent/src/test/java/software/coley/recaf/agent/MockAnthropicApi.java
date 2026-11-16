package software.coley.recaf.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * A stand-in for the Anthropic Messages API that answers each request with the next scripted response,
 * streamed as server-sent events in the API's wire format, and records what it was sent.
 * <p>
 * It lets the agent loop be tested through the real SDK without credentials or network access.
 */
final class MockAnthropicApi implements AutoCloseable {
	private final HttpServer server;
	private final ConcurrentLinkedQueue<Scripted> script = new ConcurrentLinkedQueue<>();
	final List<JsonObject> requests = new ArrayList<>();
	final List<String> betaHeaders = new ArrayList<>();
	final List<String> apiKeys = new ArrayList<>();
	/** Released when a stalling response should finish. */
	final CountDownLatch release = new CountDownLatch(1);
	/** Counted down when a stalling response has sent its first events. */
	final CountDownLatch stalled = new CountDownLatch(1);

	MockAnthropicApi() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/messages", this::handle);
		server.setExecutor(Executors.newCachedThreadPool());
		server.start();
	}

	String baseUrl() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	/** Queues a streamed response made of the given content blocks. */
	MockAnthropicApi respond(String stopReason, Block... blocks) {
		script.add(new Scripted(200, stopReason, List.of(blocks), false, null));
		return this;
	}

	/** Queues a response that sends its first block and then waits for {@link #release}. */
	MockAnthropicApi respondAndStall(Block... blocks) {
		script.add(new Scripted(200, "end_turn", List.of(blocks), true, null));
		return this;
	}

	/** Queues an error response. */
	MockAnthropicApi fail(int status, String type, String message) {
		script.add(new Scripted(status, null, List.of(), false,
				"{\"type\":\"error\",\"error\":{\"type\":\"" + type + "\",\"message\":\"" + message + "\"}}"));
		return this;
	}

	static Block text(String text) {
		return new Block("text", text, null, null);
	}

	static Block thinking(String summary) {
		return new Block("thinking", summary, null, null);
	}

	static Block toolUse(String id, String name, String inputJson) {
		return new Block("tool_use", inputJson, id, name);
	}

	private void handle(HttpExchange exchange) throws IOException {
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		synchronized (requests) {
			requests.add(JsonParser.parseString(body).getAsJsonObject());
			betaHeaders.add(String.join(",", exchange.getRequestHeaders().getOrDefault("anthropic-beta", List.of())));
			// How a key travels is the SDK's business: version 3.9 sends it as a bearer token, older ones as 'X-Api-Key'.
			String key = exchange.getRequestHeaders().getFirst("x-api-key");
			String authorization = exchange.getRequestHeaders().getFirst("authorization");
			if (key == null && authorization != null && authorization.startsWith("Bearer "))
				key = authorization.substring("Bearer ".length());
			apiKeys.add(key);
		}
		Scripted scripted = script.poll();
		if (scripted == null)
			scripted = new Scripted(500, null, List.of(), false,
					"{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"The test did not script this request\"}}");
		if (scripted.errorBody != null) {
			byte[] bytes = scripted.errorBody.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			// Tell the SDK not to retry, tests should not wait through its backoff.
			exchange.getResponseHeaders().add("x-should-retry", "false");
			exchange.sendResponseHeaders(scripted.status, bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
			return;
		}

		exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
		exchange.sendResponseHeaders(200, 0);
		try (OutputStream out = exchange.getResponseBody()) {
			event(out, "message_start", "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_test\",\"type\":\"message\"," +
					"\"role\":\"assistant\",\"model\":\"claude-opus-5-5\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null," +
					"\"usage\":{\"input_tokens\":100,\"output_tokens\":1,\"cache_read_input_tokens\":40,\"cache_creation_input_tokens\":10}}}");
			int index = 0;
			for (Block block : scripted.blocks) {
				switch (block.type) {
					case "text" -> {
						event(out, "content_block_start", "{\"type\":\"content_block_start\",\"index\":" + index + ",\"content_block\":{\"type\":\"text\",\"text\":\"\"}}");
						// Two deltas, to exercise accumulation.
						int half = block.payload.length() / 2;
						for (String part : new String[]{block.payload.substring(0, half), block.payload.substring(half)})
							event(out, "content_block_delta", "{\"type\":\"content_block_delta\",\"index\":" + index +
									",\"delta\":{\"type\":\"text_delta\",\"text\":" + quote(part) + "}}");
					}
					case "thinking" -> {
						event(out, "content_block_start", "{\"type\":\"content_block_start\",\"index\":" + index +
								",\"content_block\":{\"type\":\"thinking\",\"thinking\":\"\",\"signature\":\"\"}}");
						event(out, "content_block_delta", "{\"type\":\"content_block_delta\",\"index\":" + index +
								",\"delta\":{\"type\":\"thinking_delta\",\"thinking\":" + quote(block.payload) + "}}");
						event(out, "content_block_delta", "{\"type\":\"content_block_delta\",\"index\":" + index +
								",\"delta\":{\"type\":\"signature_delta\",\"signature\":\"c2lnbmF0dXJl\"}}");
					}
					case "tool_use" -> {
						event(out, "content_block_start", "{\"type\":\"content_block_start\",\"index\":" + index +
								",\"content_block\":{\"type\":\"tool_use\",\"id\":" + quote(block.id) + ",\"name\":" + quote(block.name) + ",\"input\":{}}}");
						int half = block.payload.length() / 2;
						for (String part : new String[]{block.payload.substring(0, half), block.payload.substring(half)})
							event(out, "content_block_delta", "{\"type\":\"content_block_delta\",\"index\":" + index +
									",\"delta\":{\"type\":\"input_json_delta\",\"partial_json\":" + quote(part) + "}}");
					}
					default -> throw new IllegalStateException(block.type);
				}
				if (scripted.stall && index == 0) {
					stalled.countDown();
					try {
						release.await(30, TimeUnit.SECONDS);
					} catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
					}
				}
				event(out, "content_block_stop", "{\"type\":\"content_block_stop\",\"index\":" + index + "}");
				index++;
			}
			String details = "refusal".equals(scripted.stopReason) ?
					",\"stop_details\":{\"type\":\"refusal\",\"category\":\"cyber\",\"explanation\":\"Declined for the test.\"}" : "";
			event(out, "message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":" + quote(scripted.stopReason) +
					",\"stop_sequence\":null" + details + "},\"usage\":{\"output_tokens\":25}}");
			event(out, "message_stop", "{\"type\":\"message_stop\"}");
		} catch (IOException ignored) {
			// The client went away, which the cancellation test does on purpose.
		}
	}

	private static void event(OutputStream out, String name, String data) throws IOException {
		out.write(("event: " + name + "\ndata: " + data + "\n\n").getBytes(StandardCharsets.UTF_8));
		out.flush();
	}

	private static String quote(String text) {
		return new com.google.gson.JsonPrimitive(text).toString();
	}

	@Override
	public void close() {
		release.countDown();
		server.stop(0);
	}

	record Block(String type, String payload, String id, String name) {}

	private record Scripted(int status, String stopReason, List<Block> blocks, boolean stall, String errorBody) {}
}
