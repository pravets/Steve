package com.steve.ai.plugin;

import com.steve.ai.di.ServiceContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages plugin discovery, loading, and lifecycle using Java's ServiceLoader.
 *
 * <p>Implements plugin discovery via SPI (Service Provider Interface), dependency
 * resolution via topological sorting, and priority-based loading order.</p>
 *
 * <p><b>Plugin Discovery:</b></p>
 * <ol>
 *   <li>ServiceLoader scans META-INF/services/com.steve.ai.plugin.ActionPlugin</li>
 *   <li>Plugins are sorted by dependencies (topological sort)</li>
 *   <li>Plugins are loaded in priority order within dependency constraints</li>
 *   <li>Each plugin's onLoad() is called with registry and container</li>
 * </ol>
 *
 * <p><b>Thread Safety:</b> Plugin loading is not thread-safe; call from server thread only.</p>
 *
 * @since 1.1.0
 * @see ActionPlugin
 * @see java.util.ServiceLoader
 */
public class PluginManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(PluginManager.class);

    private static final PluginManager INSTANCE = new PluginManager();

    /**
     * Loaded plugins by ID.
     */
    private final ConcurrentHashMap<String, ActionPlugin> loadedPlugins;

    /**
     * Plugin load order for proper unloading.
     */
    private final List<String> loadOrder;

    /**
     * Flag indicating if plugins are loaded.
     */
    private volatile boolean initialized;

    private PluginManager() {
        this.loadedPlugins = new ConcurrentHashMap<>();
        this.loadOrder = new ArrayList<>();
        this.initialized = false;
    }

    /**
     * Returns the singleton PluginManager instance.
     *
     * @return PluginManager singleton
     */
    public static PluginManager getInstance() {
        return INSTANCE;
    }

    /**
     * Discovers and loads all plugins.
     *
     * <p>Should be called once during server startup.</p>
     *
     * @param registry  ActionRegistry for action registration
     * @param container ServiceContainer for dependency injection
     */
    public synchronized void loadPlugins(ActionRegistry registry, ServiceContainer container) {
        if (initialized) {
            LOGGER.warn("Plugins already loaded, skipping");
            return;
        }

        LOGGER.info("Discovering plugins via ServiceLoader...");

        // Discover plugins via SPI
        ServiceLoader<ActionPlugin> loader = ServiceLoader.load(ActionPlugin.class);
        List<ActionPlugin> discovered = new ArrayList<>();

        for (ActionPlugin plugin : loader) {
            discovered.add(plugin);
            LOGGER.info("Discovered plugin: {} v{} (priority: {})",
                plugin.getPluginId(), plugin.getVersion(), plugin.getPriority());
        }

        if (discovered.isEmpty()) {
            LOGGER.warn("No plugins discovered! Check META-INF/services configuration");
            return;
        }

        // Sort by dependencies and priority
        List<ActionPlugin> sorted = sortPlugins(discovered);

        // Load plugins in order
        for (ActionPlugin plugin : sorted) {
            try {
                loadPlugin(plugin, registry, container);
            } catch (Exception e) {
                LOGGER.error("Failed to load plugin {}: {}", plugin.getPluginId(), e.getMessage(), e);
            }
        }

        initialized = true;
        LOGGER.info("Plugin loading complete: {} plugins loaded", loadedPlugins.size());
    }

    /**
     * Loads a single plugin.
     */
    private void loadPlugin(ActionPlugin plugin, ActionRegistry registry, ServiceContainer container) {
        String pluginId = plugin.getPluginId();

        // Check if already loaded
        if (loadedPlugins.containsKey(pluginId)) {
            LOGGER.warn("Plugin {} already loaded, skipping", pluginId);
            return;
        }

        // Check dependencies are loaded
        for (String dependency : plugin.getDependencies()) {
            if (!loadedPlugins.containsKey(dependency)) {
                throw new IllegalStateException(
                    "Plugin " + pluginId + " requires " + dependency + " which is not loaded");
            }
        }

        LOGGER.info("Loading plugin: {} v{}", pluginId, plugin.getVersion());

        // Call onLoad
        plugin.onLoad(registry, container);

        // Track loaded plugin
        loadedPlugins.put(pluginId, plugin);
        loadOrder.add(pluginId);

        LOGGER.info("Plugin {} loaded successfully", pluginId);
    }

    /**
     * Sorts plugins by dependencies (topological sort) and priority.
     *
     * @param plugins Unsorted plugins
     * @return Sorted plugins respecting dependencies and priority
     */
    private List<ActionPlugin> sortPlugins(List<ActionPlugin> plugins) {
        // Build dependency graph
        Map<String, ActionPlugin> pluginMap = new HashMap<>();
        Map<String, Set<String>> dependencies = new HashMap<>();
        Map<String, Integer> inDegree = new HashMap<>();

        for (ActionPlugin plugin : plugins) {
            String id = plugin.getPluginId();
            pluginMap.put(id, plugin);
            dependencies.put(id, new HashSet<>(Arrays.asList(plugin.getDependencies())));
            inDegree.put(id, 0);
        }

        // Calculate in-degrees
        for (ActionPlugin plugin : plugins) {
            for (String dep : plugin.getDependencies()) {
                if (pluginMap.containsKey(dep)) {
                    inDegree.merge(plugin.getPluginId(), 1, Integer::sum);
                }
            }
        }

        // Topological sort with priority-based tie-breaking
        List<ActionPlugin> sorted = new ArrayList<>();
        PriorityQueue<ActionPlugin> queue = new PriorityQueue<>(
            Comparator.comparingInt(ActionPlugin::getPriority).reversed());

        // Start with plugins that have no dependencies
        for (ActionPlugin plugin : plugins) {
            if (inDegree.get(plugin.getPluginId()) == 0) {
                queue.offer(plugin);
            }
        }

        Set<String> processed = new HashSet<>();
        while (!queue.isEmpty()) {
            ActionPlugin plugin = queue.poll();
            sorted.add(plugin);
            processed.add(plugin.getPluginId());

            // Update in-degrees for plugins that depend on this one
            for (ActionPlugin other : plugins) {
                if (processed.contains(other.getPluginId())) continue;

                Set<String> deps = dependencies.get(other.getPluginId());
                if (deps.contains(plugin.getPluginId())) {
                    int newDegree = inDegree.get(other.getPluginId()) - 1;
                    inDegree.put(other.getPluginId(), newDegree);

                    // Check if all dependencies are now satisfied
                    boolean allSatisfied = deps.stream().allMatch(processed::contains);
                    if (allSatisfied && !queue.contains(other)) {
                        queue.offer(other);
                    }
                }
            }
        }

        // Check for circular dependencies
        if (sorted.size() != plugins.size()) {
            LOGGER.error("Circular dependency detected! Some plugins could not be sorted.");
            // Add remaining plugins anyway
            for (ActionPlugin plugin : plugins) {
                if (!processed.contains(plugin.getPluginId())) {
                    LOGGER.warn("Plugin {} has unresolved dependencies, loading anyway",
                        plugin.getPluginId());
                    sorted.add(plugin);
                }
            }
        }

        return sorted;
    }

    /**
     * Unloads all plugins in reverse order.
     *
     * <p>Should be called during server shutdown.</p>
     */
    public synchronized void unloadPlugins() {
        if (!initialized) {
            LOGGER.warn("Plugins not loaded, nothing to unload");
            return;
        }

        LOGGER.info("Unloading {} plugins...", loadedPlugins.size());

        // Unload in reverse order
        List<String> reversed = new ArrayList<>(loadOrder);
        Collections.reverse(reversed);

        for (String pluginId : reversed) {
            ActionPlugin plugin = loadedPlugins.get(pluginId);
            if (plugin != null) {
                try {
                    LOGGER.info("Unloading plugin: {}", pluginId);
                    plugin.onUnload();
                } catch (Exception e) {
                    LOGGER.error("Error unloading plugin {}: {}", pluginId, e.getMessage(), e);
                }
            }
        }

        loadedPlugins.clear();
        loadOrder.clear();
        initialized = false;

        LOGGER.info("All plugins unloaded");
    }

    /**
     * Returns a loaded plugin by ID.
     *
     * @param pluginId Plugin ID
     * @return Plugin instance, or null if not loaded
     */
    public ActionPlugin getPlugin(String pluginId) {
        return loadedPlugins.get(pluginId);
    }

    /**
     * Checks if a plugin is loaded.
     *
     * @param pluginId Plugin ID
     * @return true if loaded
     */
    public boolean isPluginLoaded(String pluginId) {
        return loadedPlugins.containsKey(pluginId);
    }

    /**
     * Returns all loaded plugin IDs.
     *
     * @return Set of plugin IDs
     */
    public Set<String> getLoadedPluginIds() {
        return Collections.unmodifiableSet(loadedPlugins.keySet());
    }

    /**
     * Returns the number of loaded plugins.
     *
     * @return Plugin count
     */
    public int getPluginCount() {
        return loadedPlugins.size();
    }

    /**
     * Checks if plugins have been initialized.
     *
     * @return true if initialized
     */
    public boolean isInitialized() {
        return initialized;
    }
}
