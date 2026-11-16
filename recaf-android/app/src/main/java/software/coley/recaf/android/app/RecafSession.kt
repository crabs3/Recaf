package software.coley.recaf.android.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.gson.JsonObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import software.coley.recaf.Bootstrap
import software.coley.recaf.Recaf
import software.coley.recaf.agent.mcp.McpHttpServer
import software.coley.recaf.agent.mcp.McpServer
import software.coley.recaf.agent.state.Finding
import software.coley.recaf.agent.tool.AgentTool
import software.coley.recaf.agent.tool.AgentToolbox
import software.coley.recaf.agent.tool.ToolArgs
import software.coley.recaf.agent.tool.ToolCallListener
import software.coley.recaf.agent.tool.ToolResult
import software.coley.recaf.agent.tool.support.Workspaces
import software.coley.recaf.services.workspace.WorkspaceManager
import software.coley.recaf.services.workspace.io.ResourceImporter
import software.coley.recaf.workspace.model.BasicWorkspace
import software.coley.recaf.workspace.model.Workspace
import java.io.File

/** State of loading Recaf's services. */
sealed interface InitState {
	data object Loading : InitState
	data object Ready : InitState
	data class Failed(val message: String) : InitState
}

/**
 * What the interface needs to know about the open workspace.
 *
 * @property name File name of the opened input.
 * @property classCount Number of classes.
 * @property fileCount Number of files.
 * @property android Whether the input is an Android application.
 * @property revision Increases whenever the workspace content was modified, so that views can refresh.
 */
data class WorkspaceState(
	val name: String,
	val classCount: Int,
	val fileCount: Int,
	val android: Boolean,
	val revision: Int = 0,
)

/** State of the MCP server. */
data class McpState(val running: Boolean = false, val port: Int = -1, val network: Boolean = false, val error: String? = null)

/** A tool call that was made through the toolbox, for the activity log. */
data class ToolActivity(val time: Long, val tool: String, val arguments: String, val error: Boolean, val durationMillis: Long)

/**
 * The single Recaf instance of the app and everything built on it: the open workspace, the agent toolbox and the
 * MCP server. Lives as long as the process, independent of any screen.
 */
object RecafSession {
	private const val MAX_ACTIVITY = 200
	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
	private lateinit var appContext: Context
	private lateinit var recaf: Recaf
	private var mcpServer: McpHttpServer? = null

	private val initFlow = MutableStateFlow<InitState>(InitState.Loading)
	private val workspaceFlow = MutableStateFlow<WorkspaceState?>(null)
	private val findingsFlow = MutableStateFlow<List<Finding>>(emptyList())
	private val mcpFlow = MutableStateFlow(McpState())
	private val activityFlow = MutableStateFlow<List<ToolActivity>>(emptyList())

	val init: StateFlow<InitState> get() = initFlow
	val workspace: StateFlow<WorkspaceState?> get() = workspaceFlow
	val findings: StateFlow<List<Finding>> get() = findingsFlow
	val mcp: StateFlow<McpState> get() = mcpFlow
	val activity: StateFlow<List<ToolActivity>> get() = activityFlow

	/** The agent toolbox. Only valid once [init] is [InitState.Ready]. */
	lateinit var toolbox: AgentToolbox
		private set

	private val inputsDir: File get() = File(appContext.filesDir, "inputs").apply { mkdirs() }
	private val exportsDir: File get() = File(appContext.filesDir, "exports").apply { mkdirs() }

	fun start(context: Context) {
		appContext = context.applicationContext
		scope.launch(Dispatchers.IO) {
			try {
				// Recaf keeps its configuration and temporary files below this directory.
				System.setProperty("RECAF_DIR", File(appContext.filesDir, "recaf").apply { mkdirs() }.absolutePath)
				recaf = Bootstrap.get()
				Bootstrap.initialize()
				toolbox = AgentToolbox.create(recaf, exportsDir.toPath(), listOf(inputsDir.toPath()))

				val manager = recaf.get(WorkspaceManager::class.java)
				manager.addWorkspaceOpenListener { opened -> publishWorkspace(opened, 0) }
				manager.addWorkspaceCloseListener { workspaceFlow.value = null }
				toolbox.context().state().addFindingsListener { findingsFlow.value = it }
				toolbox.addListener(object : ToolCallListener {
					override fun onToolResult(tool: AgentTool, args: ToolArgs, result: ToolResult, durationMillis: Long) {
						val entry = ToolActivity(System.currentTimeMillis(), tool.name(), args.toString(), result.error(), durationMillis)
						activityFlow.value = (listOf(entry) + activityFlow.value).take(MAX_ACTIVITY)
						// Anything that may have changed the workspace invalidates what views are showing.
						if (!tool.readOnly() && !result.error())
							refreshWorkspace()
					}
				})
				initFlow.value = InitState.Ready
			} catch (t: Throwable) {
				initFlow.value = InitState.Failed(generateSequence(t) { it.cause }.last().toString())
			}
		}
	}

