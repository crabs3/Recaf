package software.coley.recaf.android.app

import android.content.Context
import android.os.Build
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import software.coley.recaf.RecafBuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps failures from taking the app down silently.
 *
 * Android ends the whole process when any thread lets an exception escape. Recaf's services run a lot of work on
 * background threads, where a desktop JVM would log such a failure and carry on. This does the same: a failure on
 * a background thread is recorded and reported in the interface while the app keeps running. A failure on the main
 * thread cannot be survived, so its details are written down and offered the next time the app starts.
 *
 * Reports stay on the device. They leave it only when the user copies or shares one.
 */
object CrashLog {
	private const val MAX_BACKGROUND_LOG = 64 * 1024
	private var directory: File? = null
	private val backgroundFlow = MutableStateFlow<BackgroundFailure?>(null)

	/** A failure of a background thread that the app survived. */
	data class BackgroundFailure(val summary: String, val outOfMemory: Boolean, val time: Long)

	/** The most recent failure of a background thread. */
	val backgroundFailure: StateFlow<BackgroundFailure?> get() = backgroundFlow

	fun install(context: Context) {
		directory = File(context.filesDir, "crash").apply { mkdirs() }
		val previous = Thread.getDefaultUncaughtExceptionHandler()
		Thread.setDefaultUncaughtExceptionHandler { thread, error ->
			val onMain = thread === Looper.getMainLooper().thread
			// Whatever happens while recording must not mask the original failure.
			runCatching { record(thread, error, fatal = onMain) }
			if (onMain) {
				previous?.uncaughtException(thread, error)
			} else {
				val outOfMemory = generateSequence(error) { it.cause }.any { it is OutOfMemoryError }
				backgroundFlow.value = BackgroundFailure(summarize(error), outOfMemory, System.currentTimeMillis())
			}
		}
	}

	/** Records a failure that was caught, so that it shows up in the diagnostics report. */
	fun note(what: String, error: Throwable) {
		runCatching {
			val file = File(directory ?: return, "background.txt")
			append(file, "$what\n" + describe(Thread.currentThread(), error))
		}
	}

	/** @return The report of a crash that ended the app the last time, if there is one that was not dismissed. */
	fun pendingReport(): String? = runCatching { File(directory, "fatal.txt").takeIf { it.isFile }?.readText() }.getOrNull()

	fun dismissPendingReport() {
		runCatching { File(directory, "fatal.txt").delete() }
	}

	/** @return Everything recorded about failures the app survived, newest last. Empty when there were none. */
	fun diagnostics(): String = header() + "\n" +
		(runCatching { File(directory, "background.txt").takeIf { it.isFile }?.readText() }.getOrNull() ?: "No failures recorded.")

	private fun record(thread: Thread, error: Throwable, fatal: Boolean) {
		val directory = directory ?: return
		val report = describe(thread, error)
		if (fatal)
			File(directory, "fatal.txt").writeText(header() + "\n" + report)
		else
			append(File(directory, "background.txt"), report)
	}

	private fun append(file: File, entry: String) {
		val existing = if (file.isFile) file.readText() else ""
		// Keep the newest entries when the log grows.
		file.writeText((existing + entry + "\n").takeLast(MAX_BACKGROUND_LOG))
	}

	private fun header(): String {
		val runtime = Runtime.getRuntime()
		val megabyte = 1024 * 1024
		return "Recaf ${RecafBuildConfig.VERSION} for Android\n" +
			"Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
			"Memory: ${(runtime.totalMemory() - runtime.freeMemory()) / megabyte} MB used of ${runtime.maxMemory() / megabyte} MB allowed\n"
	}

	private fun describe(thread: Thread, error: Throwable): String {
		val trace = StringWriter()
		error.printStackTrace(PrintWriter(trace))
		val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
		return "--- $time, thread '${thread.name}'\n$trace"
	}

	private fun summarize(error: Throwable): String {
		val root = generateSequence(error) { it.cause }.last()
		val message = root.message?.lineSequence()?.firstOrNull()?.take(160)
		return root.javaClass.simpleName + if (message.isNullOrBlank()) "" else ": $message"
	}
}
