package software.coley.recaf.android.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import software.coley.recaf.agent.state.Finding

/**
 * Lists every tool the agent has. Each can also be run by hand, which gives the person the same reach into the
 * program as the agent without going through a conversation.
 */
@Composable
fun ToolsScreen(navigator: Navigator) {
	var filter by rememberSaveable { mutableStateOf("") }
	val tools = remember { RecafSession.toolbox.tools().toList() }
	ScreenFrame("Tools", navigator, subtitle = "${tools.size} tools, the same ones the agent uses") { padding ->
		Column(Modifier.fillMaxSize().padding(padding)) {
			OutlinedTextField(
				value = filter,
				onValueChange = { filter = it },
				modifier = Modifier.fillMaxWidth().padding(12.dp),
				placeholder = { Text("Filter tools") },
				leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
				singleLine = true,
			)
			val shown = remember(filter) {
				tools.filter { it.name().contains(filter.trim(), true) || it.description().contains(filter.trim(), true) }
			}
			LazyColumn(Modifier.fillMaxSize()) {
				items(shown, key = { it.name() }) { tool ->
					Column(
						Modifier.fillMaxWidth().clickable { navigator.go(Screen.ToolForm(tool.name())) }
							.padding(horizontal = 16.dp, vertical = 10.dp)
					) {
						Row(verticalAlignment = Alignment.CenterVertically) {
							Text(tool.name(), fontFamily = Mono, fontSize = 14.sp, fontWeight = FontWeight.Medium)
							if (!tool.readOnly())
								Text("  modifies", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
						}
						Text(tool.description(), style = MaterialTheme.typography.bodySmall,
							color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
					}
					HorizontalDivider()
				}
			}
		}
	}
}

/** A form generated from a tool's argument schema, to run the tool by hand. */
@Composable
fun ToolFormScreen(navigator: Navigator, name: String) {
	val scope = rememberCoroutineScope()
	val tool = remember(name) { RecafSession.toolbox.tool(name) }
	if (tool == null) {
		ScreenFrame(name, navigator) { EmptyNote("There is no tool named $name.", Modifier.padding(it)) }
		return
	}
	val schema = remember(tool) { tool.inputSchema() }
	val properties = remember(schema) { schema.getAsJsonObject("properties") }
	val required = remember(schema) { schema.getAsJsonArray("required").map { it.asString }.toSet() }
	val values = remember(tool) { mutableStateMapOf<String, String>() }
	val flags = remember(tool) { mutableStateMapOf<String, Boolean>() }
	var running by remember { mutableStateOf(false) }
	var result by remember { mutableStateOf<String?>(null) }
	var failed by remember { mutableStateOf(false) }

	ScreenFrame(name, navigator) { padding ->
		Column(
			Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
			verticalArrangement = Arrangement.spacedBy(12.dp),
		) {
			Text(tool.description(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
			for ((key, element) in properties.entrySet()) {
				val property = element.asJsonObject
				val type = property.get("type")?.asString ?: "string"
				val label = if (key in required) "$key *" else key
				val description = property.get("description")?.asString ?: ""
				when {
					type == "boolean" -> Row(verticalAlignment = Alignment.CenterVertically) {
						Column(Modifier.weight(1f)) {
							Text(label, style = MaterialTheme.typography.bodyMedium)
							Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
						}
						Switch(checked = flags[key] == true, onCheckedChange = { flags[key] = it })
					}
					property.has("enum") -> Column {
						Text(label, style = MaterialTheme.typography.bodyMedium)
						Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
							for (option in property.getAsJsonArray("enum").map { it.asString })
								FilterChip(
									selected = values[key] == option,
									onClick = { if (values[key] == option) values.remove(key) else values[key] = option },
									label = { Text(option) },
								)
						}
						Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
					}
					else -> {
						// Arguments that carry source text get a tall monospace field.
						val longText = key in setOf("code", "source", "text", "detail", "mappings")
						OutlinedTextField(
							value = values[key] ?: "",
							onValueChange = { values[key] = it },
							modifier = Modifier.fillMaxWidth().then(if (longText) Modifier.heightIn(min = 160.dp) else Modifier),
							label = { Text(label) },
							supportingText = { Text(if (type == "array") "$description One per line." else description) },
							singleLine = !longText && type != "array",
							textStyle = if (longText) TextStyle(fontFamily = Mono, fontSize = 12.sp) else TextStyle.Default,
							keyboardOptions = if (type == "integer") KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
						)
					}
				}
			}
			Button(enabled = !running, onClick = {
				val arguments = JsonObject()
				for ((key, element) in properties.entrySet()) {
					val type = element.asJsonObject.get("type")?.asString ?: "string"
					val text = values[key]
					when {
						type == "boolean" -> if (flags.containsKey(key)) arguments.addProperty(key, flags[key])
						text.isNullOrEmpty() -> {}
						type == "integer" -> text.trim().toLongOrNull()?.let { arguments.addProperty(key, it) } ?: arguments.addProperty(key, text)
						type == "array" -> arguments.add(key, JsonArray().apply { text.lines().filter { it.isNotBlank() }.forEach { add(it.trim()) } })
						else -> arguments.addProperty(key, text)
					}
				}
				running = true
				scope.launch {
					val outcome = withContext(RecafSession.work) { RecafSession.callTool(name, arguments) }
					failed = outcome.error()
					result = outcome.text()
					running = false
				}
			}) { Text("Run") }
			if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
			result?.let {
				SelectionContainer {
					Text(
						it,
						Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
						fontFamily = Mono,
						fontSize = 12.sp,
						lineHeight = 17.sp,
						color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
					)
				}
			}
		}
	}
}

/** What the agent recorded about the program so far, most severe first. */
@Composable
fun FindingsScreen(navigator: Navigator) {
	val findings by RecafSession.findings.collectAsStateWithLifecycle()
	val sorted = remember(findings) { findings.sortedWith(compareByDescending<Finding> { it.severity() }.thenBy { it.id() }) }
	ScreenFrame("Findings", navigator, actions = {
		if (findings.isNotEmpty())
			TextButton(onClick = { RecafSession.toolbox.context().state().clearFindings() }) { Text("Clear") }
	}) { padding ->
		if (sorted.isEmpty()) {
			EmptyNote("Nothing recorded yet. The agent adds findings here as it establishes them.", Modifier.padding(padding))
			return@ScreenFrame
		}
		LazyColumn(
			Modifier.fillMaxSize().padding(padding),
			contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
			verticalArrangement = Arrangement.spacedBy(10.dp),
		) {
			items(sorted, key = { it.id() }) { finding ->
				val className = finding.className()
				Card(
					colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
					modifier = Modifier.fillMaxWidth().then(
						if (className != null) Modifier.clickable { navigator.go(Screen.ClassView(className)) } else Modifier
					),
				) {
					Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
						Row(verticalAlignment = Alignment.CenterVertically) {
							Text(
								finding.severity().name,
								style = MaterialTheme.typography.labelSmall,
								fontWeight = FontWeight.Bold,
								color = severityColor(finding.severity()),
							)
							Text("  " + finding.title(), style = MaterialTheme.typography.titleSmall)
						}
						if (className != null)
							Text(className + (finding.member()?.let { ".$it" } ?: ""), fontFamily = Mono, fontSize = 11.sp,
								color = MaterialTheme.colorScheme.primary)
						SelectionContainer { Text(finding.detail(), style = MaterialTheme.typography.bodySmall) }
					}
				}
			}
		}
	}
}

@Composable
private fun severityColor(severity: Finding.Severity): Color = when (severity) {
	Finding.Severity.CRITICAL, Finding.Severity.HIGH -> MaterialTheme.colorScheme.error
	Finding.Severity.MEDIUM -> MaterialTheme.colorScheme.tertiary
	else -> MaterialTheme.colorScheme.onSurfaceVariant
}
