package software.coley.recaf.agent;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Stand-in for an OpenAI-compatible API that plays back scripted responses, so that the agent is exercised
 * through real HTTP and real stream parsing.
 */
final class MockOpenAiApi implements AutoCloseable {
	final List<JsonObject> requests = new ArrayList<>();
	final List<String> paths = new ArrayList<>();
	final List<String> authorizations = new ArrayList<>();
	final CountDownLatch stalled = new CountDownLatch(1);
	private final CountDownLatch release = new CountDownLatch(1);
	private final ConcurrentLinkedQueue<Scripted> script = new ConcurrentLinkedQueue<>();
	private final HttpServer server;
	/** Path the model list is served under, others answer 404. */
	String modelsPath = "/v1/models";
	String modelsBody = "{\"object\":\"list\",\"data\":[{\"id\":\"zeta-small\"},{\"id\":\"alpha-large\",\"owned_by\":\"test\"}]}";
	int modelsStatus = 200;

	MockOpenAiApi() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/", this::handle);
		// One thread per request: a response that is made to stall must not hold up the requests after it.
		server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
		server.start();
	}

	/** @return Address of the server without a version segment. */
	String root() {
		return "http://127.0.0.1:" + server.getAddress().getPort();
	}

	/** @return Address of the API. */
	String baseUrl() {
		return root() + "/v1";
	}

	/** Scripts a streamed response made of the given event payloads. */
	MockOpenAiApi stream(String... chunks) {
		script.add(new Scripted(200, List.of(chunks), null, false, null));
		return this;
	}

	/** Scripts a streamed response that stops after its first event until the server is closed. */
	MockOpenAiApi stall(String... chunks) {
		script.add(new Scripted(200, List.of(chunks), null, true, null));
		return this;
	}

	/** Scripts a response sent in one piece, as servers that do not stream answer. */
	MockOpenAiApi whole(String json) {
		script.add(new Scripted(200, null, json, false, null));
		return this;
	}

	/** Scripts an error response. */
	MockOpenAiApi fail(int status, String body, String retryAfter) {
		script.add(new Scripted(status, null, body, false, retryAfter));
		return this;
	}

	private void handle(HttpExchange exchange) throws IOException {
		String path = exchange.getRequestURI().getPath();
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		synchronized (requests) {
			paths.add(path);
			authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
		}
		if (path.endsWith("/models")) {
			boolean known = path.equals(modelsPath);
			respond(exchange, known ? modelsStatus : 404, known ? modelsBody : "{\"error\":{\"message\":\"Not found\"}}", null);
			return;
		}
		if (!path.endsWith("/chat/completions")) {
			respond(exchange, 404, "{\"error\":{\"message\":\"Not found\"}}", null);
			return;
		}
		synchronized (requests) {
			requests.add(JsonParser.parseString(body).getAsJsonObject());
		}
		Scripted scripted = script.poll();
		if (scripted == null)
			scripted = new Scripted(500, null, "{\"error\":{\"message\":\"The test did not script this request\"}}", false, null);
		if (scripted.chunks == null) {
			respond(exchange, scripted.status, scripted.body, scripted.retryAfter);
			return;
		}

		exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
		exchange.sendResponseHeaders(200, 0);
		try (OutputStream out = exchange.getResponseBody()) {
			boolean first = true;
			for (String chunk : scripted.chunks) {
				out.write(("data: " + chunk + "\n\n").getBytes(StandardCharsets.UTF_8));
				out.flush();
				if (scripted.stall && first) {
					stalled.countDown();
					try {
						release.await(30, TimeUnit.SECONDS);
					} catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
					}
				}
				first = false;
			}
			out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
		} catch (IOException ignored) {
			// The client went away, which the cancellation test does on purpose.
		}
	}

	private static void respond(HttpExchange exchange, int status, String body, String retryAfter) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json");
		if (retryAfter != null)
			exchange.getResponseHeaders().add("Retry-After", retryAfter);
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	// --- Event payloads in the chat completions stream format

	static String text(String text) {
		return "{\"choices\":[{\"index\":0,\"delta\":{\"content\":" + quote(text) + "}}]}";
	}

	static String reasoning(String text) {
		return "{\"choices\":[{\"index\":0,\"delta\":{\"reasoning_content\":" + quote(text) + "}}]}";
	}

	/** First piece of a tool call: its position, identifier and name. */
	static String toolStart(int index, String id, String name) {
		return "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":" + index + ",\"id\":" + quote(id) +
				",\"type\":\"function\",\"function\":{\"name\":" + quote(name) + ",\"arguments\":\"\"}}]}}]}";
	}

	/** A further piece of the arguments of a tool call. */
	static String toolArguments(int index, String fragment) {
		return "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":" + index +
				",\"function\":{\"arguments\":" + quote(fragment) + "}}]}}]}";
	}

	static String finish(String reason) {
		return "{\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":" + quote(reason) + "}]}";
	}

	static String usage(int prompt, int completion, int cached) {
		return "{\"choices\":[],\"usage\":{\"prompt_tokens\":" + prompt + ",\"completion_tokens\":" + completion +
				",\"prompt_tokens_details\":{\"cached_tokens\":" + cached + "}}}";
	}

	private static String quote(String text) {
		return new com.google.gson.JsonPrimitive(text).toString();
	}

	@Override
	public void close() {
		release.countDown();
		server.stop(0);
	}

	private record Scripted(int status, List<String> chunks, String body, boolean stall, String retryAfter) {}
}
