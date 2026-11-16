package software.coley.recaf.android.app

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import software.coley.recaf.agent.chat.ModelInfo
import software.coley.recaf.agent.claude.ClaudeModel
import software.coley.recaf.agent.claude.ClaudeModels
import software.coley.recaf.agent.openai.OpenAiClient
import software.coley.recaf.agent.openai.OpenAiConfig

/** Where an Anthropic API key is created. Signing in to the Claude Console happens on this page. */
private const val CLAUDE_KEYS_URL = "https://platform.claude.com/settings/keys"

/** Addresses of common OpenAI-compatible APIs. */
private val openAiPresets = listOf(
	"OpenAI" to "https://api.openai.com/v1",
	"OpenRouter" to "https://openrouter.ai/api/v1",
	"Ollama" to "http://localhost:11434/v1",
	"LM Studio" to "http://localhost:1234/v1",
)

/** How many fetched models are listed at once. More than that and the name has to be narrowed down by typing. */
private const val MAX_LISTED_MODELS = 40

/** Progress of asking an API for its models. */
private sealed interface FetchState {
	data object Idle : FetchState
	data object Loading : FetchState
	data class Done(val message: String) : FetchState
	data class Failed(val message: String) : FetchState
}

private fun Context.openUrl(url: String) {
	runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
		.onFailure { toast("No app can open $url") }
}

/** @return Text on the clipboard, if any. Reading it is done only when the user taps "Paste". */
private fun Context.clipboardText(): String? =
	getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()

@Composable
private fun FetchStatus(state: FetchState) {
	when (state) {
		FetchState.Idle -> {}
		FetchState.Loading -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
			Text("Connecting…", style = MaterialTheme.typography.bodySmall)
		}
		is FetchState.Done -> Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
		is FetchState.Failed -> Text(state.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
	}
}

@Composable
private fun ModelRow(selected: Boolean, title: String, detail: String?, onSelect: () -> Unit) {
	Row(Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
		RadioButton(selected = selected, onClick = onSelect)
		Column {
			Text(title, style = MaterialTheme.typography.bodyMedium)
			if (!detail.isNullOrBlank())
				Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
		}
	}
}

/**
 * Connecting to Claude: the API key, a check that it works, and the choice among the models it can use.
 * Used by the first-run setup and by the settings.
 */
@Composable
fun ClaudeConnection(settings: Settings) {
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	var showKey by remember { mutableStateOf(false) }
	var showAll by remember { mutableStateOf(false) }
	var state by remember { mutableStateOf<FetchState>(FetchState.Idle) }

	Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
		Text("Recaf connects to Claude with an API key from your Claude Console account. Sign in there, create a key, " +
			"and paste it below. Usage is billed to that account.",
			style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
		OutlinedButton(onClick = { context.openUrl(CLAUDE_KEYS_URL) }) { Text("Sign in and create a key") }
		OutlinedTextField(
			value = settings.apiKey,
			onValueChange = { value ->
				state = FetchState.Idle
				SettingsStore.update { it.copy(apiKey = value.trim()) }
			},
			modifier = Modifier.fillMaxWidth(),
			label = { Text("API key") },
			placeholder = { Text("sk-ant-…") },
			singleLine = true,
			visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
			keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
			trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") } },
			supportingText = {
				Text(if (settings.baseUrl.isBlank()) "Stored encrypted on this device and sent only to the Anthropic API."
				else "Stored encrypted on this device and sent to the API endpoint set below.")
			},
		)
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
			OutlinedButton(onClick = {
				context.clipboardText()?.trim()?.takeIf { it.isNotEmpty() }?.let { pasted ->
					state = FetchState.Idle
					SettingsStore.update { it.copy(apiKey = pasted) }
				} ?: context.toast("The clipboard is empty")
			}) { Text("Paste") }
			Button(
				enabled = settings.apiKey.isNotBlank() && state != FetchState.Loading,
				onClick = {
					state = FetchState.Loading
					scope.launch {
						val result = withContext(Dispatchers.IO) {
							runCatching { ClaudeModels.list(settings.apiKey, settings.baseUrl.ifBlank { null }) }
						}
						state = result.fold(
							onSuccess = { models ->
								SettingsStore.update { current ->
									// Keep the chosen model if the key can use it, else fall back to the recommended one.
									val usable = models.any { it.id == current.model }
									val fallback = models.firstOrNull { it.id == ClaudeModel.DEFAULT.id() } ?: models.firstOrNull()
									current.copy(claudeModels = models, model = if (usable || fallback == null) current.model else fallback.id)
								}
								FetchState.Done("Connected. This key can use ${models.size} models.")
							},
							onFailure = { FetchState.Failed(it.message ?: it.toString()) },
						)
					}
				},
			) { Text("Check key") }
		}
		FetchStatus(state)

		Text("Model", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
		val fetched = settings.claudeModels
		// The models Recaf knows something about come first, with what they are good for.
		val recommended = ClaudeModel.known().filter { known -> fetched.isEmpty() || fetched.any { it.id == known.id() } }
		for (model in recommended)
			ModelRow(settings.model == model.id(), model.name(), model.summary()) { SettingsStore.update { it.copy(model = model.id()) } }
		val others = fetched.filter { model -> ClaudeModel.known().none { it.id() == model.id } }
		// A model chosen earlier stays visible even when it is not among the recommended ones.
		if (recommended.none { it.id() == settings.model } && others.none { it.id == settings.model })
			ModelRow(true, settings.model, "Not in the list of models this key can use.") {}
		if (others.isNotEmpty()) {
			if (showAll || others.any { it.id == settings.model }) {
				for (model in others)
					ModelRow(settings.model == model.id, model.name, model.id) { SettingsStore.update { it.copy(model = model.id) } }
			} else {
				TextButton(onClick = { showAll = true }) { Text("Show ${others.size} more models") }
			}
		} else if (fetched.isEmpty()) {
			Text("Check the key to see every model it can use.", style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant)
		}
	}
}

