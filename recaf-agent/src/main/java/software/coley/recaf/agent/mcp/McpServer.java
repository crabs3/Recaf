package software.coley.recaf.agent.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import software.coley.recaf.RecafBuildConfig;
import software.coley.recaf.agent.prompt.Playbook;
import software.coley.recaf.agent.prompt.Playbooks;
import software.coley.recaf.agent.tool.AgentTool;
import software.coley.recaf.agent.tool.AgentToolbox;
import software.coley.recaf.agent.tool.ToolResult;
import software.coley.recaf.analytics.logging.Logging;

import java.util.List;

/**
 * Model Context Protocol server exposing an {@link AgentToolbox}.
 * <p>
 * This class is the protocol only: it maps one JSON-RPC message to at most one JSON-RPC response and knows nothing
 * about how messages travel. {@link McpHttpServer} puts it behind the protocol's Streamable HTTP transport.
 * <p>
 * The server is stateless. It offers:
 * <ul>
 *     <li><b>Tools</b>: every tool of the toolbox.</li>
 *     <li><b>Prompts</b>: the analysis {@link Playbooks}, which MCP clients surface as ready-made commands.</li>
 * </ul>
 *
 * @author Recaf agent tooling
 * @see <a href="https://modelcontextprotocol.io/specification">MCP specification</a>
 */
public final class McpServer {
	/** Protocol revisions this server can speak, newest first. */
	public static final List<String> SUPPORTED_PROTOCOL_VERSIONS = List.of("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05");
	public static final String SERVER_NAME = "recaf";
	private static final int PARSE_ERROR = -32700;
	private static final int INVALID_REQUEST = -32600;
	private static final int METHOD_NOT_FOUND = -32601;
	private static final int INVALID_PARAMS = -32602;
	private static final int INTERNAL_ERROR = -32603;
	private static final Logger logger = Logging.get(McpServer.class);
	private final AgentToolbox toolbox;

	/**
	 * @param toolbox
	 * 		Tools to expose.
	 */
	public McpServer(@Nonnull AgentToolbox toolbox) {
		this.toolbox = toolbox;
	}

	/**
	 * @param message
	 * 		A JSON-RPC message as text.
	 *
	 * @return The JSON-RPC response as text, or {@code null} when the message was a notification or a response,
	 * which are not answered.
	 */
	@Nullable
	public String handle(@Nonnull String message) {
		JsonElement parsed;
		try {
			parsed = JsonParser.parseString(message);
		} catch (JsonParseException ex) {
			return error(null, PARSE_ERROR, "Invalid JSON: " + ex.getMessage()).toString();
		}
		// Batching was removed from the protocol and none of the supported revisions require it.
		if (!parsed.isJsonObject())
			return error(null, INVALID_REQUEST, "Expected a single JSON-RPC message object").toString();
		JsonObject response = handle(parsed.getAsJsonObject());
		return response == null ? null : response.toString();
	}

	/**
	 * @param message
	 * 		A JSON-RPC message.
	 *
	 * @return The JSON-RPC response, or {@code null} when the message was a notification or a response.
	 */
	@Nullable
	public JsonObject handle(@Nonnull JsonObject message) {
		JsonElement id = message.get("id");
		boolean expectsResponse = id != null && !id.isJsonNull();
		JsonElement methodElement = message.get("method");
		if (methodElement == null || !methodElement.isJsonPrimitive()) {
			// A response to a request of ours. The server sends none, so there is nothing to correlate.
			return expectsResponse && !message.has("result") && !message.has("error") ?
					error(id, INVALID_REQUEST, "Missing 'method'") : null;
		}
		String method = methodElement.getAsString();
		if (!expectsResponse)
			return null; // Notifications such as 'notifications/initialized' need no action here.

		JsonObject params = message.has("params") && message.get("params").isJsonObject() ?
				message.getAsJsonObject("params") : new JsonObject();
		try {
			return switch (method) {
				case "initialize" -> result(id, initialize(params));
				case "ping" -> result(id, new JsonObject());
				case "tools/list" -> result(id, listTools());
				case "tools/call" -> callTool(id, params);
				case "prompts/list" -> result(id, listPrompts());
				case "prompts/get" -> getPrompt(id, params);
				default -> error(id, METHOD_NOT_FOUND, "Method not found: " + method);
			};
		} catch (RuntimeException ex) {
			logger.warn("MCP request '{}' failed", method, ex);
			return error(id, INTERNAL_ERROR, "Internal error: " + ex);
		}
	}

