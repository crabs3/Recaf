package software.coley.recaf.android.app

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import software.coley.recaf.RecafBuildConfig
import software.coley.recaf.agent.chat.ApprovalMode
import software.coley.recaf.agent.claude.AgentConfig
import software.coley.recaf.agent.claude.ClaudeModel
import software.coley.recaf.agent.mcp.McpHttpServer
import java.net.Inet4Address
import java.net.NetworkInterface

@Composable
private fun SectionTitle(text: String) {
	Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
		modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun Choice(selected: Boolean, title: String, detail: String, onSelect: () -> Unit) {
	Row(Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
		RadioButton(selected = selected, onClick = onSelect)
		Column {
			Text(title, style = MaterialTheme.typography.bodyMedium)
			Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
		}
	}
}

/** Settings of the agent: what it runs on, how thoroughly it works, and what it may do without asking. */
@Composable
fun SettingsScreen(navigator: Navigator) {
	val settings by SettingsStore.settings.collectAsStateWithLifecycle()
	val context = LocalContext.current
	ScreenFrame("Settings", navigator) { padding ->
		Column(
			Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
			verticalArrangement = Arrangement.spacedBy(8.dp),
		) {
			SectionTitle("The agent runs on")
			Choice(settings.provider == Provider.CLAUDE, "Claude",
				"Anthropic's models, with an API key from the Claude Console.") {
				SettingsStore.update { it.copy(provider = Provider.CLAUDE) }
			}
			Choice(settings.provider == Provider.OPENAI, "OpenAI-compatible API",
				"OpenAI, a gateway such as OpenRouter, or a model server such as Ollama or LM Studio.") {
				SettingsStore.update { it.copy(provider = Provider.OPENAI) }
			}

			if (settings.provider == Provider.CLAUDE) {
				SectionTitle("Claude")
				ClaudeConnection(settings)

				SectionTitle("Effort")
				Text("How thoroughly the agent works. Higher effort takes longer and costs more.",
					style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
				Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					for (effort in listOf("low", "medium", "high", "xhigh", "max"))
						FilterChip(selected = settings.effort == effort, onClick = { SettingsStore.update { it.copy(effort = effort) } },
							label = { Text(effort) })
				}

				SectionTitle("Declined requests")
				Row(verticalAlignment = Alignment.CenterVertically) {
					Column(Modifier.weight(1f)) {
						Text("Retry on another model", style = MaterialTheme.typography.bodyMedium)
						Text("Reverse engineering questions are sometimes declined by a model's safety filters. " +
							"When that happens the request is answered by another Claude model instead of failing.",
							style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
					}
					Switch(checked = settings.refusalFallback, onCheckedChange = { value -> SettingsStore.update { it.copy(refusalFallback = value) } })
				}

				SectionTitle("API endpoint")
				OutlinedTextField(
					value = settings.baseUrl,
					onValueChange = { value -> SettingsStore.update { it.copy(baseUrl = value.trim()) } },
					modifier = Modifier.fillMaxWidth(),
					placeholder = { Text("https://api.anthropic.com") },
					singleLine = true,
					keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
					supportingText = {
						Text("Leave empty to use Anthropic's API. Set it only when your requests go through a gateway that " +
							"speaks Anthropic's format: conversations and the API key are then sent to this address.")
					},
				)
			} else {
				SectionTitle("OpenAI-compatible API")
				OpenAiConnection(settings)
			}

			SectionTitle("Changes to the program")
			Choice(settings.approvalMode == ApprovalMode.ASK_BEFORE_CHANGES, "Ask before changes",
				"The agent asks before every edit, rename, script or export.") {
				SettingsStore.update { it.copy(approvalMode = ApprovalMode.ASK_BEFORE_CHANGES) }
			}
			Choice(settings.approvalMode == ApprovalMode.ALLOW_CHANGES, "Allow changes",
				"The agent modifies the workspace without asking. Nothing reaches a file until you export.") {
				SettingsStore.update { it.copy(approvalMode = ApprovalMode.ALLOW_CHANGES) }
			}
			Choice(settings.approvalMode == ApprovalMode.READ_ONLY, "Read only",
				"The agent can inspect the program but not change it.") {
				SettingsStore.update { it.copy(approvalMode = ApprovalMode.READ_ONLY) }
			}

			SectionTitle("Diagnostics")
			Text("If something failed, this copies what Recaf recorded about it, to include in a bug report. " +
				"It holds error details and the names of classes involved, nothing else.",
				style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
			OutlinedButton(onClick = { context.copyToClipboard("Recaf diagnostics", CrashLog.diagnostics()) }) {
				Text("Copy diagnostics")
			}

			HorizontalDivider(Modifier.padding(top = 12.dp))
			TextButton(onClick = { navigator.go(Screen.Onboarding) }) { Text("Run the setup again") }
			Text("Recaf ${RecafBuildConfig.VERSION} for Android", style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant)
		}
	}
}

/** Controls of the built-in MCP server, and what a client needs to connect to it. */
@Composable
fun McpScreen(navigator: Navigator) {
	val context = LocalContext.current
	val settings by SettingsStore.settings.collectAsStateWithLifecycle()
	val state by RecafSession.mcp.collectAsStateWithLifecycle()
	val activity by RecafSession.activity.collectAsStateWithLifecycle()
	// The notification is how the user sees, and stops, a server that keeps running in the background.
	val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
		McpService.start(context)
	}

	ScreenFrame("MCP server", navigator) { padding ->
		Column(
			Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp),
		) {
			Text("Serves Recaf's tools over the Model Context Protocol, so that an MCP client such as Claude Code can " +
				"work on the program that is open here.", style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant)
			Row(verticalAlignment = Alignment.CenterVertically) {
				Text(if (state.running) "Running on port ${state.port}" else "Stopped", Modifier.weight(1f),
					style = MaterialTheme.typography.titleMedium)
				Switch(checked = state.running, onCheckedChange = { enable ->
					if (enable) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS) else McpService.stop(context)
				})
			}
			state.error?.let { Text("Could not start: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

			OutlinedTextField(
				value = settings.mcpPort.toString(),
				onValueChange = { value -> value.toIntOrNull()?.takeIf { it in 1024..65535 }?.let { port -> SettingsStore.update { it.copy(mcpPort = port) } } },
				label = { Text("Port") },
				enabled = !state.running,
				singleLine = true,
				keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
				supportingText = { Text("Default ${McpHttpServer.DEFAULT_PORT}") },
			)
			Row(verticalAlignment = Alignment.CenterVertically) {
				Column(Modifier.weight(1f)) {
					Text("Accept connections from the network", style = MaterialTheme.typography.bodyMedium)
					Text("Off: only clients on this device can connect. On: any device that can reach this one and " +
						"has the token can read and change the open program.",
						style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
				}
				Switch(checked = settings.mcpNetwork, enabled = !state.running,
					onCheckedChange = { value -> SettingsStore.update { it.copy(mcpNetwork = value) } })
			}

			val host = if (settings.mcpNetwork) localAddresses().firstOrNull() ?: "127.0.0.1" else "127.0.0.1"
			val url = "http://$host:${settings.mcpPort}${McpHttpServer.ENDPOINT}"
			val command = "claude mcp add --transport http recaf $url --header \"Authorization: Bearer ${settings.mcpToken}\""
			Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
				Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
					Text("Connect a client", fontWeight = FontWeight.SemiBold)
					SelectionContainer {
						Column {
							Text("URL", style = MaterialTheme.typography.labelSmall)
							Text(url, fontFamily = Mono, fontSize = 12.sp)
							Text("Token", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
							Text(settings.mcpToken, fontFamily = Mono, fontSize = 12.sp)
							Text("Claude Code", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
							Text(command, fontFamily = Mono, fontSize = 12.sp)
						}
					}
					Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
						OutlinedButton(onClick = { context.copyToClipboard("MCP command", command) }) { Text("Copy command") }
						TextButton(enabled = !state.running, onClick = { SettingsStore.update { it.copy(mcpToken = SettingsStore.newToken()) } }) {
							Text("New token")
						}
					}
					if (!settings.mcpNetwork)
						Text("From a computer, forward the port first: adb forward tcp:${settings.mcpPort} tcp:${settings.mcpPort}",
							style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
				}
			}

			Text("Recent tool calls", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
			if (activity.isEmpty())
				Text("None yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
			for (entry in activity.take(40)) {
				Text(
					(if (entry.error) "✕ " else "✓ ") + entry.tool + "  " + entry.arguments.take(80),
					fontFamily = Mono,
					fontSize = 11.sp,
					maxLines = 1,
					color = if (entry.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
				)
			}
		}
	}
}

/** @return IPv4 addresses of this device on its networks. */
private fun localAddresses(): List<String> = runCatching {
	NetworkInterface.getNetworkInterfaces().toList()
		.filter { it.isUp && !it.isLoopback }
		.flatMap { it.inetAddresses.toList() }
		.filterIsInstance<Inet4Address>()
		.map { it.hostAddress ?: "" }
		.filter { it.isNotEmpty() }
}.getOrDefault(emptyList())