/**
 * Connecting to an OpenAI-compatible API: its address, an optional key, and the choice among the models it offers.
 * Used by the first-run setup and by the settings.
 */
@Composable
fun OpenAiConnection(settings: Settings) {
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	var showKey by remember { mutableStateOf(false) }
	var state by remember { mutableStateOf<FetchState>(FetchState.Idle) }

	Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
		Text("Works with any API that follows OpenAI's chat completions format: OpenAI, gateways such as OpenRouter, " +
			"and model servers such as Ollama, LM Studio or vLLM. The model has to support tool calling.",
			style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
		Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
			for ((label, url) in openAiPresets)
				FilterChip(
					selected = OpenAiConfig.normalizeBaseUrl(settings.openAiBaseUrl) == url,
					onClick = {
						state = FetchState.Idle
						SettingsStore.update { it.copy(openAiBaseUrl = url, openAiModels = emptyList()) }
					},
					label = { Text(label) },
				)
		}
		OutlinedTextField(
			value = settings.openAiBaseUrl,
			onValueChange = { value ->
				state = FetchState.Idle
				SettingsStore.update { it.copy(openAiBaseUrl = value.trim()) }
			},
			modifier = Modifier.fillMaxWidth(),
			label = { Text("API address") },
			placeholder = { Text("https://api.openai.com/v1") },
			singleLine = true,
			keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
			supportingText = {
				val url = settings.openAiBaseUrl
				Text(when {
					url.contains("localhost") || url.contains("127.0.0.1") ->
						"localhost is this phone. For a server on another device, put that device's address here."
					OpenAiConfig.isPlainHttp(OpenAiConfig.normalizeBaseUrl(url)) ->
						"Not encrypted: conversations and the key travel in the clear. Use it only on a network you trust."
					else -> "The address up to and including the version, usually ending in /v1."
				})
			},
		)
		OutlinedTextField(
			value = settings.openAiApiKey,
			onValueChange = { value ->
				state = FetchState.Idle
				SettingsStore.update { it.copy(openAiApiKey = value.trim()) }
			},
			modifier = Modifier.fillMaxWidth(),
			label = { Text("API key") },
			placeholder = { Text("Leave empty if the server needs none") },
			singleLine = true,
			visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
			keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
			trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") } },
			supportingText = { Text("Stored encrypted on this device and sent only to the address above.") },
		)
		Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
			OutlinedButton(onClick = {
				context.clipboardText()?.trim()?.takeIf { it.isNotEmpty() }?.let { pasted ->
					state = FetchState.Idle
					SettingsStore.update { it.copy(openAiApiKey = pasted) }
				} ?: context.toast("The clipboard is empty")
			}) { Text("Paste key") }
			Button(
				enabled = settings.openAiBaseUrl.isNotBlank() && state != FetchState.Loading,
				onClick = {
					state = FetchState.Loading
					scope.launch {
						val result = withContext(Dispatchers.IO) {
							runCatching { OpenAiClient(settings.openAiBaseUrl, settings.openAiApiKey.ifBlank { null }).listModels() }
						}
						state = result.fold(
							onSuccess = { list ->
								// The address the models were found at may be the entered one with its version added.
								SettingsStore.update { it.copy(openAiBaseUrl = list.baseUrl(), openAiModels = list.models()) }
								if (list.models().isEmpty()) FetchState.Failed("Connected, but the API lists no models. Enter the model's name below.")
								else FetchState.Done("Connected. The API offers ${list.models().size} models.")
							},
							onFailure = { FetchState.Failed(it.message ?: it.toString()) },
						)
					}
				},
			) { Text("Load models") }
		}
		FetchStatus(state)

		OutlinedTextField(
			value = settings.openAiModel,
			onValueChange = { value -> SettingsStore.update { it.copy(openAiModel = value.trim()) } },
			modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
			label = { Text("Model") },
			placeholder = { Text(if (settings.openAiModels.isEmpty()) "Name of the model" else "Type to narrow down the list") },
			singleLine = true,
		)
		val models = settings.openAiModels
		if (models.isNotEmpty()) {
			val typed = settings.openAiModel
			val chosen = models.any { it.id == typed }
			// With a model chosen the whole list is shown again, so that another one can be picked.
			val matching = if (chosen || typed.isBlank()) models else models.filter { it.id.contains(typed, ignoreCase = true) }
			for (model in matching.take(MAX_LISTED_MODELS))
				ModelRow(model.id == typed, model.id, model.name.takeIf { it != model.id }) {
					SettingsStore.update { it.copy(openAiModel = model.id) }
				}
			if (matching.size > MAX_LISTED_MODELS)
				Text("${matching.size - MAX_LISTED_MODELS} more. Type part of a name to narrow the list down.",
					style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
			if (matching.isEmpty())
				Text("No listed model matches. The name is used as typed.",
					style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
		}
	}
}

