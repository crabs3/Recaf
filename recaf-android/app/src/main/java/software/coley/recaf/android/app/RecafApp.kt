package software.coley.recaf.android.app

import android.app.Application

/**
 * Application entry point. Starts loading Recaf as early as possible, since creating its services takes a few
 * seconds on a phone and everything else waits on it.
 */
class RecafApp : Application() {
	override fun onCreate() {
		super.onCreate()
		SettingsStore.init(this)
		RecafSession.start(this)
	}
}
