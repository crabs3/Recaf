package software.coley.recaf.android.app

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import software.coley.recaf.agent.chat.ApprovalMode
import software.coley.recaf.agent.chat.ModelInfo
import software.coley.recaf.agent.claude.AgentConfig
import software.coley.recaf.agent.claude.ClaudeModel
import software.coley.recaf.agent.mcp.McpHttpServer
import software.coley.recaf.agent.openai.OpenAiConfig
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * User settings.
 *
 * @property apiKey Anthropic API key, empty when not set.
 * @property model Identifier of the model the agent runs on.
 * @property effort How thoroughly the agent works.
 * @property approvalMode Policy for tool calls that modify the workspace.
 * @property refusalFallback Whether a declined request may be retried on another model.
 * @property provider Which kind of API the agent talks to.
 * @property baseUrl Address of the API to talk to instead of Anthropic's own, empty for the default.
 * @property claudeModels Models the Anthropic API key can use, as last fetched.
 * @property openAiBaseUrl Address of the OpenAI-compatible API.
 * @property openAiApiKey Key for the OpenAI-compatible API, empty for servers that ask for none.
 * @property openAiModel Model to use with the OpenAI-compatible API.
 * @property openAiModels Models the OpenAI-compatible API offers, as last fetched.
 * @property onboarded Whether the first-run setup was completed or skipped.
 * @property mcpPort Port of the MCP server.
 * @property mcpToken Access token of the MCP server.
 * @property mcpNetwork Whether the MCP server accepts connections from other devices.
 */
/** Which kind of API the agent talks to. */
enum class Provider {
	/** Claude, through Anthropic's API. */
	CLAUDE,

	/** Any API that follows OpenAI's chat completions format. */
	OPENAI,
}

data class Settings(
	val provider: Provider = Provider.CLAUDE,
	val apiKey: String = "",
	val baseUrl: String = "",
	val model: String = ClaudeModel.DEFAULT.id(),
	val effort: String = AgentConfig.DEFAULT_EFFORT,
	val approvalMode: ApprovalMode = ApprovalMode.ASK_BEFORE_CHANGES,
	val refusalFallback: Boolean = true,
	val claudeModels: List<ModelInfo> = emptyList(),
	val openAiBaseUrl: String = "",
	val openAiApiKey: String = "",
	val openAiModel: String = "",
	val openAiModels: List<ModelInfo> = emptyList(),
	val onboarded: Boolean = false,
	val mcpPort: Int = McpHttpServer.DEFAULT_PORT,
	val mcpToken: String = "",
	val mcpNetwork: Boolean = false,
) {
	/** @return Configuration of a Claude agent with these settings. */
	fun agentConfig(): AgentConfig =
		AgentConfig(apiKey, baseUrl.ifBlank { null }, model, effort, AgentConfig.DEFAULT_MAX_TOKENS, refusalFallback, approvalMode)

	/** @return Configuration of an agent for an OpenAI-compatible API with these settings. */
	fun openAiConfig(): OpenAiConfig = OpenAiConfig(openAiBaseUrl, openAiApiKey.ifBlank { null }, openAiModel, approvalMode)

	/** Whether everything the agent needs to run is set. */
	val agentReady: Boolean
		get() = when (provider) {
			Provider.CLAUDE -> apiKey.isNotBlank()
			// A key is optional: model servers on the local network usually ask for none.
			Provider.OPENAI -> openAiBaseUrl.isNotBlank() && openAiModel.isNotBlank()
		}

	/** What is missing for the agent to run, in words for the user. `null` when nothing is. */
	val missing: String?
		get() = when {
			agentReady -> null
			provider == Provider.CLAUDE -> "Add your Anthropic API key in Settings to use the agent."
			openAiBaseUrl.isBlank() -> "Set the address of the API in Settings to use the agent."
			else -> "Choose a model in Settings to use the agent."
		}

	/** Name of the model the agent runs on, for display. */
	val modelLabel: String
		get() = when (provider) {
			Provider.CLAUDE -> claudeModels.firstOrNull { it.id == model }?.name ?: ClaudeModel.of(model).name()
			Provider.OPENAI -> openAiModel.ifBlank { "No model chosen" }
		}
}

/**
 * Persists [Settings] in the app's private preferences.
 *
 * The API key and the MCP token are encrypted with a key that lives in the Android Keystore and never leaves it,
 * so a copy of the preferences file alone does not reveal them.
 */
object SettingsStore {
	private const val KEY_ALIAS = "recaf_settings_key"
	private const val ANDROID_KEY_STORE = "AndroidKeyStore"
	private lateinit var prefs: SharedPreferences
	private val state = MutableStateFlow(Settings())

	/** Current settings, updated on every change. */
	val settings: StateFlow<Settings> get() = state