/**
 * First-run setup: choose what the agent runs on and connect it, or skip and only browse.
 * Can be opened again from the settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(navigator: Navigator) {
	val settings by SettingsStore.settings.collectAsStateWithLifecycle()
	// Which provider is being set up. Empty while the choice is still open.
	var step by rememberSaveable { mutableStateOf("") }

	fun finish() {
		SettingsStore.update { it.copy(onboarded = true) }
		navigator.toChat()
	}

	fun back() {
		if (step.isEmpty()) finish() else step = ""
	}
	BackHandler(onBack = ::back)

	Scaffold(
		topBar = {
			TopAppBar(
				title = { Text(when (step) { "claude" -> "Connect Claude"; "openai" -> "Connect an API"; else -> "Set up the agent" }) },
				navigationIcon = {
					if (step.isNotEmpty())
						IconButton(onClick = ::back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
				},
			)
		},
	) { padding ->
		Column(
			Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			when (step) {
				"claude" -> {
					ClaudeConnection(settings)
					HorizontalDivider(Modifier.padding(top = 8.dp))
					Button(onClick = ::finish, enabled = settings.apiKey.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Done") }
				}
				"openai" -> {
					OpenAiConnection(settings)
					HorizontalDivider(Modifier.padding(top = 8.dp))
					Button(onClick = ::finish, enabled = settings.agentReady, modifier = Modifier.fillMaxWidth()) { Text("Done") }
				}
				else -> {
					Text("Recaf's agent reads, searches and edits programs for you. It needs a model to think with.",
						style = MaterialTheme.typography.bodyLarge)
					ProviderCard(
						title = "Claude",
						detail = "Anthropic's models. Sign in to the Claude Console, create an API key, and paste it here.",
						action = "Connect Claude",
					) {
						SettingsStore.update { it.copy(provider = Provider.CLAUDE) }
						step = "claude"
					}
					ProviderCard(
						title = "OpenAI-compatible API",
						detail = "OpenAI, OpenRouter and other gateways, or a model server of your own such as Ollama or LM Studio. " +
							"Recaf fetches the list of models from it.",
						action = "Connect an API",
					) {
						SettingsStore.update { it.copy(provider = Provider.OPENAI) }
						step = "openai"
					}
					TextButton(onClick = ::finish, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Skip for now") }
					Text("Without a model you can still open files, browse and decompile classes, and use every tool by hand. " +
						"The setup is in Settings whenever you want it.",
						style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
				}
			}
		}
	}
}

@Composable
private fun ProviderCard(title: String, detail: String, action: String, onClick: () -> Unit) {
	Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
		Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
			Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
			Text(detail, style = MaterialTheme.typography.bodyMedium)
			Button(onClick = onClick) { Text(action) }
		}
	}
}
