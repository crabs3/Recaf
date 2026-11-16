package software.coley.recaf.util;

/**
 * Desktop Recaf opens JDK modules for deep reflection here.
 * <p>
 * <b>Android variant</b>: There is no module system to open, this is a no-op.
 *
 * @author xDark
 */
class AccessPatcher {
	private AccessPatcher() {}

	/**
	 * No-op on Android.
	 */
	static void patch() {
		// no-op
	}
}
