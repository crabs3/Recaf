package software.coley.recaf.android.app

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.gson.JsonParser
import kotlinx.coroutines.launch
import software.coley.recaf.agent.claude.AgentListener
import software.coley.recaf.agent.prompt.Playbooks

/**
 * The main screen: a conversation with the agent about the open program.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(navigator: Navigator, agent: AgentViewModel) {
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	val init by RecafSession.init.collectAsStateWithLifecycle()
	val workspace by RecafSession.workspace.collectAsStateWithLifecycle()
	val findings by RecafSession.findings.collectAsStateWithLifecycle()
	val settings by SettingsStore.settings.collectAsStateWithLifecycle()
	var menuOpen by remember { mutableStateOf(false) }
	var message by rememberSaveable { mutableStateOf("") }

	// A screen that sent the user back here may have prepared a message.
	LaunchedEffect(navigator.draft) {
		navigator.draft?.let {
			message = it
			navigator.draft = null
		}
	}

	val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
		if (uri != null) scope.launch {
			val result = RecafSession.openUri(uri)
			result.onSuccess { agent.newConversation() }
			context.toast(result.fold({ "Opened $it" }, { "Could not open the file: ${it.message}" }))
		}
	}
	val exportDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
		if (uri != null) scope.launch {
			context.toast(RecafSession.exportTo(uri).fold({ it }, { "Export failed: ${it.message}" }))
		}
	}

	Scaffold(
		topBar = {
			TopAppBar(
				title = {
					Column {
						Text("Recaf", style = MaterialTheme.typography.titleMedium)
						Text(
							workspace?.let { "${it.name} · ${it.classCount} classes" } ?: "No file open",
							style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
					}
				},
				actions = {
					IconButton(onClick = { openDocument.launch(arrayOf("*/*")) }, enabled = init is InitState.Ready) {
						Icon(Icons.Default.Add, contentDescription = "Open file")
					}
					IconButton(onClick = { navigator.go(Screen.Findings) }) {
						BadgedBox(badge = { if (findings.isNotEmpty()) Badge { Text(findings.size.toString()) } }) {
							Icon(Icons.Default.Star, contentDescription = "Findings")
						}
					}
					IconButton(onClick = { menuOpen = true }) { Icon(Icons.Default.MoreVert, contentDescription = "More") }
					DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
						@Composable
						fun item(label: String, enabled: Boolean = true, action: () -> Unit) = DropdownMenuItem(
							text = { Text(label) }, enabled = enabled, onClick = { menuOpen = false; action() })
						item("Browse classes and files", workspace != null) { navigator.go(Screen.Browser) }
						item("Tools", init is InitState.Ready) { navigator.go(Screen.Tools) }
						item("Open installed app", init is InitState.Ready) { navigator.go(Screen.Apps) }
						item("Export workspace", workspace != null) { exportDocument.launch("modified-" + (workspace?.name ?: "workspace")) }
						HorizontalDivider()
						item("New conversation", !agent.running) { agent.newConversation() }
						item("MCP server", init is InitState.Ready) { navigator.go(Screen.Mcp) }
						item("Settings") { navigator.go(Screen.Settings) }
					}
				},
			)
		},
		bottomBar = {
			Composer(
				message = message,
				onMessageChange = { message = it },
				running = agent.running,
				enabled = init is InitState.Ready,
				usage = agent.usage,
				onSend = {
					agent.send(message)
					message = ""
				},
				onStop = agent::stop,
			)
		},
	) { padding ->
		Box(Modifier.fillMaxSize().padding(padding)) {
			when (val state = init) {
				InitState.Loading -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
					CircularProgressIndicator()
					Text("Starting Recaf…", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.bodyMedium)
				}
				is InitState.Failed -> Text(
					"Recaf could not start:\n${state.message}",
					Modifier.align(Alignment.Center).padding(24.dp),
					color = MaterialTheme.colorScheme.error,
				)
				InitState.Ready -> if (agent.items.isEmpty()) {
					Welcome(
						hasKey = settings.apiKey.isNotBlank(),
						hasWorkspace = workspace != null,
						android = workspace?.android == true,
						onOpenSettings = { navigator.go(Screen.Settings) },
						onOpenFile = { openDocument.launch(arrayOf("*/*")) },
						onOpenApp = { navigator.go(Screen.Apps) },
						onPlaybook = agent::send,
					)
				} else {
					Conversation(agent, onResume = agent::resume)
				}
			}
		}
	}

	agent.pendingApproval?.let { request ->
		AlertDialog(
			onDismissRequest = { agent.decide(false) },
			title = { Text("Allow ${request.tool.name()}?") },
			text = {
				Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
					Text("The agent wants to change the workspace:", style = MaterialTheme.typography.bodyMedium)
					Text(
						prettyJson(request.arguments.toString()),
						Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()),
						fontFamily = Mono,
						fontSize = 12.sp,
					)
				}
			},
			confirmButton = {
				Column(horizontalAlignment = Alignment.End) {
					Button(onClick = { agent.decide(true) }) { Text("Allow once") }
					TextButton(onClick = { agent.decide(true, forConversation = true) }) { Text("Allow for this conversation") }
				}
			},
			dismissButton = { TextButton(onClick = { agent.decide(false) }) { Text("Deny") } },
		)
	}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Welcome(
	hasKey: Boolean,
	hasWorkspace: Boolean,
	android: Boolean,
	onOpenSettings: () -> Unit,
	onOpenFile: () -> Unit,
	onOpenApp: () -> Unit,
	onPlaybook: (String) -> Unit,
) {
	Column(
		Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
		verticalArrangement = Arrangement.spacedBy(16.dp),
	) {
		Text("Dissect Java and Android programs with an agent", style = MaterialTheme.typography.headlineSmall)
		Text(
			"Open a JAR, APK, DEX or class file, then ask about it. The agent decompiles, searches, " +
				"follows references and can patch and deobfuscate the program for you.",
			style = MaterialTheme.typography.bodyMedium,
			color = MaterialTheme.colorScheme.onSurfaceVariant,
		)
		if (!hasKey) {
			Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
				Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
					Text("Add an Anthropic API key", fontWeight = FontWeight.SemiBold)
					Text("The agent runs on Claude and needs your API key. Browsing and the tools work without one.",
						style = MaterialTheme.typography.bodySmall)
					Button(onClick = onOpenSettings) { Text("Open settings") }
				}
			}
		}
		if (!hasWorkspace) {
			Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
				Button(onClick = onOpenFile) { Text("Open a file") }
				OutlinedButton(onClick = onOpenApp) { Text("Open an installed app") }
			}
		} else {
			Text("Start with", style = MaterialTheme.typography.titleSmall)
			FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
				for (playbook in Playbooks.all()) {
					// The Android audit only makes sense for an APK.
					if (playbook.id() == "android-audit" && !android) continue
					AssistChip(onClick = { onPlaybook(playbook.prompt()) }, label = { Text(playbook.title()) }, enabled = hasKey)
				}
			}
			Text("or type a question below.", style = MaterialTheme.typography.bodySmall,
				color = MaterialTheme.colorScheme.onSurfaceVariant)
		}
	}
}