	fun init(context: Context) {
		prefs = context.getSharedPreferences("recaf_settings", Context.MODE_PRIVATE)
		var loaded = Settings(
			provider = runCatching { Provider.valueOf(prefs.getString("provider", "")!!) }.getOrDefault(Provider.CLAUDE),
			apiKey = decrypt(prefs.getString("api_key", null)),
			baseUrl = prefs.getString("base_url", null) ?: "",
			claudeModels = decodeModels(prefs.getString("claude_models", null)),
			openAiBaseUrl = prefs.getString("openai_base_url", null) ?: "",
			openAiApiKey = decrypt(prefs.getString("openai_api_key", null)),
			openAiModel = prefs.getString("openai_model", null) ?: "",
			openAiModels = decodeModels(prefs.getString("openai_models", null)),
			onboarded = prefs.getBoolean("onboarded", false),
			model = prefs.getString("model", null) ?: ClaudeModel.DEFAULT.id(),
			effort = prefs.getString("effort", null) ?: AgentConfig.DEFAULT_EFFORT,
			approvalMode = runCatching { ApprovalMode.valueOf(prefs.getString("approval", "")!!) }
				.getOrDefault(ApprovalMode.ASK_BEFORE_CHANGES),
			refusalFallback = prefs.getBoolean("refusal_fallback", true),
			mcpPort = prefs.getInt("mcp_port", McpHttpServer.DEFAULT_PORT),
			mcpToken = decrypt(prefs.getString("mcp_token", null)),
			mcpNetwork = prefs.getBoolean("mcp_network", false),
		)
		// The MCP server never runs without a token, so there always is one.
		if (loaded.mcpToken.isEmpty()) {
			loaded = loaded.copy(mcpToken = newToken())
			save(loaded)
		}
		state.value = loaded
	}

	fun update(transform: (Settings) -> Settings) {
		val updated = transform(state.value)
		state.value = updated
		save(updated)
	}

	/** @return A new random access token. */
	fun newToken(): String {
		val bytes = ByteArray(24)
		SecureRandom().nextBytes(bytes)
		return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
	}

	private fun save(settings: Settings) {
		prefs.edit()
			.putString("provider", settings.provider.name)
			.putString("api_key", encrypt(settings.apiKey))
			.putString("base_url", settings.baseUrl)
			.putString("claude_models", encodeModels(settings.claudeModels))
			.putString("openai_base_url", settings.openAiBaseUrl)
			.putString("openai_api_key", encrypt(settings.openAiApiKey))
			.putString("openai_model", settings.openAiModel)
			.putString("openai_models", encodeModels(settings.openAiModels))
			.putBoolean("onboarded", settings.onboarded)
			.putString("model", settings.model)
			.putString("effort", settings.effort)
			.putString("approval", settings.approvalMode.name)
			.putBoolean("refusal_fallback", settings.refusalFallback)
			.putInt("mcp_port", settings.mcpPort)
			.putString("mcp_token", encrypt(settings.mcpToken))
			.putBoolean("mcp_network", settings.mcpNetwork)
			.apply()
	}

	private fun encodeModels(models: List<ModelInfo>): String {
		val array = JsonArray()
		for (model in models)
			array.add(JsonObject().apply {
				addProperty("id", model.id)
				addProperty("name", model.name)
			})
		return array.toString()
	}

	private fun decodeModels(stored: String?): List<ModelInfo> {
		if (stored.isNullOrEmpty()) return emptyList()
		return runCatching {
			JsonParser.parseString(stored).asJsonArray.map {
				val model = it.asJsonObject
				ModelInfo(model.get("id").asString, model.get("name").asString)
			}
		}.getOrDefault(emptyList())
	}

	private fun secretKey(): SecretKey {
		val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
		(keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
		val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
		generator.init(
			KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
				.setBlockModes(KeyProperties.BLOCK_MODE_GCM)
				.setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
				.setKeySize(256)
				.build()
		)
		return generator.generateKey()
	}

	private fun encrypt(plain: String): String? {
		if (plain.isEmpty()) return null
		return runCatching {
			val cipher = Cipher.getInstance("AES/GCM/NoPadding")
			cipher.init(Cipher.ENCRYPT_MODE, secretKey())
			val payload = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
			Base64.encodeToString(payload, Base64.NO_WRAP)
		}.getOrNull()
	}

	private fun decrypt(stored: String?): String {
		if (stored.isNullOrEmpty()) return ""
		return runCatching {
			val payload = Base64.decode(stored, Base64.NO_WRAP)
			val cipher = Cipher.getInstance("AES/GCM/NoPadding")
			// GCM uses a 12 byte IV, which the cipher generated and which is stored in front of the ciphertext.
			cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, payload, 0, 12))
			String(cipher.doFinal(payload, 12, payload.size - 12), Charsets.UTF_8)
		}.getOrDefault("")
	}
}
