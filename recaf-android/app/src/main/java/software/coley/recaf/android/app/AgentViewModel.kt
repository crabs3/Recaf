package software.coley.recaf.android.app

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import software.coley.recaf.agent.claude.AgentConfig
import software.coley.recaf.agent.claude.AgentListener
import software.coley.recaf.agent.claude.AgentTurn
import software.coley.recaf.agent.claude.ClaudeAgent
import software.coley.recaf.agent.claude.ToolApprover
import software.coley.recaf.agent.tool.AgentTool
import software.coley.recaf.agent.tool.ToolArgs
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicLong

/** One entry of the conversation as shown on screen. */
sealed class ChatItem {
	val id: Long = nextId.incrementAndGet()

	/** A message the user sent. */
	class User(val text: String) : ChatItem()

	/** Text the agent wrote, growing while it streams in. */
	class Assistant : ChatItem() {
		var text by mutableStateOf("")
	}

	/** Summary of the agent's reasoning or a progress note. */
	class Thinking : ChatItem() {
		var text by mutableStateOf("")
	}

	/** A tool call the agent made. */
	class Tool(val callId: String, val name: String) : ChatItem() {
		var arguments by mutableStateOf<String?>(null)
		var result by mutableStateOf<String?>(null)
		var error by mutableStateOf(false)
		var durationMillis by mutableStateOf(0L)
		val finished: Boolean get() = result != null
	}

	/** Something the app tells the user about the run: an error, a limit, a fallback. */
	class Notice(val text: String, val error: Boolean, val canResume: Boolean) : ChatItem()

	private companion object {
		val nextId = AtomicLong()
	}
}

/** A modifying tool call waiting for the user's decision. */
class ApprovalRequest(val tool: AgentTool, val arguments: ToolArgs, val decision: CompletableFuture<ToolApprover.Decision>)

/**
 * Holds the conversation with the agent and runs it off the main thread.
 *
 * The agent reports from its own thread. Everything it reports is forwarded to the main thread before touching
 * state that the interface observes.
 */
class AgentViewModel : ViewModel() {
	private val main = Handler(Looper.getMainLooper())
	private var agent: ClaudeAgent? = null
	private var agentSettings: Settings? = null
	private var allowChangesForConversation = false

	val items = mutableStateListOf<ChatItem>()
	var running by mutableStateOf(false)
		private set
	var usage by mutableStateOf<AgentListener.Usage?>(null)
		private set
	var pendingApproval by mutableStateOf<ApprovalRequest?>(null)
		private set

	private val listener = object : AgentListener {
		override fun onText(text: String) = onMain {
			val last = items.lastOrNull()
			val target = if (last is ChatItem.Assistant) last else ChatItem.Assistant().also { items.add(it) }
			target.text += text
		}

		override fun onThinking(text: String) = onMain {
			val last = items.lastOrNull()
			val target = if (last is ChatItem.Thinking) last else ChatItem.Thinking().also { items.add(it) }
			target.text += text
		}

		override fun onToolCallStarted(callId: String, toolName: String) = onMain {
			items.add(ChatItem.Tool(callId, toolName))
		}

		override fun onToolCallReady(callId: String, toolName: String, argumentsJson: String) = onMain {
			findTool(callId)?.arguments = argumentsJson
		}

		override fun onToolCallFinished(callId: String, result: String, error: Boolean, durationMillis: Long) = onMain {
			findTool(callId)?.let {
				it.error = error
				it.durationMillis = durationMillis
				it.result = result
			}
		}

		override fun onUsage(usage: AgentListener.Usage) = onMain { this@AgentViewModel.usage = usage }

		override fun onModelFallback(fromModel: String, toModel: String) = onMain {
			items.add(ChatItem.Notice("$fromModel declined this request, $toModel answered instead.", error = false, canResume = false))
		}
	}