	/**
	 * Copies the content behind a URI into the app's storage and opens it as the workspace.
	 * Recaf works on files, and a granted content URI may stop being readable once the app is restarted.
	 */
	suspend fun openUri(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
		runCatching {
			val name = displayName(uri)
			val target = File(inputsDir, name)
			appContext.contentResolver.openInputStream(uri).use { input ->
				requireNotNull(input) { "The file could not be read" }
				target.outputStream().use { output -> input.copyTo(output) }
			}
			open(target)
		}
	}

	/** Copies a file, such as the APK of an installed app, into the app's storage and opens it. */
	suspend fun openCopyOf(source: File, name: String): Result<String> = withContext(Dispatchers.IO) {
		runCatching {
			val target = File(inputsDir, name)
			source.inputStream().use { input -> target.outputStream().use { output -> input.copyTo(output) } }
			open(target)
		}
	}

	private fun open(file: File): String {
		val resource = recaf.get(ResourceImporter::class.java).importResource(file.toPath())
		val opened = BasicWorkspace(resource)
		recaf.get(WorkspaceManager::class.java).setCurrentIgnoringConditions(opened)
		toolbox.context().state().clearFindings()
		toolbox.context().state().setExported(false)
		return file.name
	}

	/** Calls a tool. Blocks, so call it off the main thread. */
	fun callTool(name: String, arguments: JsonObject): ToolResult = toolbox.call(name, arguments)

	/** Calls a tool with string arguments on a background thread. */
	suspend fun tool(name: String, vararg arguments: Pair<String, Any?>): ToolResult = withContext(Dispatchers.IO) {
		val json = JsonObject()
		for ((key, value) in arguments) {
			when (value) {
				null -> {}
				is Number -> json.addProperty(key, value)
				is Boolean -> json.addProperty(key, value)
				else -> json.addProperty(key, value.toString())
			}
		}
		toolbox.call(name, json)
	}

	/** @return Internal names of all classes of the open workspace, sorted. */
	suspend fun classNames(): List<String> = withContext(Dispatchers.IO) {
		val current = toolbox.context().workspaceOrNull() ?: return@withContext emptyList()
		Workspaces.classes(current).map { it.value.name }.sorted().toList()
	}

	/** @return Paths of all files of the open workspace, sorted. */
	suspend fun fileNames(): List<String> = withContext(Dispatchers.IO) {
		val current = toolbox.context().workspaceOrNull() ?: return@withContext emptyList()
		Workspaces.files(current).map { it.value.name }.sorted().toList()
	}

	/**
	 * Exports the workspace, with all modifications, to a document the user picked.
	 *
	 * @return Summary of what was written.
	 */
	suspend fun exportTo(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
		runCatching {
			val name = "export-" + System.currentTimeMillis() + "-" + (workspaceFlow.value?.name ?: "workspace")
			val result = toolbox.call("export_workspace", JsonObject().apply {
				addProperty("file_name", name)
				addProperty("overwrite", true)
			})
			check(!result.error()) { result.text() }
			val exported = File(exportsDir, name)
			appContext.contentResolver.openOutputStream(uri, "wt").use { output ->
				requireNotNull(output) { "The destination could not be written" }
				exported.inputStream().use { it.copyTo(output) }
			}
			val size = exported.length()
			exported.delete()
			"Exported " + (size / 1024) + " KB"
		}
	}

	fun startMcp(settings: Settings) {
		stopMcp()
		try {
			val server = McpHttpServer(McpServer(toolbox), settings.mcpToken)
			server.start(settings.mcpPort, !settings.mcpNetwork)
			mcpServer = server
			mcpFlow.value = McpState(true, server.port, settings.mcpNetwork)
		} catch (t: Throwable) {
			mcpFlow.value = McpState(error = t.message ?: t.toString())
		}
	}

	fun stopMcp() {
		mcpServer?.close()
		mcpServer = null
		mcpFlow.value = McpState()
	}

	private fun refreshWorkspace() {
		val current = toolbox.context().workspaceOrNull() ?: return
		publishWorkspace(current, (workspaceFlow.value?.revision ?: 0) + 1)
	}

	private fun publishWorkspace(opened: Workspace, revision: Int) {
		val name = workspaceFlow.value?.takeIf { revision > 0 }?.name ?: inputName(opened)
		workspaceFlow.value = WorkspaceState(
			name = name,
			classCount = Workspaces.classes(opened).count().toInt(),
			fileCount = Workspaces.files(opened).count().toInt(),
			android = opened.findFile("AndroidManifest.xml") != null,
			revision = revision,
		)
	}

	private fun inputName(opened: Workspace): String {
		val resource = opened.primaryResource
		if (resource is software.coley.recaf.workspace.model.resource.WorkspaceFileResource) {
			val info = resource.fileInfo
			val path = software.coley.recaf.info.properties.builtin.InputFilePathProperty.get(info)
			return path?.fileName?.toString() ?: info.name
		}
		return "workspace"
	}

	private fun displayName(uri: Uri): String {
		var name: String? = null
		if (uri.scheme == "content") {
			appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
				if (cursor.moveToFirst()) name = cursor.getString(0)
			}
		}
		name = name ?: uri.lastPathSegment ?: "input"
		// Keep only a plain file name, the input ends up as a file in the app's storage.
		return name!!.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_").trimStart('.').ifEmpty { "input" }
	}
}
