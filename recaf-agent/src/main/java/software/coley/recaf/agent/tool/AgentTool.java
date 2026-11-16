package software.coley.recaf.agent.tool;

import com.google.gson.JsonObject;
import jakarta.annotation.Nonnull;

/**
 * A capability of Recaf exposed to an AI agent.
 * <p>
 * The same tool definitions back both the embedded Claude agent and the MCP server, so a tool only describes
 * <i>what</i> it does: a name, a description and JSON schema written for a model to read, and a handler.
 *
 * @author Recaf agent tooling
 * @see AgentToolbox
 */
public interface AgentTool {
	/**
	 * @return Unique tool name in {@code snake_case}.
	 */
	@Nonnull
	String name();

	/**
	 * @return Description of what the tool does and when to use it, written for the model.
	 */
	@Nonnull
	String description();

	/**
	 * @return JSON schema of the tool's arguments. Always of type {@code object}.
	 */
	@Nonnull
	JsonObject inputSchema();

	/**
	 * @return {@code true} when the tool only reads from the workspace.
	 * {@code false} when it can modify the workspace or anything outside of it.
	 */
	boolean readOnly();

	/**
	 * @param args
	 * 		Arguments supplied by the model.
	 *
	 * @return Text result for the model.
	 *
	 * @throws ToolException
	 * 		To report a problem to the model.
	 * @throws Exception
	 * 		On unexpected failures, which are reported to the model as errors too.
	 */
	@Nonnull
	String execute(@Nonnull ToolArgs args) throws Exception;

	/**
	 * @param name
	 * 		Unique tool name in {@code snake_case}.
	 *
	 * @return Builder for a tool.
	 */
	@Nonnull
	static Builder builder(@Nonnull String name) {
		return new Builder(name);
	}

	/**
	 * Tool logic.
	 */
	@FunctionalInterface
	interface Handler {
		/**
		 * @param args
		 * 		Arguments supplied by the model.
		 *
		 * @return Text result for the model.
		 *
		 * @throws Exception
		 * 		See {@link AgentTool#execute(ToolArgs)}.
		 */
		@Nonnull
		String handle(@Nonnull ToolArgs args) throws Exception;
	}

	/**
	 * Builder for tools.
	 */
	final class Builder {
		private final String name;
		private String description;
		private ToolSchema schema = ToolSchema.object();
		private boolean readOnly = true;
		private Handler handler;

		private Builder(@Nonnull String name) {
			this.name = name;
		}

		/**
		 * @param description
		 * 		Description of what the tool does and when to use it, written for the model.
		 *
		 * @return Self.
		 */
		@Nonnull
		public Builder description(@Nonnull String description) {
			this.description = description;
			return this;
		}

		/**
		 * @param schema
		 * 		Schema of the tool's arguments.
		 *
		 * @return Self.
		 */
		@Nonnull
		public Builder schema(@Nonnull ToolSchema schema) {
			this.schema = schema;
			return this;
		}

		/**
		 * Marks the tool as one that changes the workspace or something outside of it.
		 *
		 * @return Self.
		 */
		@Nonnull
		public Builder mutating() {
			this.readOnly = false;
			return this;
		}

		/**
		 * @param handler
		 * 		Tool logic.
		 *
		 * @return Self.
		 */
		@Nonnull
		public Builder handler(@Nonnull Handler handler) {
			this.handler = handler;
			return this;
		}

		/**
		 * @return The tool.
		 */
		@Nonnull
		public AgentTool build() {
			if (description == null || handler == null)
				throw new IllegalStateException("Tool '" + name + "' needs a description and a handler");
			String description = this.description;
			JsonObject schemaJson = schema.toJson();
			boolean readOnly = this.readOnly;
			Handler handler = this.handler;
			return new AgentTool() {
				@Nonnull
				@Override
				public String name() {
					return name;
				}

				@Nonnull
				@Override
				public String description() {
					return description;
				}

				@Nonnull
				@Override
				public JsonObject inputSchema() {
					return schemaJson.deepCopy();
				}

				@Override
				public boolean readOnly() {
					return readOnly;
				}

				@Nonnull
				@Override
				public String execute(@Nonnull ToolArgs args) throws Exception {
					return handler.handle(args);
				}

				@Override
				public String toString() {
					return "AgentTool{" + name + '}';
				}
			};
		}
	}
}