@Composable
private fun Conversation(agent: AgentViewModel, onResume: () -> Unit) {
	val listState = rememberLazyListState()
	val items = agent.items
	// Follow the conversation as it grows, unless the user scrolled up to read.
	val last = items.lastOrNull()
	val growth = when (last) {
		is ChatItem.Assistant -> last.text.length
		is ChatItem.Thinking -> last.text.length
		is ChatItem.Tool -> if (last.finished) 1 else 0
		else -> 0
	}
	LaunchedEffect(items.size, growth) {
		val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
		if (items.isNotEmpty() && lastVisible >= items.size - 3)
			listState.scrollToItem(items.size - 1, Int.MAX_VALUE / 2)
	}

	LazyColumn(
		Modifier.fillMaxSize(),
		state = listState,
		contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 12.dp),
		verticalArrangement = Arrangement.spacedBy(10.dp),
	) {
		items(items, key = { it.id }) { item ->
			when (item) {
				is ChatItem.User -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
					Surface(
						color = MaterialTheme.colorScheme.primaryContainer,
						shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
						modifier = Modifier.widthIn(max = 320.dp),
					) {
						SelectionContainer { Text(item.text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium) }
					}
				}
				is ChatItem.Assistant -> SelectionContainer { Markdown(item.text, Modifier.fillMaxWidth()) }
				is ChatItem.Thinking -> if (item.text.isNotBlank()) Text(
					item.text.trim(),
					style = MaterialTheme.typography.bodySmall,
					fontStyle = FontStyle.Italic,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 6,
					overflow = TextOverflow.Ellipsis,
				)
				is ChatItem.Tool -> ToolCard(item)
				is ChatItem.Notice -> NoticeCard(item, showResume = item === items.last() && !agent.running, onResume = onResume)
			}
		}
		if (agent.running && (last == null || last is ChatItem.User || (last is ChatItem.Tool && last.finished))) {
			item(key = "working") {
				Row(verticalAlignment = Alignment.CenterVertically) {
					CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
					Text("Working…", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodySmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant)
				}
			}
		}
	}
}

