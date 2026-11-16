package software.coley.recaf.analytics.logging;

import jakarta.annotation.Nonnull;
import org.slf4j.event.Level;

/**
 * Shared filtering for Recaf-managed logging outputs.
 * <p>
 * <b>Android variant</b>: Uses SLF4J levels, {@code java.lang.System.Logger} does not exist on Android.
 *
 * @author Matt Coley
 */
public final class RecafLoggingFilter {
	private static volatile Level consoleLevel = Level.DEBUG;
	private static volatile boolean consoleEnabled = true;

	private RecafLoggingFilter() {}

	/**
	 * @return Minimum level for console logging.
	 */
	@Nonnull
	public static Level getConsoleLevel() {
		return consoleLevel;
	}

	/**
	 * @param level
	 * 		Minimum level for console logging.
	 */
	public static void setConsoleLevel(@Nonnull Level level) {
		consoleLevel = level;
	}

	/**
	 * @param enabled
	 *        {@code true} to print log messages to the console <i>(logcat on Android)</i>.
	 */
	public static void setConsoleEnabled(boolean enabled) {
		consoleEnabled = enabled;
	}

	/**
	 * @param loggerName
	 * 		Logger name.
	 * @param level
	 * 		Level of the message.
	 *
	 * @return {@code true} when the message should be printed to the console.
	 */
	public static boolean allowsConsole(@Nonnull String loggerName, @Nonnull Level level) {
		return consoleEnabled && isRecafLogger(loggerName) && level.toInt() >= consoleLevel.toInt();
	}

	/**
	 * @param loggerName
	 * 		Logger name.
	 * @param level
	 * 		Level of the message.
	 *
	 * @return {@code true} when the message should be written to log files.
	 */
	public static boolean allowsFile(@Nonnull String loggerName, @Nonnull Level level) {
		return isRecafLogger(loggerName);
	}

	private static boolean isRecafLogger(@Nonnull String loggerName) {
		return loggerName.startsWith("software.coley.") || Logging.loggerKeys().contains(loggerName);
	}
}