	@Nonnull
	private JsonObject initialize(@Nonnull JsonObject params) {
		// Speak the client's revision when we know it, otherwise offer our newest and let the client decide.
		String requested = params.has("protocolVersion") && params.get("protocolVersion").isJsonPrimitive() ?
				params.get("protocolVersion").getAsString() : null;
		String version = requested != null && SUPPORTED_PROTOCOL_VERSIONS.contains(requested) ?
				requested : SUPPORTED_PROTOCOL_VERSIONS.get(0);

		JsonObject capabilities = new JsonObject();
		JsonObject tools = new JsonObject();
		tools.addProperty("listChanged", false);
		capabilities.add("tools", tools);
		JsonObject prompts = new JsonObject();
		prompts.addProperty("listChanged", false);
		capabilities.add("prompts", prompts);

		JsonObject serverInfo = new JsonObject();
		serverInfo.addProperty("name", SERVER_NAME);
		serverInfo.addProperty("title", "Recaf");
		serverInfo.addProperty("version", RecafBuildConfig.VERSION);

		JsonObject result = new JsonObject();
		result.addProperty("protocolVersion", version);
		result.add("capabilities", capabilities);
		result.add("serverInfo", serverInfo);
		result.addProperty("instructions", Playbooks.TOOL_GUIDE);
		return result;
	}

	@Nonnull
	private JsonObject listTools() {
		JsonArray tools = new JsonArray();
		for (AgentTool tool : toolbox.tools()) {
			JsonObject json = new JsonObject();
			json.addProperty("name", tool.name());
			json.addProperty("description", tool.description());
			json.add("inputSchema", tool.inputSchema());
			JsonObject annotations = new JsonObject();
			annotations.addProperty("readOnlyHint", tool.readOnly());
			// Nothing here reaches beyond the Recaf workspace and its export directory.
			annotations.addProperty("openWorldHint", false);
			json.add("annotations", annotations);
			tools.add(json);
		}
		JsonObject result = new JsonObject();
		result.add("tools", tools);
		return result;
	}

	@Nonnull
	private JsonObject callTool(@Nonnull JsonElement id, @Nonnull JsonObject params) {
		if (!params.has("name") || !params.get("name").isJsonPrimitive())
			return error(id, INVALID_PARAMS, "Missing tool 'name'");
		String name = params.get("name").getAsString();
		if (toolbox.tool(name) == null)
			return error(id, INVALID_PARAMS, "Unknown tool: " + name);
		JsonObject arguments = params.has("arguments") && params.get("arguments").isJsonObject() ?
				params.getAsJsonObject("arguments") : new JsonObject();

		// Failures of the tool itself are results the model should see, not protocol errors.
		ToolResult toolResult = toolbox.call(name, arguments);
		JsonObject text = new JsonObject();
		text.addProperty("type", "text");
		text.addProperty("text", toolResult.text());
		JsonArray content = new JsonArray();
		content.add(text);
		JsonObject result = new JsonObject();
		result.add("content", content);
		result.addProperty("isError", toolResult.error());
		return result(id, result);
	}

	@Nonnull
	private JsonObject listPrompts() {
		JsonArray prompts = new JsonArray();
		for (Playbook playbook : Playbooks.all()) {
			JsonObject json = new JsonObject();
			json.addProperty("name", playbook.id());
			json.addProperty("title", playbook.title());
			json.addProperty("description", playbook.description());
			json.add("arguments", new JsonArray());
			prompts.add(json);
		}
		JsonObject result = new JsonObject();
		result.add("prompts", prompts);
		return result;
	}

	@Nonnull
	private JsonObject getPrompt(@Nonnull JsonElement id, @Nonnull JsonObject params) {
		String name = params.has("name") && params.get("name").isJsonPrimitive() ? params.get("name").getAsString() : null;
		Playbook playbook = name == null ? null : Playbooks.byId(name);
		if (playbook == null)
			return error(id, INVALID_PARAMS, "Unknown prompt: " + name);
		JsonObject content = new JsonObject();
		content.addProperty("type", "text");
		content.addProperty("text", playbook.prompt());
		JsonObject message = new JsonObject();
		message.addProperty("role", "user");
		message.add("content", content);
		JsonArray messages = new JsonArray();
		messages.add(message);
		JsonObject result = new JsonObject();
		result.addProperty("description", playbook.description());
		result.add("messages", messages);
		return result(id, result);
	}

	@Nonnull
	private static JsonObject result(@Nonnull JsonElement id, @Nonnull JsonObject result) {
		JsonObject response = new JsonObject();
		response.addProperty("jsonrpc", "2.0");
		response.add("id", id);
		response.add("result", result);
		return response;
	}

	@Nonnull
	private static JsonObject error(@Nullable JsonElement id, int code, @Nonnull String message) {
		JsonObject error = new JsonObject();
		error.addProperty("code", code);
		error.addProperty("message", message);
		JsonObject response = new JsonObject();
		response.addProperty("jsonrpc", "2.0");
		response.add("id", id == null ? com.google.gson.JsonNull.INSTANCE : id);
		response.add("error", error);
		return response;
	}
}
