package software.coley.recaf.agent.claude;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.errors.PermissionDeniedException;
import com.anthropic.errors.UnauthorizedException;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import software.coley.recaf.agent.chat.ModelInfo;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Asks the Claude API which models an API key can use. The request costs nothing, which also makes it the way
 * to find out whether a key works.
 *
 * @author Recaf agent tooling
 */
public final class ClaudeModels {
	private ClaudeModels() {}

	/**
	 * @param apiKey
	 * 		Anthropic API key.
	 * @param baseUrl
	 * 		API base URL, or {@code null} or empty for the default.
	 *
	 * @return Models the key can use, newest first, which is the order the API lists them in.
	 *
	 * @throws InvalidKeyException
	 * 		When the API did not accept the key.
	 * @throws IOException
	 * 		When the API could not be reached or answered with another error.
	 */
	@Nonnull
	public static List<ModelInfo> list(@Nonnull String apiKey, @Nullable String baseUrl) throws IOException {
		AnthropicOkHttpClient.Builder builder = AnthropicOkHttpClient.builder()
				.apiKey(apiKey)
				.timeout(Duration.ofSeconds(30))
				.maxRetries(1);
		if (baseUrl != null && !baseUrl.isBlank())
			builder.baseUrl(baseUrl);
		AnthropicClient client = builder.build();
		try {
			List<ModelInfo> models = new ArrayList<>();
			for (com.anthropic.models.models.ModelInfo model : client.models().list().autoPager())
				models.add(new ModelInfo(model.id(), model.displayName()));
			return models;
		} catch (UnauthorizedException | PermissionDeniedException ex) {
			throw new InvalidKeyException("The API key was not accepted: " + describe(ex));
		} catch (AnthropicServiceException ex) {
			throw new IOException("The API returned an error (HTTP " + ex.statusCode() + "): " + describe(ex));
		} catch (AnthropicIoException ex) {
			throw new IOException("Could not reach the API: " + describe(ex));
		} catch (RuntimeException ex) {
			throw new IOException("Could not list the models: " + describe(ex));
		} finally {
			client.close();
		}
	}

	@Nonnull
	private static String describe(@Nonnull Throwable t) {
		String message = t.getMessage();
		return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
	}

	/**
	 * The API did not accept the key.
	 */
	public static final class InvalidKeyException extends IOException {
		private InvalidKeyException(@Nonnull String message) {
			super(message);
		}
	}
}
