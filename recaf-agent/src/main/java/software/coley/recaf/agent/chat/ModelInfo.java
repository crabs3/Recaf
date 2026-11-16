package software.coley.recaf.agent.chat;

import jakarta.annotation.Nonnull;

/**
 * A model that an API offers.
 *
 * @param id
 * 		Identifier to request the model with.
 * @param name
 * 		Name to show to people. The identifier when the API has nothing better.
 *
 * @author Recaf agent tooling
 */
public record ModelInfo(@Nonnull String id, @Nonnull String name) {}
