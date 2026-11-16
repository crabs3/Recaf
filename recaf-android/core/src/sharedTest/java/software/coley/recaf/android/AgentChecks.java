package software.coley.recaf.android;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import software.coley.recaf.agent.claude.AgentConfig;
import software.coley.recaf.agent.claude.AgentListener;
import software.coley.recaf.agent.claude.AgentTurn;
import software.coley.recaf.agent.claude.ClaudeAgent;
import software.coley.recaf.agent.mcp.McpHttpServer;
import software.coley.recaf.agent.mcp.McpServer;
import software.coley.recaf.agent.tool.AgentTool;
import software.coley.recaf.agent.tool.AgentToolbox;
import software.coley.recaf.agent.tool.ToolResult;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static software.coley.recaf.android.CoreChecks.check;

/**
 * Checks of the agent tooling on the Android build of the core, shared between the host JVM unit tests and the
 * on-device instrumented tests like {@link CoreChecks}.
 * <p>
 * On a device these answer the questions the desktop test suite of {@code recaf-agent} cannot: do the tools, the
 * JavaScript engine, the MCP server and the Anthropic SDK behave on Android's runtime.
 */
public final class AgentChecks {
	private static AgentToolbox toolbox;

	private AgentChecks() {}

	public static synchronized AgentToolbox toolbox(Path workDir) throws IOException {
		CoreChecks.setup(workDir);
		if (toolbox == null)
			toolbox = AgentToolbox.create(CoreChecks.recaf(), workDir.resolve("exports"),
					List.of(workDir.resolve("samples"), workDir.resolve("exports")));
		return toolbox;
	}

	/**
	 * Calls a representative tool of every group against both samples.
	 */
	public static void toolsWork(Path workDir) throws IOException {
		AgentToolbox toolbox = toolbox(workDir);
		check(toolbox.tools().size() >= 15, "Expected the standard tools, found " + toolbox.tools().size());

		CoreChecks.openSample("hello.jar");
		expect("workspace_info", "JVM classes: 2");
		expect("list_classes", "sample/util/Checks");
		expect("list_packages", "sample");
		expect("list_files", "config/app.properties");
		expect("read_file", "mode=debug", "path", "config/app.properties");
		expect("get_class_info", "implements java/lang/Runnable", "class", "sample.Hello");
		expect("decompile_class", "class Hello", "class", "sample/Hello");
		expect("decompile_method", "greet", "class", "sample/Hello", "method", "greet");
		expect("disassemble_method", "greet", "class", "sample/Hello", "method", "greet");
		expect("get_inheritance", "java/lang/Runnable", "class", "sample/Hello");
		expect("record_finding", "Recorded finding", "severity", "info", "title", "t", "detail", "d");
		expect("list_findings", "1 findings");

		// Search and program graph
		expect("search_strings", "s3cr3t-token", "query", "s3cr3t");
		expect("search_references", "sample/Hello", "owner", "sample/util/Checks", "name", "isAdmin");
		expect("search_declarations", "sample/util/Checks", "pattern", "magic", "kind", "method");
		expect("get_callees", "isAdmin", "class", "sample/Hello", "method", "run");
		expect("find_entry_points", "sample/Hello");
		expect("list_transformers", "transformers");

		// Editing: change a constant through the assembler, rename a class, export, and find all of it in the export.
		ToolResult listing = toolbox.call("disassemble_method", args("class", "sample/util/Checks", "method", "magic"));
		check(!listing.error() && listing.text().contains("1337"), "disassemble_method: " + listing.text());
		expect("assemble_method", "sample/util/Checks", "class", "sample/util/Checks", "source", listing.text().replace("1337", "4242"));
		expect("decompile_class", "4242", "class", "sample/util/Checks");
		expect("class_diff", "4242", "class", "sample/util/Checks");
		expect("list_modified", "sample/util/Checks");
		expect("rename_class", "sample/util/Validators", "class", "sample/util/Checks", "new_name", "sample/util/Validators");
		expect("decompile_class", "Validators.isAdmin", "class", "sample/Hello");
		expect("put_file", "notes/readme.txt", "path", "notes/readme.txt", "text", "patched on device");
		expect("export_workspace", "patched.jar", "file_name", "patched.jar", "overwrite", "true");
		expect("open_workspace", "2 JVM classes", "path", workDir.resolve("exports").resolve("patched.jar").toString(), "discard_changes", "true");
		expect("get_class_info", "magic()I", "class", "sample/util/Validators");
		expect("read_file", "patched on device", "path", "notes/readme.txt");

		CoreChecks.openSample("sample.apk");
		expect("workspace_info", "Android (DEX) classes: 2");
		expect("search_strings", "https://example.invalid/collect", "query", "example.invalid");
		expect("get_android_manifest", "android.permission.READ_CONTACTS");
		expect("decompile_class", "https://example.invalid/collect", "class", "sample.app.MainActivity");
		expect("disassemble_class", "sample/app/Keys", "class", "sample/app/Keys");

		// Every tool must at least reject an empty call cleanly rather than blow up.
		for (AgentTool tool : toolbox.tools()) {
			if (!tool.readOnly())
				continue;
			ToolResult result = toolbox.call(tool.name(), new JsonObject());
			check(!result.text().contains("failed unexpectedly"), tool.name() + " -> " + result.text());
		}
	}

