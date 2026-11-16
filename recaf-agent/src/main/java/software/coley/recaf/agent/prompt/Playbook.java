package software.coley.recaf.agent.prompt;

import jakarta.annotation.Nonnull;

/**
 * A ready-made analysis task: a prompt a user can start with one tap instead of typing it.
 *
 * @param id
 * 		Stable identifier, also the name of the MCP prompt.
 * @param title
 * 		Short title for menus.
 * @param description
 * 		One sentence on what the task produces.
 * @param prompt
 * 		The user message that starts the task.
 *
 * @author Recaf agent tooling
 */
public record Playbook(@Nonnull String id, @Nonnull String title, @Nonnull String description, @Nonnull String prompt) {}
