package software.coley.recaf.agent.openai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import software.coley.recaf.agent.chat.ModelInfo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Talks to an API that follows OpenAI's chat completions format. Besides OpenAI itself that is what most model
 * providers, gateways and local model servers offer, each with its own reading of the format. This client sticks
 * to the common core and is lenient about what comes back.
 * <p>
 * Requests go through OkHttp, which the Anthropic SDK brings along already and which behaves the same on a
 * desktop JVM and on Android. Unlike the JDK's own client it can break off a response that is being read.
 *
 * @author Recaf agent tooling
 */
public final class OpenAiClient {
	private static final int MAX_ERROR_CHARS = 600;
	private static final MediaType JSON = MediaType.get("application/json");
	/** Shared so that connections and threads are reused across clients. */
	private static final OkHttpClient HTTP = new OkHttpClient.Builder()
			.connectTimeout(20, TimeUnit.SECONDS)
			.writeTimeout(60, TimeUnit.SECONDS)
			.readTimeout(30, TimeUnit.SECONDS)
			.build();
	/** Local models can take minutes to load and to read a long prompt before the first token arrives. */
	private static final OkHttpClient STREAMING = HTTP.newBuilder()
			.readTimeout(10, TimeUnit.MINUTES)
			.build();
	private final String baseUrl;
	private final String apiKey;
	private final Object lock = new Object();
	private Call active;
	private volatile boolean aborted;

	/**
	 * @param baseUrl
	 * 		Address of the API, see {@link OpenAiConfig#baseUrl()}.
	 * @param apiKey
	 * 		Key sent as a bearer token, or {@code null} or empty for servers that do not ask for one.
	 */
	public OpenAiClient(@Nonnull String baseUrl, @Nullable String apiKey) {
		this.baseUrl = OpenAiConfig.normalizeBaseUrl(baseUrl);
		this.apiKey = apiKey == null ? "" : apiKey.trim();
	}

	/**
	 * Asks the API which models it offers.
	 * <p>
	 * People often enter the address of a server without the version segment its API lives under. When the
	 * address as given has no model list, the same address with {@code /v1} appended is tried, and the address
	 * that worked is returned along with the models.
	 *
	 * @return The models, sorted by identifier, and the address they were found at.
	 *
	 * @throws OpenAiException
	 * 		When the API answered with an error.
	 * @throws IOException
	 * 		When the API could not be reached, or answered with something that is not a model list.
	 */
	@Nonnull
	public ModelList listModels() throws IOException {
		if (baseUrl.isEmpty())
			throw new IOException("No API address is set.");
		try {
			return new ModelList(baseUrl, fetchModels(baseUrl));
		} catch (OpenAiException ex) {
			boolean versioned = baseUrl.matches(".*/v\\d+[a-z0-9]*(/.*)?");
			if ((ex.status() != 404 && ex.status() != 405) || versioned)
				throw ex;
			try {
				return new ModelList(baseUrl + "/v1", fetchModels(baseUrl + "/v1"));
			} catch (IOException ignored) {
				throw ex; // The first answer is the one that describes the address the user entered.
			}
		}
	}

	@Nonnull
	private List<ModelInfo> fetchModels(@Nonnull String base) throws IOException {
		Request request = request(base + "/models").header("Accept", "application/json").get().build();
		try (Response response = HTTP.newCall(request).execute()) {
			if (!response.isSuccessful())
				throw error(response);
			ResponseBody body = response.body();
			JsonElement json;
			try {
				json = JsonParser.parseString(body == null ? "" : body.string());
			} catch (JsonParseException ex) {
				throw new IOException("The address answered, but not with a model list. Is it the address of the API?");
			}
			return parseModels(json);
		}
	}

