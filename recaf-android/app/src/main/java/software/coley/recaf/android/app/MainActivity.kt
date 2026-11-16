package software.coley.recaf.android.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** A destination in the app. */
sealed interface Screen {
	data object Chat : Screen
	data object Browser : Screen
	data class ClassView(val name: String) : Screen
	data class FileView(val path: String) : Screen
	data object Tools : Screen
	data class ToolForm(val name: String) : Screen
	data object Findings : Screen
	data object Mcp : Screen
	data object Settings : Screen
	data object Apps : Screen
	data object Onboarding : Screen
}

/** Back stack of [Screen]s. The agent chat is always at the bottom. */
class Navigator {
	private val stack = mutableStateListOf<Screen>(Screen.Chat)

	/** Text to put into the chat's message field the next time it is shown. */
	var draft by mutableStateOf<String?>(null)

	val current: Screen get() = stack.last()
	val canGoBack: Boolean get() = stack.size > 1

	fun go(screen: Screen) {
		if (stack.last() != screen) stack.add(screen)
	}

	fun back() {
		if (stack.size > 1) stack.removeAt(stack.lastIndex)
	}

	/** Returns to the chat, optionally with a prepared message. */
	fun toChat(draft: String? = null) {
		while (stack.size > 1) stack.removeAt(stack.lastIndex)
		this.draft = draft
	}
}

class MainActivity : ComponentActivity() {
	private val navigator = Navigator()

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		enableEdgeToEdge()
		setContent {
			RecafTheme { App(navigator) }
		}
		if (savedInstanceState == null)
			handleIntent(intent)
	}

	override fun onNewIntent(intent: Intent) {
		super.onNewIntent(intent)
		handleIntent(intent)
	}

	/** Opens a file that was handed to the app through "Open with" or "Share". */
	private fun handleIntent(intent: Intent?) {
		val uri: Uri? = when (intent?.action) {
			Intent.ACTION_VIEW -> intent.data
			Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
			else -> null
		}
		if (uri != null)
			openWhenReady(uri)
	}

	private fun openWhenReady(uri: Uri) {
		lifecycleScope.launch {
			// Recaf may still be starting when the app is launched by an "Open with".
			if (RecafSession.init.first { it !is InitState.Loading } is InitState.Ready) {
				val result = RecafSession.openUri(uri)
				if (result.isSuccess) toast("Opened ${result.getOrNull()}")
				navigator.toChat()
			}
		}
	}
}

@Composable
private fun App(navigator: Navigator) {
	val agent: AgentViewModel = viewModel()
	val context = LocalContext.current
	val opening by RecafSession.opening.collectAsStateWithLifecycle()
	val openFailure by RecafSession.openFailure.collectAsStateWithLifecycle()
	var crashReport by remember { mutableStateOf(CrashLog.pendingReport()) }

	// The first start begins with setting up what the agent runs on.
	LaunchedEffect(Unit) {
		val settings = SettingsStore.settings.value
		if (!settings.onboarded && !settings.agentReady)
			navigator.go(Screen.Onboarding)
	}

	// A background task that failed is reported, the app keeps running.
	LaunchedEffect(Unit) {
		CrashLog.backgroundFailure.collect { failure ->
			if (failure != null)
				context.toast(
					if (failure.outOfMemory) "Recaf ran out of memory. The file may be too large for this device."
					else "A background task failed: ${failure.summary}"
				)
		}
	}

	BackHandler(enabled = navigator.canGoBack && opening == null && navigator.current != Screen.Onboarding) { navigator.back() }
	when (val screen = navigator.current) {
		Screen.Chat -> ChatScreen(navigator, agent)
		Screen.Browser -> BrowserScreen(navigator)
		is Screen.ClassView -> ClassScreen(navigator, screen.name)
		is Screen.FileView -> FileScreen(navigator, screen.path)
		Screen.Tools -> ToolsScreen(navigator)
		is Screen.ToolForm -> ToolFormScreen(navigator, screen.name)
		Screen.Findings -> FindingsScreen(navigator)
		Screen.Mcp -> McpScreen(navigator)
		Screen.Settings -> SettingsScreen(navigator)
		Screen.Apps -> AppsScreen(navigator)
		Screen.Onboarding -> OnboardingScreen(navigator)
	}

	// Opening a file can take a while and needs most of the app's memory, so nothing else goes on meanwhile.
	opening?.let { name ->
		Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)) {
			Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
				Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
					CircularProgressIndicator()
					Text("Opening $name", Modifier.padding(top = 16.dp), style = MaterialTheme.typography.titleMedium,
						textAlign = TextAlign.Center)
					Text("Large apps take a minute or more.", Modifier.padding(top = 4.dp),
						style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
				}
			}
		}
	}

	openFailure?.let { reason ->
		AlertDialog(
			onDismissRequest = { RecafSession.openFailure.value = null },
			title = { Text("The file could not be opened") },
			text = { Text(reason) },
			confirmButton = { TextButton(onClick = { RecafSession.openFailure.value = null }) { Text("OK") } },
		)
	}

	crashReport?.let { report ->
		AlertDialog(
			onDismissRequest = {},
			title = { Text("Recaf closed unexpectedly") },
			text = {
				Column {
					Text("This is what happened the last time. The report stays on this device unless you copy or share it.",
						style = MaterialTheme.typography.bodyMedium)
					Text(
						report.lineSequence().take(14).joinToString("\n"),
						Modifier.padding(top = 12.dp).heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
						fontFamily = Mono, fontSize = 11.sp, lineHeight = 15.sp,
					)
				}
			},
			confirmButton = {
				TextButton(onClick = { context.copyToClipboard("Recaf crash report", report) }) { Text("Copy report") }
			},
			dismissButton = {
				TextButton(onClick = {
					CrashLog.dismissPendingReport()
					crashReport = null
				}) { Text("Dismiss") }
			},
		)
	}
}

fun Context.toast(text: String) {
	Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}

fun Context.copyToClipboard(label: String, text: String) {
	getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
	toast("Copied")
}
