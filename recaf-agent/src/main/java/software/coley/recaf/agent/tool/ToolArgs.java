package software.coley.recaf.agent.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Typed, forgiving access to the arguments a model passed to a tool.
 * <p>
 * Models occasionally send numbers as strings or booleans as {@code "true"}. Rather than bouncing such calls,
 * the accessors here coerce where the intent is unambiguous and otherwise raise a {@link ToolException} that
 * names the argument.
 *
 * @author Recaf agent tooling
 */
public final class ToolArgs {
	private final JsonObject json;

	/**
	 * @param json
	 * 		Raw arguments.
	 */
	public ToolArgs(@Nullable JsonObject json) {
		this.json = json == null ? new JsonObject() : json;
	}

	/**
	 * @return Raw arguments.
	 */
	@Nonnull
	public JsonObject json() {
		return json;
	}

	/**
	 * @param name
	 * 		Argument name.
	 *
	 * @return {@code true} when the argument was passed with a non-null value.
	 */
	public boolean has(@Nonnull String name) {
		return json.has(name) && !json.get(name).isJsonNull();
	}

	/**
	 * @param name
	 * 		Argument name.
	 *
	 * @return Value of the required string argument.
	 */
	@Nonnull
	public String string(@Nonnull String name) {
		String value = optString(name, null);
		if (value == null || value.isEmpty())
			throw new ToolException("Missing required argument '" + name + "'");
		return value;
	}

	/**
	 * @param name
	 * 		Argument name.
	 * @param fallback
	 * 		Value when the argument was not passed.
	 *
	 * @return Value of the optional string argument.
	 */
	@Nullable
	public String optString(@Nonnull String name, @Nullable String fallback) {
		if (!has(name))
			return fallback;
		JsonElement element = json.get(name);
		if (element.isJsonPrimitive())
			return element.getAsString();
		throw new ToolException("Argument '" + name + "' must be a string");
	}

	/**
	 * @param name
	 * 		Argument name.
	 *
	 * @return Value of the required integer argument.
	 */
	public int integer(@Nonnull String name) {
		if (!has(name))
			throw new ToolException("Missing required argument '" + name + "'");
		return optInt(name, 0);
	}

	/**
	 * @param name
	 * 		Argument name.
	 * @param fallback
	 * 		Value when the argument was not passed.
	 *
	 * @return Value of the optional integer argument.
	 */
	public int optInt(@Nonnull String name, int fallback) {
		if (!has(name))
			return fallback;
		JsonElement element = json.get(name);
		if (element.isJsonPrimitive()) {
			JsonPrimitive primitive = element.getAsJsonPrimitive();
			try {
				if (primitive.isNumber())
					return primitive.getAsInt();
				return Integer.decode(primitive.getAsString().trim());
			} catch (NumberFormatException ignored) {
				// fall through
			}
		}
		throw new ToolException("Argument '" + name + "' must be an integer");
	}

	/**
	 * @param name
	 * 		Argument name.
	 * @param fallback
	 * 		Value when the argument was not passed.
	 *
	 * @return Value of the optional boolean argument.
	 */
	public boolean optBool(@Nonnull String name, boolean fallback) {
		if (!has(name))
			return fallback;
		JsonElement element = json.get(name);
		if (element.isJsonPrimitive()) {
			JsonPrimitive primitive = element.getAsJsonPrimitive();
			if (primitive.isBoolean())
				return primitive.getAsBoolean();
			String text = primitive.getAsString().trim();
			if (text.equalsIgnoreCase("true") || text.equals("1")) return true;
			if (text.equalsIgnoreCase("false") || text.equals("0")) return false;
		}
		throw new ToolException("Argument '" + name + "' must be true or false");
	}

	/**
	 * @param name
	 * 		Argument name.
	 *
	 * @return Values of the string list argument, empty when it was not passed.
	 * A single string is accepted as a list of one.
	 */
	@Nonnull
	public List<String> stringList(@Nonnull String name) {
		List<String> values = new ArrayList<>();
		if (!has(name))
			return values;
		JsonElement element = json.get(name);
		if (element.isJsonArray()) {
			JsonArray array = element.getAsJsonArray();
			for (JsonElement item : array) {
				if (!item.isJsonPrimitive())
					throw new ToolException("Argument '" + name + "' must be a list of strings");
				values.add(item.getAsString());
			}
		} else if (element.isJsonPrimitive()) {
			values.add(element.getAsString());
		} else {
			throw new ToolException("Argument '" + name + "' must be a list of strings");
		}
		return values;
	}

	/**
	 * @return Value of the standard {@code offset} paging argument, never negative.
	 */
	public int offset() {
		return Math.max(0, optInt("offset", 0));
	}

	/**
	 * @param defaultLimit
	 * 		Limit when the argument was not passed.
	 * @param maxLimit
	 * 		Upper bound of the limit.
	 *
	 * @return Value of the standard {@code limit} paging argument, clamped to {@code [1, maxLimit]}.
	 */
	public int limit(int defaultLimit, int maxLimit) {
		return Math.max(1, Math.min(maxLimit, optInt("limit", defaultLimit)));
	}

	@Override
	public String toString() {
		return json.toString();
	}
}
