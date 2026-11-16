package software.coley.recaf.analytics.logging;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.LegacyAbstractLogger;
import org.slf4j.helpers.MessageFormatter;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * {@link LoggerFactory} wrapper that lets us intercept all logged messages.
 * <p>
 * <b>Android variant</b>: Desktop Recaf routes logging through the Rainbow Gum SLF4J backend, which depends on
 * {@code java.lang.System.Logger}. This variant uses a self-contained logger which prints to standard output
 * <i>(which Android forwards to logcat)</i> and feeds the same {@link LogConsumer} interception API.
 *
 * @author Matt Coley
 */
public class Logging {
	private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
	private static final Map<String, DebuggingLogger> loggers = new ConcurrentHashMap<>();
	private static final NavigableSet<String> loggerKeys = Collections.synchronizedNavigableSet(new TreeSet<>());
	private static final List<LogConsumer<String>> logConsumers = new CopyOnWriteArrayList<>();
	private static final Map<Path, FileLogSink> fileSinks = new ConcurrentHashMap<>();
	private static Level interceptLevel = Level.INFO;

	/**
	 * @return Set of the current logger keys.
	 */
	@Nonnull
	public static NavigableSet<String> loggerKeys() {
		return Collections.unmodifiableNavigableSet(loggerKeys);
	}

	/**
	 * @param name
	 * 		Logger name.
	 *
	 * @return Logger associated with name.
	 */
	@Nonnull
	public static DebuggingLogger get(@Nonnull String name) {
		return loggers.computeIfAbsent(name, k -> intercept(k, new ConsoleLogger(k)));
	}

	/**
	 * @param cls
	 * 		Logger class key.
	 *
	 * @return Logger associated with class.
	 */
	@Nonnull
	public static DebuggingLogger get(@Nonnull Class<?> cls) {
		return get(cls.getName());
	}

	/**
	 * @param consumer
	 * 		New log message consumer.
	 */
	public static void addLogConsumer(@Nonnull LogConsumer<String> consumer) {
		logConsumers.add(consumer);
	}

	/**
	 * @param consumer
	 * 		Log message consumer to remove.
	 */
	public static void removeLogConsumer(@Nonnull LogConsumer<String> consumer) {
		logConsumers.remove(consumer);
	}

	/**
	 * Sets the target level for log interception. This affects what messages {@link LogConsumer}s receive.
	 *
	 * @param level
	 * 		New target level.
	 */
	public static void setInterceptLevel(@Nonnull Level level) {
		interceptLevel = level;
	}

	/**
	 * Registers a file appender for all log calls.
	 *
	 * @param path
	 * 		Path to file to append to.
	 */
	public static void addFileAppender(@Nonnull Path path) {
		Path normalized = path.toAbsolutePath().normalize();
		fileSinks.computeIfAbsent(normalized, FileLogSink::new);
	}

	@Nonnull
	private static DebuggingLogger intercept(@Nonnull String name, @Nonnull Logger logger) {
		loggerKeys.add(name);
		return new InterceptingLogger(logger) {
			@Override
			public void intercept(@Nonnull Level level, String message) {
				if (interceptLevel.toInt() <= level.toInt())
					logConsumers.forEach(consumer -> consumer.accept(name, level, message));
			}

			@Override
			public void intercept(@Nonnull Level level, String message, Throwable t) {
				if (interceptLevel.toInt() <= level.toInt())
					logConsumers.forEach(consumer -> consumer.accept(name, level, message, t));
			}
		};
	}

	/**
	 * @param loggerName
	 * 		Logger name.
	 * @param level
	 * 		Level of message.
	 * @param message
	 * 		Formatted message.
	 * @param throwable
	 * 		Optional throwable.
	 *
	 * @return Line(s) in the form: {@code HH:mm:ss.SSS [Class/Thread] LEVEL: MESSAGE}
	 */
	@Nonnull
	private static String format(@Nonnull String loggerName, @Nonnull Level level,
	                             @Nullable String message, @Nullable Throwable throwable) {
		int separator = loggerName.lastIndexOf('.');
		String shortName = separator >= 0 && separator + 1 < loggerName.length() ?
				loggerName.substring(separator + 1) : loggerName;
		StringBuilder sb = new StringBuilder(96);
		sb.append(LocalTime.now().format(TIME_FORMAT))
				.append(" [").append(shortName).append('/').append(Thread.currentThread().getName()).append("] ")
				.append(String.format("%-5s", level.name())).append(": ").append(message);
		if (throwable != null) {
			StringWriter trace = new StringWriter();
			throwable.printStackTrace(new PrintWriter(trace));
			sb.append('\n').append(trace.toString().stripTrailing());
		}
		return sb.toString();
	}

	/**
	 * Backing logger which prints to standard output.
	 */
	private static final class ConsoleLogger extends LegacyAbstractLogger {
		private ConsoleLogger(@Nonnull String name) {
			this.name = name;
		}

		@Override
		protected String getFullyQualifiedCallerName() {
			return null;
		}

		@Override
		protected void handleNormalizedLoggingCall(Level level, Marker marker, String messagePattern,
		                                           Object[] arguments, Throwable throwable) {
			if (!RecafLoggingFilter.allowsConsole(name, level))
				return;
			String message = MessageFormatter.basicArrayFormat(messagePattern, arguments);
			System.out.println(format(name, level, message, throwable));
		}

		@Override
		public boolean isTraceEnabled() {
			return true;
		}

		@Override
		public boolean isDebugEnabled() {
			return true;
		}

		@Override
		public boolean isInfoEnabled() {
			return true;
		}

		@Override
		public boolean isWarnEnabled() {
			return true;
		}

		@Override
		public boolean isErrorEnabled() {
			return true;
		}
	}

	/**
	 * Log sink that writes to a file.
	 */
	private static final class FileLogSink implements LogConsumer<String> {
		private final Path path;
		private BufferedWriter writer;

		private FileLogSink(@Nonnull Path path) {
			this.path = path;
			try {
				if (path.getParent() != null)
					Files.createDirectories(path.getParent());
				writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
						StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			} catch (IOException ex) {
				System.err.println("Failed to open log file: " + path + " - " + ex);
			}
			addLogConsumer(this);
		}

		@Override
		public void accept(@Nonnull String loggerName, @Nonnull Level level, String messageContent) {
			accept(loggerName, level, messageContent, null);
		}

		@Override
		public synchronized void accept(@Nonnull String loggerName, @Nonnull Level level,
		                                @Nullable String messageContent, @Nullable Throwable throwable) {
			if (writer == null || !RecafLoggingFilter.allowsFile(loggerName, level))
				return;
			try {
				writer.write(format(loggerName, level, messageContent, throwable));
				writer.newLine();
				writer.flush();
			} catch (IOException ex) {
				// Stop writing to a file that cannot be written to.
				writer = null;
				fileSinks.remove(path, this);
				removeLogConsumer(this);
			}
		}
	}
}