/** A tool call: one line that says what was done, expandable to the full arguments and result. */
@Composable
private fun ToolCard(item: ChatItem.Tool) {
	var expanded by remember { mutableStateOf(false) }
	Surface(
		color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
		shape = RoundedCornerShape(10.dp),
		modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { expanded = !expanded }.animateContentSize(),
	) {
		Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
			Row(verticalAlignment = Alignment.CenterVertically) {
				when {
					!item.finished -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
					item.error -> Icon(Icons.Default.Warning, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
					else -> Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
				}
				Text(item.name, Modifier.padding(start = 8.dp), fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.Medium)
				Text(
					summarize(item.arguments),
					Modifier.padding(start = 8.dp).weight(1f),
					style = MaterialTheme.typography.bodySmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
					maxLines = 1,
					overflow = TextOverflow.Ellipsis,
				)
				if (item.finished && item.durationMillis >= 1000)
					Text("${item.durationMillis / 1000}s", style = MaterialTheme.typography.labelSmall,
						color = MaterialTheme.colorScheme.onSurfaceVariant)
			}
			if (expanded) {
				SelectionContainer {
					Column(Modifier.padding(top = 8.dp)) {
						item.arguments?.let {
							Text(prettyJson(it), Modifier.horizontalScroll(rememberScrollState()), fontFamily = Mono, fontSize = 11.sp, lineHeight = 15.sp)
						}
						item.result?.let {
							HorizontalDivider(Modifier.padding(vertical = 6.dp))
							// Results can be long, the card shows the beginning. The agent saw all of it.
							val shown = if (it.length > 4000) it.take(4000) + "\n… (${it.length - 4000} more characters)" else it
							Text(shown, Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())
								.horizontalScroll(rememberScrollState()), fontFamily = Mono, fontSize = 11.sp, lineHeight = 15.sp,
								color = if (item.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
						}
					}
				}
			}
		}
	}
}

@Composable
private fun NoticeCard(item: ChatItem.Notice, showResume: Boolean, onResume: () -> Unit) {
	val container = if (item.error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
	Surface(color = container, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
		Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
			Text(item.text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
			if (item.canResume && showResume)
				TextButton(onClick = onResume) { Text("Continue") }
		}
	}
}

@Composable
private fun Composer(
	message: String,
	onMessageChange: (String) -> Unit,
	running: Boolean,
	enabled: Boolean,
	usage: AgentListener.Usage?,
	onSend: () -> Unit,
	onStop: () -> Unit,
) {
	Surface(tonalElevation = 3.dp) {
		Column(Modifier.navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp)) {
			if (usage != null) {
				val tokens = usage.inputTokens() + usage.cacheReadTokens() + usage.cacheWriteTokens()
				val cost = usage.estimatedCost()?.let { " · about $" + String.format("%.2f", it) } ?: ""
				Text(
					"${formatCount(tokens)} tokens in, ${formatCount(usage.outputTokens())} out$cost",
					Modifier.padding(bottom = 4.dp),
					style = MaterialTheme.typography.labelSmall,
					color = MaterialTheme.colorScheme.onSurfaceVariant,
				)
			}
			Row(verticalAlignment = Alignment.Bottom) {
				OutlinedTextField(
					value = message,
					onValueChange = onMessageChange,
					modifier = Modifier.weight(1f),
					placeholder = { Text("Ask about the program") },
					enabled = enabled,
					maxLines = 6,
				)
				Spacer(Modifier.width(8.dp))
				if (running) {
					FilledIconButton(onClick = onStop) { Icon(Icons.Default.Close, contentDescription = "Stop") }
				} else {
					FilledIconButton(onClick = onSend, enabled = enabled && message.isNotBlank()) {
						Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
					}
				}
			}
		}
	}
}

private val summaryKeys = listOf("class", "method", "name", "query", "pattern", "path", "owner", "file_name", "title", "package", "value")

/** One-line description of a tool call's arguments: the values a reader would look for first. */
private fun summarize(argumentsJson: String?): String {
	if (argumentsJson == null) return ""
	return runCatching {
		val json = JsonParser.parseString(argumentsJson).asJsonObject
		val parts = summaryKeys.filter { json.has(it) && json.get(it).isJsonPrimitive }.map { json.get(it).asString }
		if (parts.isNotEmpty()) parts.joinToString(" · ")
		else json.entrySet().firstOrNull { it.value.isJsonPrimitive }?.value?.asString?.lineSequence()?.firstOrNull() ?: ""
	}.getOrDefault("")
}

fun prettyJson(json: String): String = runCatching {
	com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(JsonParser.parseString(json))
}.getOrDefault(json)

private fun formatCount(count: Long): String = when {
	count >= 1_000_000 -> String.format("%.1fM", count / 1_000_000.0)
	count >= 1_000 -> String.format("%.1fk", count / 1_000.0)
	else -> count.toString()
}
