package software.coley.recaf.android.app

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Lists the classes and files of the open workspace, with a filter. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(navigator: Navigator) {
	val workspace by RecafSession.workspace.collectAsStateWithLifecycle()
	var tab by rememberSaveable { mutableIntStateOf(0) }
	var filter by rememberSaveable { mutableStateOf("") }
	var classes by remember { mutableStateOf<List<String>?>(null) }
	var files by remember { mutableStateOf<List<String>?>(null) }
	LaunchedEffect(workspace) {
		classes = RecafSession.classNames()
		files = RecafSession.fileNames()
	}

	ScreenFrame(workspace?.name ?: "Workspace", navigator) { padding ->
		Column(Modifier.fillMaxSize().padding(padding)) {
			PrimaryTabRow(selectedTabIndex = tab) {
				Tab(tab == 0, onClick = { tab = 0 }, text = { Text("Classes (${classes?.size ?: 0})") })
				Tab(tab == 1, onClick = { tab = 1 }, text = { Text("Files (${files?.size ?: 0})") })
			}
			OutlinedTextField(
				value = filter,
				onValueChange = { filter = it },
				modifier = Modifier.fillMaxWidth().padding(12.dp),
				placeholder = { Text("Filter by name") },
				leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
				singleLine = true,
			)
			val all = if (tab == 0) classes else files
			if (all == null) {
				Loading()
			} else {
				val shown = remember(all, filter) {
					if (filter.isBlank()) all else all.filter { it.contains(filter.trim(), ignoreCase = true) }
				}
				if (shown.isEmpty()) EmptyNote(if (all.isEmpty()) "Nothing here." else "No names match the filter.")
				LazyColumn(Modifier.fillMaxSize()) {
					items(shown, key = { it }) { name ->
						val simple = name.substringAfterLast('/')
						val parent = name.substringBeforeLast('/', "")
						Column(
							Modifier.fillMaxWidth()
								.clickable { navigator.go(if (tab == 0) Screen.ClassView(name) else Screen.FileView(name)) }
								.padding(horizontal = 16.dp, vertical = 10.dp)
						) {
							Text(simple, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
							if (parent.isNotEmpty())
								Text(parent, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
									maxLines = 1, overflow = TextOverflow.StartEllipsis)
						}
						HorizontalDivider()
					}
				}
			}
		}
	}
}

/** Shows one class: decompiled source, assembler listing that can be edited and assembled back, and structure. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClassScreen(navigator: Navigator, name: String) {
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	val workspace by RecafSession.workspace.collectAsStateWithLifecycle()
	var tab by rememberSaveable { mutableIntStateOf(0) }
	var decompiler by rememberSaveable { mutableStateOf<String?>(null) }
	var content by remember { mutableStateOf<String?>(null) }
	var failed by remember { mutableStateOf(false) }
	var editing by remember { mutableStateOf<String?>(null) }

	// Reload when the tab, the decompiler or the workspace content changes.
	LaunchedEffect(name, tab, decompiler, workspace?.revision) {
		content = null
		val result = when (tab) {
			0 -> RecafSession.tool("decompile_class", "class" to name, "decompiler" to decompiler, "max_lines" to 2000)
			1 -> RecafSession.tool("disassemble_class", "class" to name, "max_lines" to 2000)
			else -> RecafSession.tool("get_class_info", "class" to name)
		}
		failed = result.error()
		content = result.text()
	}

	ScreenFrame(
		title = name.substringAfterLast('/'),
		subtitle = name.substringBeforeLast('/', ""),
		navigator = navigator,
		actions = {
			if (tab == 1 && editing == null && content != null && !failed)
				IconButton(onClick = { editing = content }) { Icon(Icons.Default.Edit, contentDescription = "Edit bytecode") }
			TextButton(onClick = { navigator.toChat("Explain what the class `$name` does.") }) { Text("Ask agent") }
		},
	) { padding ->
		Column(Modifier.fillMaxSize().padding(padding)) {
			val draft = editing
			if (draft != null) {
				// Editing the assembler listing. Assembling replaces the class in the workspace.
				Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					Button(onClick = {
						scope.launch {
							val result = RecafSession.tool("assemble_class", "source" to draft)
							context.toast(result.text().lineSequence().first().take(200))
							if (!result.error()) editing = null
						}
					}) { Text("Assemble") }
					OutlinedButton(onClick = { editing = null }) { Text("Discard") }
				}
				OutlinedTextField(
					value = draft,
					onValueChange = { editing = it },
					modifier = Modifier.fillMaxSize().imePadding().padding(horizontal = 8.dp),
					textStyle = TextStyle(fontFamily = Mono, fontSize = 12.sp, lineHeight = 17.sp),
				)
				return@Column
			}
			PrimaryTabRow(selectedTabIndex = tab) {
				Tab(tab == 0, onClick = { tab = 0 }, text = { Text("Source") })
				Tab(tab == 1, onClick = { tab = 1 }, text = { Text("Bytecode") })
				Tab(tab == 2, onClick = { tab = 2 }, text = { Text("Structure") })
			}
			if (tab == 0) {
				Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
					horizontalArrangement = Arrangement.spacedBy(8.dp)) {
					for ((label, value) in listOf("Default" to null, "CFR" to "CFR", "Procyon" to "Procyon", "Vineflower" to "Vineflower"))
						FilterChip(selected = decompiler == value, onClick = { decompiler = value }, label = { Text(label) })
				}
			}
			val text = content
			when {
				text == null -> Loading()
				failed -> EmptyNote(text)
				else -> CodeView(text, Modifier.fillMaxSize(), highlighted = tab != 2)
			}
		}
	}
}

/** Shows one file of the workspace as text, decoded XML or a hex dump, whichever fits its content. */
@Composable
fun FileScreen(navigator: Navigator, path: String) {
	val workspace by RecafSession.workspace.collectAsStateWithLifecycle()
	var content by remember { mutableStateOf<String?>(null) }
	LaunchedEffect(path, workspace?.revision) {
		content = RecafSession.tool("read_file", "path" to path, "max_lines" to 2000).text()
	}
	ScreenFrame(path.substringAfterLast('/'), navigator, subtitle = path.substringBeforeLast('/', "")) { padding ->
		val text = content
		if (text == null) Loading(Modifier.padding(padding))
		else CodeView(text, Modifier.fillMaxSize().padding(padding), highlighted = false)
	}
}

private data class InstalledApp(val label: String, val packageName: String, val apk: String)

/** Lists the launchable apps on the device so that one of their APKs can be opened for analysis. */
@Composable
fun AppsScreen(navigator: Navigator) {
	val context = LocalContext.current
	val scope = rememberCoroutineScope()
	val agent: AgentViewModel = viewModel()
	var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
	var filter by rememberSaveable { mutableStateOf("") }
	var opening by remember { mutableStateOf<String?>(null) }
	LaunchedEffect(Unit) {
		apps = withContext(Dispatchers.IO) {
			val manager = context.packageManager
			val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
			manager.queryIntentActivities(launcher, 0)
				.map { it.activityInfo.applicationInfo }
				.distinctBy { it.packageName }
				.filter { it.packageName != context.packageName }
				.map { InstalledApp(manager.getApplicationLabel(it).toString(), it.packageName, it.sourceDir) }
				.sortedBy { it.label.lowercase() }
		}
	}

	ScreenFrame("Open an installed app", navigator) { padding ->
		Column(Modifier.fillMaxSize().padding(padding)) {
			OutlinedTextField(
				value = filter,
				onValueChange = { filter = it },
				modifier = Modifier.fillMaxWidth().padding(12.dp),
				placeholder = { Text("Filter by name or package") },
				leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
				singleLine = true,
			)
			val all = apps
			if (all == null || opening != null) {
				if (opening != null)
					Text("Importing $opening…", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
				Loading()
				return@Column
			}
			val shown = remember(all, filter) {
				all.filter { it.label.contains(filter.trim(), true) || it.packageName.contains(filter.trim(), true) }
			}
			LazyColumn(Modifier.fillMaxSize()) {
				items(shown, key = { it.packageName }) { app ->
					Column(
						Modifier.fillMaxWidth().clickable {
							opening = app.label
							scope.launch {
								// Only the base APK is opened. Code delivered in split APKs is not included.
								val result = RecafSession.openCopyOf(File(app.apk), app.packageName + ".apk")
								opening = null
								result.onSuccess {
									agent.newConversation()
									navigator.toChat()
								}
								if (result.isSuccess) context.toast("Opened ${app.label}")
							}
						}.padding(horizontal = 16.dp, vertical = 10.dp)
					) {
						Text(app.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
						Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
					}
					HorizontalDivider()
				}
			}
		}
	}
}