	/**
	 * @param json
	 * 		Response of a model list request.
	 *
	 * @return The models in it. Understands the usual shape, an object with the models under {@code data}, as well
	 * as a plain array and the {@code models} key that some servers use.
	 *
	 * @throws IOException
	 * 		When no models can be found in the response.
	 */
	@Nonnull
	static List<ModelInfo> parseModels(@Nonnull JsonElement json) throws IOException {
		JsonArray entries = null;
		if (json.isJsonArray()) {
			entries = json.getAsJsonArray();
		} else if (json.isJsonObject()) {
			JsonObject object = json.getAsJsonObject();
			for (String key : new String[]{"data", "models"}) {
				if (object.has(key) && object.get(key).isJsonArray()) {
					entries = object.getAsJsonArray(key);
					break;
				}
			}
		}
		if (entries == null)
			throw new IOException("The address answered, but not with a model list. Is it the address of the API?");

		Map<String, ModelInfo> models = new LinkedHashMap<>();
		for (JsonElement entry : entries) {
			String id = null;
			String name = null;
			if (entry.isJsonPrimitive()) {
				id = entry.getAsString();
			} else if (entry.isJsonObject()) {
				JsonObject model = entry.getAsJsonObject();
				for (String key : new String[]{"id", "model", "name"}) {
					id = string(model, key);
					if (id != null)
						break;
				}
				for (String key : new String[]{"display_name", "name"}) {
					name = string(model, key);
					if (name != null)
						break;
				}
			}
			if (id == null || id.isBlank())
				continue;
			// Some servers prefix identifiers with the collection they list, the chat endpoint wants them without.
			if (id.startsWith("models/"))
				id = id.substring("models/".length());
			models.putIfAbsent(id, new ModelInfo(id, name == null || name.isBlank() ? id : name));
		}
		List<ModelInfo> sorted = new ArrayList<>(models.values());
		sorted.sort(Comparator.comparing(model -> model.id().toLowerCase()));
		return sorted;
	}

	/**
	 * Requests a chat completion and reports it while it streams in.
	 *
	 * @param body
	 * 		Request body. {@code stream} is expected to be set, a server that answers in one piece anyway is
	 * 		understood as well.
	 * @param listener
	 * 		Receiver of the response as it arrives.
	 *
	 * @return The complete response.
	 *
	 * @throws OpenAiException
	 * 		When the API answered with an error.
	 * @throws IOException
	 * 		When the API could not be reached, the connection broke, or {@link #abort()} was called.
	 */
	@Nonnull
	public Completion chat(@Nonnull JsonObject body, @Nonnull StreamListener listener) throws IOException {
		if (baseUrl.isEmpty())
			throw new IOException("No API address is set.");
		aborted = false;
		Request request = request(baseUrl + "/chat/completions")
				.header("Accept", "text/event-stream, application/json")
				// Compression would let a proxy hold tokens back until a block is full.
				.header("Accept-Encoding", "identity")
				.post(RequestBody.create(body.toString().getBytes(StandardCharsets.UTF_8), JSON))
				.build();
		Call call = STREAMING.newCall(request);
		synchronized (lock) {
			active = call;
		}
		try (Response response = call.execute()) {
			if (!response.isSuccessful())
				throw error(response);
			ResponseBody responseBody = response.body();
			if (responseBody == null)
				throw new IOException("The API sent an empty response.");

			Accumulator accumulator = new Accumulator(listener);
			String contentType = response.header("Content-Type", "");
			if (contentType.toLowerCase().contains("json") && !contentType.toLowerCase().contains("event-stream")) {
				// The server ignored the request to stream and sent the whole answer at once.
				accumulator.acceptWhole(parse(responseBody.string()));
			} else {
				readEvents(responseBody.byteStream(), accumulator);
			}
			return accumulator.finish();
		} catch (IOException ex) {
			if (aborted)
				throw new IOException("The request was cancelled.");
			throw ex;
		} finally {
			synchronized (lock) {
				active = null;
			}
		}
	}

	/**
	 * Breaks off the request that is in progress, if any. Safe to call from any thread.
	 * The thread inside {@link #chat(JsonObject, StreamListener)} fails with an {@link IOException}.
	 */
	public void abort() {
		aborted = true;
		Call call;
		synchronized (lock) {
			call = active;
		}
		if (call != null)
			call.cancel();
	}