	/**
	 * Runs scripts, which exercises the JavaScript engine in interpreted mode and its bridge to Java.
	 */
	public static void scriptsRun(Path workDir) throws IOException {
		toolbox(workDir);
		CoreChecks.openSample("hello.jar");
		expect("run_script", "=> 2", "code", "recaf.classNames().size()");
		String rewrite = """
				var asm = Packages.org.objectweb.asm;
				var node = new asm.tree.ClassNode();
				new asm.ClassReader(recaf.classBytes("sample/Hello")).accept(node, 0);
				for (var m = 0; m < node.methods.size(); m++) {
				  var insns = node.methods.get(m).instructions;
				  for (var insn = insns.getFirst(); insn != null; insn = insn.getNext())
				    if (insn instanceof asm.tree.LdcInsnNode && "Hello " == insn.cst) insn.cst = "Goodbye ";
				}
				var writer = new asm.ClassWriter(0);
				node.accept(writer);
				print("wrote " + recaf.putClass(writer.toByteArray()));
				""";
		expect("run_script", "wrote sample/Hello", "code", rewrite);
		expect("decompile_class", "Goodbye ", "class", "sample/Hello");
		ToolResult escaped = toolbox.call("run_script", args("code", "java.lang.Runtime.getRuntime().exec('id')"));
		check(escaped.error() || escaped.text().contains("[JavaPackage "), "The sandbox let a script reach Runtime: " + escaped.text());
		ToolResult runaway = toolbox.call("run_script", args("code", "while (true) {}", "timeout_seconds", "1"));
		check(runaway.error() && runaway.text().contains("time limit"), "Runaway script was not stopped: " + runaway.text());
	}

	/**
	 * Talks to the MCP server over HTTP on the loopback interface.
	 */
	public static void mcpServerAnswers(Path workDir) throws IOException {
		AgentToolbox toolbox = toolbox(workDir);
		CoreChecks.openSample("hello.jar");
		try (McpHttpServer server = new McpHttpServer(new McpServer(toolbox), "device-token")) {
			server.start(0, true);
			String endpoint = "http://127.0.0.1:" + server.getPort() + McpHttpServer.ENDPOINT;
			check(post(endpoint, null, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}").status == 401, "A token must be required");

			Response init = post(endpoint, "device-token",
					"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-06-18\"}}");
			check(init.status == 200 && init.body.contains("\"serverInfo\""), "initialize: " + init);
			Response tools = post(endpoint, "device-token", "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
			check(tools.body.contains("\"decompile_class\""), "tools/list: " + tools);
			Response call = post(endpoint, "device-token", "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\"," +
					"\"params\":{\"name\":\"get_class_info\",\"arguments\":{\"class\":\"sample.util.Checks\"}}}");
			JsonObject result = JsonParser.parseString(call.body).getAsJsonObject().getAsJsonObject("result");
			check(!result.get("isError").getAsBoolean(), "tools/call: " + call);
			check(result.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString().contains("isAdmin"), "tools/call: " + call);
		}
	}

	/**
	 * Runs the Claude agent through the real Anthropic SDK against a scripted server speaking the API's streaming
	 * wire format: a response that calls a tool, then a response with the final answer.
	 */
	public static void agentLoopRuns(Path workDir) throws Exception {
		AgentToolbox toolbox = toolbox(workDir);
		CoreChecks.openSample("hello.jar");
		List<String> requests = new ArrayList<>();
		try (ServerSocket socket = new ServerSocket(0, 8, InetAddress.getLoopbackAddress())) {
			Thread server = new Thread(() -> {
				String[] responses = {
						sse("tool_use", "{\"type\":\"tool_use\",\"id\":\"toolu_1\",\"name\":\"get_class_info\",\"input\":{}}",
								"{\"type\":\"input_json_delta\",\"partial_json\":\"{\\\"class\\\": \\\"sample.Hello\\\"}\"}"),
						sse("end_turn", "{\"type\":\"text\",\"text\":\"\"}",
								"{\"type\":\"text_delta\",\"text\":\"It implements Runnable.\"}"),
				};
				for (String response : responses) {
					try (Socket connection = socket.accept()) {
						requests.add(readRequestBody(connection.getInputStream()));
						OutputStream out = connection.getOutputStream();
						out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\n" + response)
								.getBytes(StandardCharsets.UTF_8));
						out.flush();
					} catch (IOException ex) {
						requests.add("server failed: " + ex);
					}
				}
			}, "mock-anthropic-api");
			server.setDaemon(true);
			server.start();

			List<String> events = new ArrayList<>();
			AgentConfig config = new AgentConfig("device-key", "http://127.0.0.1:" + socket.getLocalPort(), "claude-opus-5-5",
					"medium", 64_000, true, AgentConfig.ApprovalMode.ALLOW_CHANGES);
			ClaudeAgent agent = new ClaudeAgent(toolbox, config, new AgentListener() {
				@Override
				public void onText(String text) {
					events.add("text:" + text);
				}

				@Override
				public void onToolCallFinished(String callId, String result, boolean error, long durationMillis) {
					events.add("tool:" + (error ? "error:" : "ok:") + result);
				}
			}, null);

			AgentTurn turn = agent.send("What does Hello implement?");
			check(turn.outcome() == AgentTurn.Outcome.COMPLETED, "Agent run ended with " + turn.outcome() + ": " + turn.detail());
			check(requests.size() == 2, "Expected two requests, got " + requests);
			check(events.size() == 2 && events.get(0).startsWith("tool:ok:") && events.get(0).contains("implements java/lang/Runnable"),
					"Unexpected events: " + events);
			check("text:It implements Runnable.".equals(events.get(1)), "Unexpected events: " + events);
			JsonObject second = JsonParser.parseString(requests.get(1)).getAsJsonObject();
			check(second.getAsJsonArray("messages").size() == 3, "The tool result was not sent back: " + requests.get(1));
			check(second.getAsJsonArray("tools").size() == toolbox.tools().size(), "Tools were not declared");
			check("It implements Runnable.".equals(agent.lastReply().orElse(null)), "Unexpected reply: " + agent.lastReply());
		}
	}

	private static void expect(String tool, String expected, String... keyValues) {
		ToolResult result = toolbox.call(tool, args(keyValues));
		check(!result.error(), tool + " failed: " + result.text());
		check(result.text().contains(expected), tool + " did not mention '" + expected + "': " + result.text());
	}

	private static JsonObject args(String... keyValues) {
		JsonObject json = new JsonObject();
		for (int i = 0; i < keyValues.length; i += 2)
			json.addProperty(keyValues[i], keyValues[i + 1]);
		return json;
	}

	private static String sse(String stopReason, String blockStart, String delta) {
		return event("message_start", "{\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\"," +
				"\"model\":\"claude-opus-5-5\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null," +
				"\"usage\":{\"input_tokens\":10,\"output_tokens\":1}}}") +
				event("content_block_start", "{\"type\":\"content_block_start\",\"index\":0,\"content_block\":" + blockStart + "}") +
				event("content_block_delta", "{\"type\":\"content_block_delta\",\"index\":0,\"delta\":" + delta + "}") +
				event("content_block_stop", "{\"type\":\"content_block_stop\",\"index\":0}") +
				event("message_delta", "{\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"" + stopReason +
						"\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":5}}") +
				event("message_stop", "{\"type\":\"message_stop\"}");
	}

