package software.coley.recaf.agent.openai;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import software.coley.recaf.agent.chat.ApprovalMode;

/**
 * Settings of an {@link OpenAiAgent}.
 *
 * @param baseUrl
 * 		Address of the API, up to and including its version segment. For example {@code https://api.openai.com/v1}
 * 		or {@code http://localhost:11434/v1}. The agent requests {@code <baseUrl>/chat/completions}.
 * @param apiKey
 * 		Key sent as a bearer token, or {@code null} or empty for servers that do not ask for one.
 * @param model
 * 		Identifier of the model to run on.
 * @param approvalMode
 * 		Policy for tool calls that modify the workspace.
 *
 * @author Recaf agent tooling
 */
public record OpenAiConfig(@Nonnull String baseUrl, @Nullable String apiKey, @Nonnull String model,
                           @Nonnull ApprovalMode approvalMode) {
	public OpenAiConfig {
		baseUrl = normalizeBaseUrl(baseUrl);
	}

	/**
	 * Tidies an address a person typed or pasted: surrounding space and trailing slashes go, and so does the name
	 * of an endpoint when the whole request address was pasted instead of its base.
	 *
	 * @param baseUrl
	 * 		Address as entered.
	 *
	 * @return Address to append endpoint paths to. Empty when nothing was entered.
	 */
	@Nonnull
	public static String normalizeBaseUrl(@Nonnull String baseUrl) {
		String url = baseUrl.trim();
		if (url.isEmpty())
			return url;
		if (!url.contains("://"))
			url = (isLocal(url) ? "http://" : "https://") + url;
		while (url.endsWith("/"))
			url = url.substring(0, url.length() - 1);
		for (String endpoint : new String[]{"/chat/completions", "/completions", "/models"}) {
			if (url.endsWith(endpoint)) {
				url = url.substring(0, url.length() - endpoint.length());
				break;
			}
		}
		return url;
	}

	/**
	 * @param baseUrl
	 * 		Address of an API.
	 *
	 * @return {@code true} when requests to it are not encrypted.
	 */
	public static boolean isPlainHttp(@Nonnull String baseUrl) {
		return baseUrl.trim().regionMatches(true, 0, "http://", 0, 7);
	}

	/**
	 * @return {@code true} for an address without a scheme that looks like a machine on the local network,
	 * where model servers are served over plain HTTP.
	 */
	private static boolean isLocal(@Nonnull String hostAndPath) {
		String host = hostAndPath;
		int slash = host.indexOf('/');
		if (slash >= 0)
			host = host.substring(0, slash);
		int colon = host.lastIndexOf(':');
		if (colon >= 0)
			host = host.substring(0, colon);
		return host.equals("localhost") || host.endsWith(".local") || host.endsWith(".lan")
				|| host.matches("\\d{1,3}(\\.\\d{1,3}){3}") || !host.contains(".");
	}
}
