package software.coley.recaf.android.app

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import software.coley.recaf.agent.claude.AgentConfig
import software.coley.recaf.agent.claude.ClaudeModel
import software.coley.recaf.agent.mcp.McpHttpServer
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
 * @property mcpPort Port of the MCP server.
 * @property mcpToken Access token of the MCP server.
 * @property mcpNetwork Whether the MCP server accepts connections from other devices.
 */
data class Settings(
	val apiKey: String = "",
	val model: String = ClaudeModel.DEFAULT.id(),
	val effort: String = AgentConfig.DEFAULT_EFFORT,
	val approvalMode: AgentConfig.ApprovalMode = AgentConfig.ApprovalMode.ASK_BEFORE_CHANGES,
	val refusalFallback: Boolean = true,
	val mcpPort: Int = McpHttpServer.DEFAULT_PORT,
	val mcpToken: String = "",
	val mcpNetwork: Boolean = false,
) {
	/** @return Configuration of an agent with these settings. */
	fun agentConfig(): AgentConfig =
		AgentConfig(apiKey, null, model, effort, AgentConfig.DEFAULT_MAX_TOKENS, refusalFallback, approvalMode)
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
			apiKey = decrypt(prefs.getString("api_key", null)),
			model = prefs.getString("model", null) ?: ClaudeModel.DEFAULT.id(),
			effort = prefs.getString("effort", null) ?: AgentConfig.DEFAULT_EFFORT,
			approvalMode = runCatching { AgentConfig.ApprovalMode.valueOf(prefs.getString("approval", "")!!) }
				.getOrDefault(AgentConfig.ApprovalMode.ASK_BEFORE_CHANGES),
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
			.putString("api_key", encrypt(settings.apiKey))
			.putString("model", settings.model)
			.putString("effort", settings.effort)
			.putString("approval", settings.approvalMode.name)
			.putBoolean("refusal_fallback", settings.refusalFallback)
			.putInt("mcp_port", settings.mcpPort)
			.putString("mcp_token", encrypt(settings.mcpToken))
			.putBoolean("mcp_network", settings.mcpNetwork)
			.apply()
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