	private val approver = ToolApprover { tool, args ->
		if (allowChangesForConversation)
			return@ToolApprover ToolApprover.Decision.ALLOW
		val request = ApprovalRequest(tool, args, CompletableFuture())
		onMain { pendingApproval = request }
		// The agent's thread waits here for as long as the user takes.
		val decision = runCatching { request.decision.get() }.getOrDefault(ToolApprover.Decision.DENY)
		onMain { if (pendingApproval === request) pendingApproval = null }
		decision
	}

	/** Sends a message to the agent and lets it work. */
	fun send(text: String) {
		if (running || text.isBlank()) return
		items.add(ChatItem.User(text.trim()))
		run { it.send(text.trim()) }
	}

	/** Continues a run that was stopped, failed or hit a limit. */
	fun resume() {
		if (running) return
		run { it.resume() }
	}

	/** Stops the agent. */
	fun stop() {
		agent?.cancel()
		pendingApproval?.decision?.complete(ToolApprover.Decision.DENY)
	}

	/** Answers the pending approval request. */
	fun decide(allowed: Boolean, forConversation: Boolean = false) {
		val request = pendingApproval ?: return
		if (allowed && forConversation)
			allowChangesForConversation = true
		pendingApproval = null
		request.decision.complete(if (allowed) ToolApprover.Decision.ALLOW else ToolApprover.Decision.DENY)
	}

	/** Discards the conversation. */
	fun newConversation() {
		if (running) return
		agent = null
		agentSettings = null
		allowChangesForConversation = false
		items.clear()
		usage = null
	}

	private fun run(action: (ClaudeAgent) -> AgentTurn) {
		val settings = SettingsStore.settings.value
		if (settings.apiKey.isBlank()) {
			items.add(ChatItem.Notice("Add your Anthropic API key in Settings to use the agent.", error = true, canResume = false))
			return
		}
		running = true
		viewModelScope.launch {
			val turn = withContext(Dispatchers.IO) {
				runCatching { action(agentFor(settings)) }
					.getOrElse { AgentTurn(AgentTurn.Outcome.ERROR, it.toString()) }
			}
			running = false
			describe(turn)?.let { items.add(it) }
		}
	}

	/**
	 * @return The agent for the current settings. When settings that the agent is built with change mid
	 * conversation, a new agent takes over the conversation so far.
	 */
	private fun agentFor(settings: Settings): ClaudeAgent {
		val current = agent
		if (current != null && agentSettings?.agentConfig() == settings.agentConfig())
			return current
		val replacement = ClaudeAgent(RecafSession.toolbox, settings.agentConfig(), listener, approver)
		if (current != null)
			replacement.importConversation(current.exportConversation())
		agent = replacement
		agentSettings = settings
		return replacement
	}

	private fun describe(turn: AgentTurn): ChatItem.Notice? {
		val detail = turn.detail()
		return when (turn.outcome()) {
			AgentTurn.Outcome.COMPLETED -> null
			AgentTurn.Outcome.CANCELLED -> ChatItem.Notice("Stopped.", error = false, canResume = true)
			AgentTurn.Outcome.TRUNCATED, AgentTurn.Outcome.STEP_LIMIT ->
				ChatItem.Notice(detail ?: "The agent stopped early.", error = false, canResume = true)
			AgentTurn.Outcome.REFUSED -> ChatItem.Notice(detail ?: "The request was declined.", error = true, canResume = false)
			AgentTurn.Outcome.AUTH_ERROR ->
				ChatItem.Notice((detail ?: "The API key was not accepted.") + " Check it in Settings.", error = true, canResume = true)
			else -> ChatItem.Notice(detail ?: "The agent failed.", error = true, canResume = turn.outcome().canResume())
		}
	}

	private fun findTool(callId: String): ChatItem.Tool? =
		items.lastOrNull { it is ChatItem.Tool && it.callId == callId } as? ChatItem.Tool

	private fun onMain(action: () -> Unit) {
		if (Looper.myLooper() == Looper.getMainLooper()) action() else main.post(action)
	}

	override fun onCleared() {
		agent?.cancel()
		super.onCleared()
	}

	/** @return The approval mode the agent runs with, for display. */
	fun approvalMode(): AgentConfig.ApprovalMode = SettingsStore.settings.value.approvalMode
}
