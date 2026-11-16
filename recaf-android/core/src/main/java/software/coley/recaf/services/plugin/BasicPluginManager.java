package software.coley.recaf.services.plugin;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import software.coley.recaf.plugin.Plugin;
import software.coley.recaf.services.plugin.discovery.PluginDiscoverer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Basic implementation of {@link PluginManager}.
 * <p>
 * <b>Android variant</b>: Recaf plugins are jars of JVM bytecode, each loaded into its own class loader and child
 * Weld container. Neither can work on Android's runtime, so this variant manages no plugins. Loaders can still be
 * registered so that callers do not need to special-case the platform, but {@link #loadPlugins(PluginDiscoverer)}
 * fails with an explanatory {@link PluginException}.
 *
 * @author xDark
 */
@ApplicationScoped
public class BasicPluginManager implements PluginManager {
	private final List<PluginLoader> loaders = new ArrayList<>();
	private final PluginManagerConfig config;

	@Inject
	public BasicPluginManager(@Nonnull PluginManagerConfig config) {
		this.config = config;
	}

	@Nullable
	@Override
	public <T extends Plugin> PluginContainer<T> getPlugin(@Nonnull String id) {
		return null;
	}

	@Nonnull
	@Override
	public Collection<PluginContainer<?>> getPlugins() {
		return Collections.emptyList();
	}

	@Override
	public boolean isPluginLoaded(@Nonnull String id) {
		return false;
	}

	@Override
	public void registerLoader(@Nonnull PluginLoader loader) {
		loaders.add(loader);
	}

	@Nonnull
	@Override
	public Collection<PluginContainer<?>> loadPlugins(@Nonnull PluginDiscoverer discoverer) throws PluginException {
		throw new PluginException("Recaf plugins are not supported on Android");
	}

	@Nonnull
	@Override
	public PluginUnloader unloaderFor(@Nonnull String id) {
		throw new IllegalStateException("Plugin is not loaded: " + id);
	}

	@Nonnull
	@Override
	public String getServiceId() {
		return SERVICE_ID;
	}

	@Nonnull
	@Override
	public PluginManagerConfig getServiceConfig() {
		return config;
	}
}
