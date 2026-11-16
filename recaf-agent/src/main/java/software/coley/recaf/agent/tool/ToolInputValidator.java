package software.coley.recaf.agent.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Checks tool arguments against the flat schemas produced by {@link ToolSchema} before a tool runs.
 * <p>
 * Tool inputs are streamed from the model without server-side validation <i>(see the note on eager input
 * streaming in {@code ClaudeAgent})</i>, so an input can arrive cut off or malformed and still parse as JSON.
 * Running a tool on half of its arguments is worse than not running it, so structural problems are caught here
 * and reported back in a form the model can correct.
 *
 * @author Recaf agent tooling
 */
public final class ToolInputValidator {
	private ToolInputValidator() {}

	/**
	 * @param schema
	 * 		Schema of the tool's arguments.
	 * @param input
	 * 		Arguments supplied by the model.
	 *
	 * @return Description of what is wrong with the arguments, or {@code null} when they fit the schema.
	 */
	@Nullable
	public static String validate(@Nonnull JsonObject schema, @Nonnull JsonObject input) {
		JsonObject properties = schema.has("properties") ? schema.getAsJsonObject("properties") : new JsonObject();
		List<String> problems = new ArrayList<>();

		if (schema.has("required")) {
			for (JsonElement required : schema.getAsJsonArray("required")) {
				String name = required.getAsString();
				if (!input.has(name) || input.get(name).isJsonNull())
					problems.add("missing required argument '" + name + "'");
			}
		}
		for (Map.Entry<String, JsonElement> entry : input.entrySet()) {
			String name = entry.getKey();
			JsonElement value = entry.getValue();
			if (!properties.has(name)) {
				problems.add("unknown argument '" + name + "'");
				continue;
			}
			if (value.isJsonNull())
				continue;
			JsonObject property = properties.getAsJsonObject(name);
			String type = property.has("type") ? property.get("type").getAsString() : "string";
			if (!matches(type, value)) {
				problems.add("argument '" + name + "' must be of type " + type);
			} else if (property.has("enum") && value.isJsonPrimitive()) {
				JsonArray options = property.getAsJsonArray("enum");
				if (!options.contains(new JsonPrimitive(value.getAsString())))
					problems.add("argument '" + name + "' must be one of " + options);
			}
		}
		if (problems.isEmpty())
			return null;
		return String.join("; ", problems) + ". Accepted arguments: " + String.join(", ", properties.keySet()) + ".";
	}

	/**
	 * Lenient on purpose where intent is unambiguous: {@link ToolArgs} coerces numeric and boolean strings.
	 */
	private static boolean matches(@Nonnull String type, @Nonnull JsonElement value) {
		return switch (type) {
			case "string" -> value.isJsonPrimitive();
			case "integer", "number" -> value.isJsonPrimitive() && (value.getAsJsonPrimitive().isNumber() || isNumeric(value.getAsString()));
			case "boolean" -> value.isJsonPrimitive() && (value.getAsJsonPrimitive().isBoolean() || isBoolean(value.getAsString()));
			case "array" -> value.isJsonArray() || value.isJsonPrimitive();
			case "object" -> value.isJsonObject();
			default -> true;
		};
	}

	private static boolean isNumeric(@Nonnull String text) {
		try {
			Long.decode(text.trim());
			return true;
		} catch (NumberFormatException ex) {
			return false;
		}
	}

	private static boolean isBoolean(@Nonnull String text) {
		String trimmed = text.trim();
		return trimmed.equalsIgnoreCase("true") || trimmed.equalsIgnoreCase("false") || trimmed.equals("0") || trimmed.equals("1");
	}
}