	/**
	 * Reads server-sent events and hands the data of each to the accumulator.
	 */
	private void readEvents(@Nonnull InputStream stream, @Nonnull Accumulator accumulator) throws IOException {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
			StringBuilder data = new StringBuilder();
			String line;
			while ((line = reader.readLine()) != null) {
				if (aborted)
					throw new IOException("The request was cancelled.");
				if (line.isEmpty()) {
					// A blank line ends an event.
					if (data.length() > 0 && !accumulator.acceptEvent(data.toString()))
						return;
					data.setLength(0);
				} else if (line.startsWith("data:")) {
					String value = line.substring(5);
					if (value.startsWith(" "))
						value = value.substring(1);
					if (data.length() > 0)
						data.append('\n');
					data.append(value);
				} else if (data.length() == 0 && line.startsWith("{")) {
					// Not an event stream after all: a JSON body, most likely an error, sent with the wrong type.
					StringBuilder whole = new StringBuilder(line);
					while ((line = reader.readLine()) != null)
						whole.append('\n').append(line);
					accumulator.acceptWhole(parse(whole.toString()));
					return;
				}
				// Other fields (event, id, retry) and comment lines carry nothing needed here.
			}
			if (data.length() > 0)
				accumulator.acceptEvent(data.toString());
		}
	}

	@Nonnull
	private Request.Builder request(@Nonnull String url) throws IOException {
		Request.Builder builder;
		try {
			builder = new Request.Builder().url(url);
		} catch (IllegalArgumentException ex) {
			throw new IOException("Not a valid API address: " + url);
		}
		builder.header("User-Agent", "recaf-agent");
		if (!apiKey.isEmpty())
			builder.header("Authorization", "Bearer " + apiKey);
		return builder;
	}

	/**
	 * @return The error an unsuccessful response describes.
	 */
	@Nonnull
	private static OpenAiException error(@Nonnull Response response) {
		String body = "";
		try {
			ResponseBody responseBody = response.body();
			if (responseBody != null)
				body = responseBody.string();
		} catch (IOException ignored) {
			// The status alone has to do.
		}
		long retryAfter = -1;
		String header = response.header("Retry-After");
		if (header != null) {
			try {
				retryAfter = (long) (Double.parseDouble(header.trim()) * 1000);
			} catch (NumberFormatException ignored) {
				// A date instead of a number of seconds. Not worth parsing, the default wait is used.
			}
		}
		String message = errorMessage(body);
		if (message == null)
			message = body.isBlank() ? "HTTP " + response.code() : shorten(body.strip());
		return new OpenAiException(response.code(), message, retryAfter);
	}

	/**
	 * @param body
	 * 		Body of a response.
	 *
	 * @return The message of the error it describes, or {@code null} when it is not an error in a known shape.
	 */
	@Nullable
	static String errorMessage(@Nonnull String body) {
		JsonElement json;
		try {
			json = JsonParser.parseString(body);
		} catch (JsonParseException ex) {
			return null;
		}
		return json.isJsonObject() ? errorMessage(json.getAsJsonObject()) : null;
	}

	@Nullable
	private static String errorMessage(@Nonnull JsonObject json) {
		JsonElement error = json.get("error");
		if (error != null && error.isJsonObject()) {
			String message = string(error.getAsJsonObject(), "message");
			if (message != null)
				return shorten(message);
			return shorten(error.toString());
		}
		if (error != null && error.isJsonPrimitive())
			return shorten(error.getAsString());
		for (String key : new String[]{"message", "detail"}) {
			JsonElement value = json.get(key);
			if (value != null && value.isJsonPrimitive())
				return shorten(value.getAsString());
			if (value != null && !value.isJsonNull())
				return shorten(value.toString());
		}
		return null;
	}

	@Nonnull
	private static JsonObject parse(@Nonnull String body) throws IOException {
		try {
			JsonElement json = JsonParser.parseString(body);
			if (json.isJsonObject())
				return json.getAsJsonObject();
		} catch (JsonParseException ignored) {
			// reported below
		}
		throw new IOException("The API sent a response that is not JSON: " + shorten(body.strip()));
	}

	@Nonnull
	private static String shorten(@Nonnull String text) {
		return text.length() <= MAX_ERROR_CHARS ? text : text.substring(0, MAX_ERROR_CHARS) + "…";
	}

	/**
	 * @return The string under a key, or {@code null} when there is none.
	 */
	@Nullable
	private static String string(@Nonnull JsonObject object, @Nonnull String key) {
		JsonElement value = object.get(key);
		return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
	}

	private static long number(@Nullable JsonObject object, @Nonnull String key) {
		if (object == null)
			return 0;
		JsonElement value = object.get(key);
		try {
			return value != null && value.isJsonPrimitive() ? value.getAsLong() : 0;
		} catch (NumberFormatException ex) {
			return 0;
		}
	}

	/**
	 * Models an API offers, and the address they were found at.
	 *
	 * @param baseUrl
	 * 		Address to use for requests. Differs from the one the client was created with when that one was
	 * 		missing its version segment.
	 * @param models
	 * 		The models, sorted by identifier.
	 */
	public record ModelList(@Nonnull String baseUrl, @Nonnull List<ModelInfo> models) {}

	/**
	 * A tool call requested by the model.
	 *
	 * @param id
	 * 		Identifier of the call, which the result must be sent back under.
	 * @param name
	 * 		Name of the tool.
	 * @param arguments
	 * 		Arguments as the model wrote them: JSON text that is not guaranteed to be valid.
	 */
	public record ToolCall(@Nonnull String id, @Nonnull String name, @Nonnull String arguments) {}

	/**
	 * A complete response.
	 *
	 * @param text
	 * 		The reply, without reasoning the model wrote into it.
	 * @param toolCalls
	 * 		Tool calls the model requested.
	 * @param finishReason
	 * 		Why the response ended: {@code stop}, {@code tool_calls}, {@code length}, {@code content_filter},
	 * 		or {@code null} when the API did not say.
	 * @param promptTokens
	 * 		Tokens of the request, as far as the API reported usage.
	 * @param completionTokens
	 * 		Tokens of the response.
	 * @param cachedTokens
	 * 		Part of the request tokens that was served from a cache.
	 */
	public record Completion(@Nonnull String text, @Nonnull List<ToolCall> toolCalls, @Nullable String finishReason,
	                         long promptTokens, long completionTokens, long cachedTokens) {}

	/**
	 * Receiver of a response while it streams in.
	 */
	public interface StreamListener {
		/**
		 * @param text
		 * 		Next piece of the reply.
		 */
		void onText(@Nonnull String text);

		/**
		 * @param text
		 * 		Next piece of the model's reasoning.
		 */
		void onReasoning(@Nonnull String text);

		/**
		 * @param id
		 * 		Identifier of a tool call the model began to write.
		 * @param name
		 * 		Name of the tool.
		 */
		void onToolCallStarted(@Nonnull String id, @Nonnull String name);
	}

	/**
	 * Assembles a response from the pieces it arrives in.
	 */
	private static final class Accumulator {
		private final StreamListener listener;
		private final StringBuilder text = new StringBuilder();
		private final List<PartialCall> calls = new ArrayList<>();
		private final ThinkTagSplitter splitter;
		private String finishReason;
		private long promptTokens, completionTokens, cachedTokens;
		private boolean anything;

		private Accumulator(@Nonnull StreamListener listener) {
			this.listener = listener;
			this.splitter = new ThinkTagSplitter(piece -> {
				text.append(piece);
				listener.onText(piece);
			}, listener::onReasoning);
		}

		/**
		 * @param data
		 * 		Data of one event.
		 *
		 * @return {@code false} when it was the marker that ends the stream.
		 */
		private boolean acceptEvent(@Nonnull String data) throws IOException {
			String trimmed = data.trim();
			if (trimmed.equals("[DONE]"))
				return false;
			if (trimmed.isEmpty())
				return true;
			JsonObject chunk;
			try {
				chunk = parse(trimmed);
			} catch (IOException ex) {
				// Keep-alive text and the like. A stream that carries nothing but such lines is reported at the end.
				return true;
			}
			accept(chunk, "delta");
			return true;
		}

		private void acceptWhole(@Nonnull JsonObject response) throws IOException {
			accept(response, "message");
		}

		private void accept(@Nonnull JsonObject chunk, @Nonnull String partKey) throws IOException {
			// Errors can arrive inside a stream that began with a success status.
			if (chunk.has("error") && !chunk.get("error").isJsonNull()) {
				String message = errorMessage(chunk);
				int status = 500;
				JsonElement error = chunk.get("error");
				if (error.isJsonObject()) {
					long code = number(error.getAsJsonObject(), "code");
					if (code >= 400 && code < 600)
						status = (int) code;
				}
				throw new OpenAiException(status, message == null ? "The API reported an error." : message, -1);
			}
			anything = true;

			JsonElement usage = chunk.get("usage");
			if (usage != null && usage.isJsonObject()) {
				JsonObject usageObject = usage.getAsJsonObject();
				promptTokens = number(usageObject, "prompt_tokens");
				completionTokens = number(usageObject, "completion_tokens");
				JsonElement details = usageObject.get("prompt_tokens_details");
				cachedTokens = details != null && details.isJsonObject() ? number(details.getAsJsonObject(), "cached_tokens") : 0;
			}

			JsonElement choices = chunk.get("choices");
			if (choices == null || !choices.isJsonArray() || choices.getAsJsonArray().isEmpty())
				return;
			JsonElement first = choices.getAsJsonArray().get(0);
			if (!first.isJsonObject())
				return;
			JsonObject choice = first.getAsJsonObject();
			String finish = string(choice, "finish_reason");
			if (finish != null && !finish.isEmpty())
				finishReason = finish;

			JsonElement partElement = choice.get(partKey);
			// Some servers send the complete message under 'message' even when streaming.
			if ((partElement == null || !partElement.isJsonObject()) && choice.has("message"))
				partElement = choice.get("message");
			if (partElement == null || !partElement.isJsonObject())
				return;
			JsonObject part = partElement.getAsJsonObject();

			for (String key : new String[]{"reasoning_content", "reasoning"}) {
				String reasoning = string(part, key);
				if (reasoning != null && !reasoning.isEmpty()) {
					listener.onReasoning(reasoning);
					break;
				}
			}

			JsonElement content = part.get("content");
			if (content != null && content.isJsonPrimitive()) {
				splitter.accept(content.getAsString());
			} else if (content != null && content.isJsonArray()) {
				// Content given as a list of typed parts.
				for (JsonElement piece : content.getAsJsonArray()) {
					if (piece.isJsonObject()) {
						String pieceText = string(piece.getAsJsonObject(), "text");
						if (pieceText != null)
							splitter.accept(pieceText);
					}
				}
			}

			JsonElement toolCalls = part.get("tool_calls");
			if (toolCalls != null && toolCalls.isJsonArray())
				for (JsonElement call : toolCalls.getAsJsonArray())
					if (call.isJsonObject())
						acceptToolCall(call.getAsJsonObject());
		}

		private void acceptToolCall(@Nonnull JsonObject fragment) {
			String id = string(fragment, "id");
			JsonElement indexElement = fragment.get("index");
			PartialCall call = null;
			if (indexElement != null && indexElement.isJsonPrimitive()) {
				int index;
				try {
					index = indexElement.getAsInt();
				} catch (NumberFormatException ex) {
					index = -1;
				}
				if (index >= 0 && index < 256) {
					while (calls.size() <= index)
						calls.add(new PartialCall());
					call = calls.get(index);
				}
			}
			if (call == null && id != null && !id.isEmpty()) {
				for (PartialCall existing : calls)
					if (id.equals(existing.id))
						call = existing;
			}
			if (call == null) {
				// No position given: servers that do this send each call complete, one fragment per call.
				call = new PartialCall();
				calls.add(call);
			}
			if (id != null && !id.isEmpty() && call.id == null)
				call.id = id;

			JsonElement functionElement = fragment.get("function");
			if (functionElement != null && functionElement.isJsonObject()) {
				JsonObject function = functionElement.getAsJsonObject();
				String name = string(function, "name");
				if (name != null && !name.isEmpty()) {
					if (call.name.length() == 0)
						call.name.append(name);
					else if (!call.announced && !call.name.toString().equals(name))
						call.name.append(name); // A name that arrives in pieces.
				}
				JsonElement arguments = function.get("arguments");
				if (arguments != null && arguments.isJsonPrimitive()) {
					call.arguments.append(arguments.getAsString());
				} else if (arguments != null && (arguments.isJsonObject() || arguments.isJsonArray())) {
					// Arguments given as JSON instead of as JSON text.
					call.arguments.setLength(0);
					call.arguments.append(arguments);
				}
				// The name is complete once arguments follow it.
				if (!call.announced && call.name.length() > 0 && (call.arguments.length() > 0 || name == null))
					announce(call);
			}
		}

		private void announce(@Nonnull PartialCall call) {
			if (call.id == null)
				call.id = "call_" + Long.toHexString(System.nanoTime()) + "_" + calls.indexOf(call);
			call.announced = true;
			listener.onToolCallStarted(call.id, call.name.toString());
		}

		@Nonnull
		private Completion finish() throws IOException {
			splitter.finish();
			if (!anything)
				throw new IOException("The API closed the connection without sending a response.");
			List<ToolCall> complete = new ArrayList<>();
			for (PartialCall call : calls) {
				if (call.name.length() == 0)
					continue;
				if (!call.announced)
					announce(call);
				complete.add(new ToolCall(call.id, call.name.toString(), call.arguments.toString()));
			}
			return new Completion(text.toString(), complete, finishReason, promptTokens, completionTokens, cachedTokens);
		}
	}

	private static final class PartialCall {
		private final StringBuilder name = new StringBuilder();
		private final StringBuilder arguments = new StringBuilder();
		private String id;
		private boolean announced;
	}
}
