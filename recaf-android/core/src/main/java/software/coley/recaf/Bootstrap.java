package software.coley.recaf;

import jakarta.annotation.Nonnull;
import org.slf4j.Logger;
import software.coley.recaf.analytics.logging.Logging;
import software.coley.recaf.android.cdi.LiteContainer;
import software.coley.recaf.cdi.InitializationEvent;
import software.coley.recaf.cdi.InitializationStage;
import software.coley.recaf.cdi.UiInitializationEvent;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static software.coley.recaf.RecafBuildConfig.*;

/**
 * Handles creation of Recaf instance.
 * <p>
 * <b>Android variant</b>: Desktop Recaf builds a Weld container here. Weld defines proxy classes from JVM bytecode
 * at runtime, which Android cannot do, so this variant builds a {@link LiteContainer} from the bean index that
 * the build generates into {@code META-INF/recaf-core-beans.txt}.
 *
 * @author Matt Coley
 */
public class Bootstrap {
	private static final Logger logger = Logging.get(Bootstrap.class);
	private static final String RESOURCE_PATH = "META-INF/recaf-core-beans.txt";
	private static final Set<Class<?>> additionalBeans = new LinkedHashSet<>();
	private static Recaf instance;
	private static LiteContainer container;
	private static boolean initialized;

	/**
	 * @return Recaf instance.
	 */
	@Nonnull
	public static synchronized Recaf get() {
		if (instance == null) {
			String fmt = """
					Initializing Recaf {}
					 - Build rev:  {}
					 - Build date: {}
					 - Build hash: {}""";
			logger.info(fmt, VERSION, GIT_REVISION, GIT_DATE, GIT_SHA);
			long then = System.currentTimeMillis();
			try {
				container = createContainer();
				instance = new Recaf(container);
				logger.info("Recaf CDI container created in {}ms", System.currentTimeMillis() - then);
			} catch (Throwable t) {
				logger.error("Failed to create Recaf CDI container", t);
				throw new IllegalStateException("Failed to create Recaf CDI container", t);
			}
		}
		return instance;
	}

	/**
	 * Registers an additional bean class with the container.
	 * Can be called before or after {@link #get()}.
	 *
	 * @param beanClass
	 * 		Class annotated with a CDI scope annotation.
	 */
	public static synchronized void addBeanClass(@Nonnull Class<?> beanClass) {
		additionalBeans.add(beanClass);
		if (container != null)
			container.register(beanClass);
	}

	/**
	 * Performs the equivalent of the desktop launch sequence's initialization step:
	 * Publishes the {@link InitializationEvent} and creates eagerly initialized services.
	 * Calling this more than once has no effect.
	 */
	public static synchronized void initialize() {
		get();
		if (initialized)
			return;
		initialized = true;
		long then = System.currentTimeMillis();
		container.fire(new InitializationEvent());
		container.initializeEagerBeans(InitializationStage.IMMEDIATE);
		logger.info("Recaf services initialized in {}ms", System.currentTimeMillis() - then);
	}

	/**
	 * Publishes the {@link UiInitializationEvent} and creates services which wait for the UI.
	 */
	public static synchronized void initializeUi() {
		initialize();
		container.fire(new UiInitializationEvent());
		container.initializeEagerBeans(InitializationStage.AFTER_UI_INIT);
	}

	/**
	 * No-op on Android, the bean index only ever lists the beans compiled into the application.
	 * Kept for source compatibility with the desktop variant.
	 */
	public static void enableCoreOnlyDiscovery() {
		// no-op
	}

	/**
	 * Shuts down the container, invoking {@code @PreDestroy} callbacks of created services.
	 */
	public static synchronized void shutdown() {
		if (container != null)
			container.close();
		container = null;
		instance = null;
		initialized = false;
	}

	@Nonnull
	private static LiteContainer createContainer() {
		logger.info("Creating Recaf CDI container...");
		ClassLoader classLoader = Bootstrap.class.getClassLoader();
		List<Class<?>> beanClasses = new ArrayList<>();
		for (String beanClassName : loadBeanClassNames(classLoader)) {
			try {
				beanClasses.add(Class.forName(beanClassName, false, classLoader));
			} catch (ClassNotFoundException | LinkageError ex) {
				// The index is generated from sources, a class that was stripped from the build is skipped.
				logger.warn("Skipping unavailable bean class: {}", beanClassName);
			}
		}
		beanClasses.addAll(additionalBeans);
		return new LiteContainer(beanClasses);
	}

	@Nonnull
	private static List<String> loadBeanClassNames(@Nonnull ClassLoader classLoader) {
		try (InputStream stream = classLoader.getResourceAsStream(RESOURCE_PATH)) {
			if (stream == null)
				throw new IllegalStateException("Missing core bean index: " + RESOURCE_PATH);
			List<String> names = new ArrayList<>();
			try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null)
					if (!line.isBlank())
						names.add(line.trim());
			}
			return names;
		} catch (IOException ex) {
			throw new IllegalStateException("Failed reading core bean index: " + RESOURCE_PATH, ex);
		}
	}
}