	private static String event(String name, String data) {
		return "event: " + name + "\ndata: " + data + "\n\n";
	}

	/** Reads one HTTP request and returns its body. Handles both a content length and chunked transfer encoding. */
	private static String readRequestBody(InputStream in) throws IOException {
		int contentLength = -1;
		boolean chunked = false;
		String line;
		while (!(line = readLine(in)).isEmpty()) {
			String lower = line.toLowerCase();
			if (lower.startsWith("content-length:"))
				contentLength = Integer.parseInt(line.substring(15).trim());
			else if (lower.startsWith("transfer-encoding:") && lower.contains("chunked"))
				chunked = true;
		}
		ByteArrayOutputStream body = new ByteArrayOutputStream();
		if (chunked) {
			while (true) {
				int size = Integer.parseInt(readLine(in).trim(), 16);
				if (size == 0)
					break;
				body.write(readExactly(in, size));
				readLine(in);
			}
		} else if (contentLength > 0) {
			body.write(readExactly(in, contentLength));
		}
		return body.toString(StandardCharsets.UTF_8);
	}

	private static byte[] readExactly(InputStream in, int length) throws IOException {
		byte[] data = new byte[length];
		int offset = 0;
		while (offset < length) {
			int read = in.read(data, offset, length - offset);
			if (read < 0)
				throw new IOException("Request ended early");
			offset += read;
		}
		return data;
	}

	private static String readLine(InputStream in) throws IOException {
		StringBuilder sb = new StringBuilder();
		int b;
		while ((b = in.read()) >= 0 && b != '\n')
			if (b != '\r')
				sb.append((char) b);
		return sb.toString();
	}

	private static Response post(String endpoint, String token, String body) throws IOException {
		HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
		connection.setRequestMethod("POST");
		connection.setDoOutput(true);
		connection.setRequestProperty("Content-Type", "application/json");
		connection.setRequestProperty("Accept", "application/json, text/event-stream");
		if (token != null)
			connection.setRequestProperty("Authorization", "Bearer " + token);
		try (OutputStream out = connection.getOutputStream()) {
			out.write(body.getBytes(StandardCharsets.UTF_8));
		}
		int status = connection.getResponseCode();
		InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
		String text = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
		connection.disconnect();
		return new Response(status, text);
	}

	private record Response(int status, String body) {}
}
