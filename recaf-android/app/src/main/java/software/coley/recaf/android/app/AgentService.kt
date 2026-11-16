package software.coley.recaf.android.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder

/**
 * Foreground service that exists for as long as the agent is working.
 *
 * An analysis takes minutes, and people switch to other apps while it runs. Android freezes apps that are not in
 * front, which would stall the agent in the middle of a request. A foreground service keeps the process running,
 * and its notification offers a way to stop the agent without coming back to the app.
 */
class AgentService : Service() {
	override fun onBind(intent: Intent?): IBinder? = null

	override fun onCreate() {
		super.onCreate()
		instance = this
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		if (intent?.action == ACTION_STOP) {
			// The run ends in response, which then removes this service.
			stopRequest?.invoke()
			return START_NOT_STICKY
		}
		// A service started as a foreground service must become one, even if there is nothing left to do.
		startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
		if (!working)
			finish()
		return START_NOT_STICKY
	}

	override fun onDestroy() {
		instance = null
		super.onDestroy()
	}

	private fun finish() {
		stopForeground(STOP_FOREGROUND_REMOVE)
		stopSelf()
	}

	private fun notification(): Notification {
		val manager = getSystemService(NotificationManager::class.java)
		if (manager.getNotificationChannel(CHANNEL_ID) == null) {
			manager.createNotificationChannel(
				NotificationChannel(CHANNEL_ID, getString(R.string.agent_channel_name), NotificationManager.IMPORTANCE_LOW)
					.apply { description = getString(R.string.agent_channel_description) }
			)
		}
		val open = PendingIntent.getActivity(
			this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
		)
		val stop = PendingIntent.getService(
			this, 3, Intent(this, AgentService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
		)
		return Notification.Builder(this, CHANNEL_ID)
			.setSmallIcon(R.drawable.ic_launcher_foreground)
			.setContentTitle("The Recaf agent is working")
			.setContentText("Open Recaf to follow along or to answer its questions.")
			.setContentIntent(open)
			.setOngoing(true)
			.addAction(Notification.Action.Builder(null, "Stop", stop).build())
			.build()
	}

	companion object {
		private const val CHANNEL_ID = "agent"
		private const val NOTIFICATION_ID = 2
		private const val ACTION_STOP = "software.coley.recaf.android.app.STOP_AGENT"

		@Volatile
		private var instance: AgentService? = null

		@Volatile
		private var working = false

		@Volatile
		private var stopRequest: (() -> Unit)? = null

		/**
		 * Called when the agent starts working.
		 *
		 * @param stop Stops the agent, for the notification's action.
		 */
		fun runStarted(context: Context, stop: () -> Unit) {
			working = true
			stopRequest = stop
			// Runs start from a tap in the app, which is when starting a foreground service is allowed.
			// Should the system refuse anyway, the agent still runs, it just is not protected in the background.
			runCatching { context.startForegroundService(Intent(context, AgentService::class.java)) }
		}

		/** Called when the agent is done, was stopped or failed. */
		fun runEnded() {
			working = false
			stopRequest = null
			// When the service has not come up yet, it sees that there is no work and ends itself.
			instance?.finish()
		}
	}
}
