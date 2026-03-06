package com.heatmap.engine;

import com.heatmap.HeatmapPlugin;
import com.heatmap.database.DatabaseManager;
import com.heatmap.model.DataPoint;
import com.heatmap.model.EventType;
import com.heatmap.spatial.SpatialHashMap;
import com.heatmap.visualization.HeatmapRenderer;
import org.bukkit.Location;

/**
 * Central coordinator for all plugin subsystems.
 *
 * Acts as the only public surface that event listeners and commands interact
 * with.
 * They do NOT hold direct references to {@code DatabaseManager} or other
 * internals.
 */
public class AnalyticsEngine {

    private final HeatmapPlugin plugin;
    private final DatabaseManager dbManager;
    private final BatchWriteQueue writeQueue;
    private final SpatialHashMap spatialMap;
    private final HeatmapRenderer renderer;

    public AnalyticsEngine(HeatmapPlugin plugin,
            DatabaseManager dbManager,
            BatchWriteQueue writeQueue,
            SpatialHashMap spatialMap,
            HeatmapRenderer renderer) {
        this.plugin = plugin;
        this.dbManager = dbManager;
        this.writeQueue = writeQueue;
        this.spatialMap = spatialMap;
        this.renderer = renderer;
    }

    // -------------------------------------------------------------------------
    // Event recording API
    // -------------------------------------------------------------------------

    /**
     * Creates a {@link DataPoint} from the supplied parameters and enqueues it for
     * async batch writing. This method is safe to call from the <em>main
     * server thread</em> — it performs no I/O and never blocks.
     *
     * @param type     category of event
     * @param location world location where the event occurred
     * @param metadata arbitrary string (player name, material, etc.)
     */
    public void recordEvent(EventType type, Location location, String metadata) {
        if (location == null || location.getWorld() == null)
            return;

        DataPoint dp = new DataPoint(
                location.getWorld().getName(),
                location.getX(),
                location.getY(),
                location.getZ(),
                type,
                metadata);

        writeQueue.add(dp);
    }

    // -------------------------------------------------------------------------
    // Accessors for commands / other subsystems
    // -------------------------------------------------------------------------

    public DatabaseManager getDbManager() {
        return dbManager;
    }

    public BatchWriteQueue getWriteQueue() {
        return writeQueue;
    }

    public SpatialHashMap getSpatialMap() {
        return spatialMap;
    }

    public HeatmapRenderer getRenderer() {
        return renderer;
    }

    public HeatmapPlugin getPlugin() {
        return plugin;
    }
}
