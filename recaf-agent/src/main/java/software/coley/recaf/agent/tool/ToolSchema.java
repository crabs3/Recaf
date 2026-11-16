package software.coley.recaf.agent.tool;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import jakarta.annotation.Nonnull;

/**
 * Builder for the JSON schema of a tool's arguments.
 * <p>
 * Schemas are deliberately flat: an object whose properties are strings, integers, booleans and string lists.
 * Flat schemas are the ones models fill in most reliably.
 *
 * @author Recaf agent tooling
 */
public final class ToolSchema {
	private final JsonObject properties = new JsonObject();
	private final JsonArray required = new JsonArray();

	private ToolSchema() {}

	/**
	 * @return New schema of an object without properties.
	 */
	@Nonnull
	public static ToolSchema object() {
		return new ToolSchema();
	}

	/**
	 * @param name
	 * 		Property name.
	 * @param description
	 * 		Property description, written for the model.
	 *
	 * @return Self, with a required string property added.
	 */
	@Nonnull
	public ToolSchema string(@Nonnull String name, @Nonnull String description) {
		return add(name, "string", description, true);
	}

	/**
	 * @param name
	 * 		Property name.
	 * @param description
	 * 		Property description, written for the model.
	 *
	 * @return Self, with an optional string property added.
	 */
	@Nonnull
	public ToolSchema optString(@Nonnull String name, @Nonnull String description) {
		return add(name, "string", description, false);
	}

	/**
	 * @param name
	 * 		Property name.
	 * @param description
	 * 		Property description, written for the model.
	 * @param values
	 * 		Allowed values.
	 *
	 * @return Self, with a required enumerated string property added.
	 */
	@Nonnull
	public ToolSchema choice(@Nonnull String name, @Nonnull String description, @Nonnull String... values) {
		return enumeration(name, description, true, values);
	}

	/**
	 * @param name
	 * 		Property name.
	 * @param description
	 * 		Property description, written for the model.
	 * @param values
	 * 		Allowed values.
	 *
	 * @return Self, with an optional enumerated string property added.
	 */
	@Nonnull
	public ToolSchema optChoice(@Nonnull String name, @Nonnull String description, @Nonnull String... values) {
		return enumeration(name, description, false, values);
	}

	/**
	 * @param name
	 * 		Property name.
	 * @param description
	 * 		Property description, written for the model.
	 *
	 * @return Self, with a required integer property added.
	 */
	@Nonnull
	public ToolSchema integer(@Nonnull String name, @Nonnull String description) {
		return add(name, "integer", description, true);
	}

	/**
	 * @param name
	 * 		Property name.
	 * @param description
	 * 		Property description, written for the model.
	 *
	 * @return Self, with an optional integer property added.
	 */
	@Nonnull
	public ToolSchema optInteger(@Nonnull String name, @Nonnull String description) {
		return add(name, "integer", description, false);
	}

	/**
	 * @param name
	 * 		Property name.
	 * @param description
	 * 		Property description, written for the model.
	 *
	 * @return Self, with an optional boolean property added.
	 */
	@Nonnull
	public ToolSchema optBool(@Nonnull String name, @Nonnull String description) {
		return add(name, "boolean", description, false);
	}

	/**
	 * @param name
	 * 		Property name.
	 * @param description
	 * 		Property description, written for the model.
	 *
	 * @return Self, with a required list-of-strings property added.
	 */
	@Nonnull
	public ToolSchema stringList(@Nonnull String name, @Nonnull String description) {
		return list(name, description, true);
	}

	/**
	 * @param name
	 * 		Property name.
	 * @param description
	 * 		Property description, written for the model.
	 *
	 * @return Self, with an optional list-of-strings property added.
	 */
	@Nonnull
	public ToolSchema optStringList(@Nonnull String name, @Nonnull String description) {
		return list(name, description, false);
	}

	/**
	 * Adds the standard paging properties {@code offset} and {@code limit}.
	 *
	 * @param defaultLimit
	 * 		Limit applied when the model does not pass one.
	 *
	 * @return Self.
	 */
	@Nonnull
	public ToolSchema paging(int defaultLimit) {
		optInteger("offset", "Number of results to skip, for paging. Default 0.");
		optInteger("limit", "Maximum number of results to return. Default " + defaultLimit + ".");
		return this;
	}

	@Nonnull
	private ToolSchema enumeration(@Nonnull String name, @Nonnull String description, boolean isRequired, @Nonnull String... values) {
		add(name, "string", description, isRequired);
		JsonArray options = new JsonArray();
		for (String value : values)
			options.add(value);
		properties.getAsJsonObject(name).add("enum", options);
		return this;
	}

	@Nonnull
	private ToolSchema list(@Nonnull String name, @Nonnull String description, boolean isRequired) {
		add(name, "array", description, isRequired);
		JsonObject items = new JsonObject();
		items.addProperty("type", "string");
		properties.getAsJsonObject(name).add("items", items);
		return this;
	}

	@Nonnull
	private ToolSchema add(@Nonnull String name, @Nonnull String type, @Nonnull String description, boolean isRequired) {
		if (properties.has(name))
			throw new IllegalStateException("Duplicate schema property: " + name);
		JsonObject property = new JsonObject();
		property.addProperty("type", type);
		property.addProperty("description", description);
		properties.add(name, property);
		if (isRequired)
			required.add(name);
		return this;
	}

	/**
	 * @return The JSON schema.
	 */
	@Nonnull
	public JsonObject toJson() {
		JsonObject schema = new JsonObject();
		schema.addProperty("type", "object");
		schema.add("properties", properties.deepCopy());
		schema.add("required", required.deepCopy());
		schema.addProperty("additionalProperties", false);
		return schema;
	}
}
