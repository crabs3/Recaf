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
 * Foreground service that hosts the MCP server.
 *
 * MCP clients are typically other apps, a terminal on the device or a computer on the same network. The server has
 * to stay reachable while Recaf is not the app in front, which Android only guarantees for a foreground service
 * with a visible notification.
 */
class McpService : Service() {
	override fun onBind(intent: Intent?): IBinder? = null

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		if (intent?.action == ACTION_STOP) {
			RecafSession.stopMcp()
			stopForeground(STOP_FOREGROUND_REMOVE)
			stopSelf()
			return START_NOT_STICKY
		}
		startForeground(NOTIFICATION_ID, notification("Starting…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
		RecafSession.startMcp(SettingsStore.settings.value)
		val state = RecafSession.mcp.value
		if (!state.running) {
			// Starting failed, for instance because the port is taken. The screen shows the reason.
			stopForeground(STOP_FOREGROUND_REMOVE)
			stopSelf()
			return START_NOT_STICKY
		}
		val scope = if (state.network) "network" else "this device"
		getSystemService(NotificationManager::class.java)
			.notify(NOTIFICATION_ID, notification("Port ${state.port}, reachable from $scope"))
		return START_NOT_STICKY
	}

	override fun onDestroy() {
		RecafSession.stopMcp()
		super.onDestroy()
	}

	private fun notification(text: String): Notification {
		val manager = getSystemService(NotificationManager::class.java)
		if (manager.getNotificationChannel(CHANNEL_ID) == null) {
			manager.createNotificationChannel(
				NotificationChannel(CHANNEL_ID, getString(R.string.mcp_channel_name), NotificationManager.IMPORTANCE_LOW)
					.apply { description = getString(R.string.mcp_channel_description) }
			)
		}
		val open = PendingIntent.getActivity(
			this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
		)
		val stop = PendingIntent.getService(
			this, 1, Intent(this, McpService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
		)
		return Notification.Builder(this, CHANNEL_ID)
			.setSmallIcon(R.drawable.ic_launcher_foreground)
			.setContentTitle("Recaf MCP server is running")
			.setContentText(text)
			.setContentIntent(open)
			.setOngoing(true)
			.addAction(Notification.Action.Builder(null, "Stop", stop).build())
			.build()
	}

	companion object {
		private const val CHANNEL_ID = "mcp"
		private const val NOTIFICATION_ID = 1
		private const val ACTION_STOP = "software.coley.recaf.android.app.STOP_MCP"

		fun start(context: Context) {
			context.startForegroundService(Intent(context, McpService::class.java))
		}

		fun stop(context: Context) {
			context.startService(Intent(context, McpService::class.java).setAction(ACTION_STOP))
		}
	}
}
