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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
				toast(result.fold({ "Opened $it" }, { "Could not open the file: ${it.message}" }))
				navigator.toChat()
			}
		}
	}
}

@Composable
private fun App(navigator: Navigator) {
	val agent: AgentViewModel = viewModel()
	BackHandler(enabled = navigator.canGoBack) { navigator.back() }
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
	}
}

fun Context.toast(text: String) {
	Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}

fun Context.copyToClipboard(label: String, text: String) {
	getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
	toast("Copied")
}
