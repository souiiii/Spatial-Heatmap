package com.heatmap;

import com.heatmap.commands.HeatmapCommand;
import com.heatmap.database.DatabaseManager;
import com.heatmap.engine.AnalyticsEngine;
import com.heatmap.engine.BatchWriteQueue;
import com.heatmap.listeners.BlockBreakListener;
import com.heatmap.listeners.PlayerDeathListener;
import com.heatmap.listeners.PlayerMoveListener;
import com.heatmap.spatial.SpatialHashMap;
import com.heatmap.visualization.HeatmapRenderer;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.logging.Level;

/**
 * Main entry point for the Spatial Heatmap plugin.
 * Handles lifecycle: initialising the database, starting schedulers,
 * registering listeners, and orderly shutdown.
 */
public class HeatmapPlugin extends JavaPlugin {

    private DatabaseManager databaseManager;
    private BatchWriteQueue writeQueue;
    private AnalyticsEngine analyticsEngine;
    private HeatmapRenderer heatmapRenderer;
    private PlayerMoveListener playerMoveListener;

    @Override
    public void onEnable() {
        // 1. Config
        saveDefaultConfig();

        // 2. Database layer
        databaseManager = new DatabaseManager(this);
        try {
            databaseManager.initialize();
        } catch (SQLException e) {
            getLogger().log(Level.SEVERE, "Failed to initialize SQLite database! Disabling plugin...", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // 3. Core Engine systems
        writeQueue = new BatchWriteQueue(this, databaseManager);
        writeQueue.start();

        int cellSize = getConfig().getInt("cell-size", 4);
        SpatialHashMap spatialMap = new SpatialHashMap(cellSize);

        // Build engine first, then inject renderer via setter to avoid circular
        // dependency.
        analyticsEngine = new AnalyticsEngine(this, databaseManager, writeQueue, spatialMap);
        heatmapRenderer = new HeatmapRenderer(analyticsEngine);
        analyticsEngine.setRenderer(heatmapRenderer);

        // 4. Register Event Listeners
        if (getConfig().getBoolean("track-events.deaths", true)) {
            getServer().getPluginManager().registerEvents(new PlayerDeathListener(analyticsEngine), this);
            getLogger().info("Registered PlayerDeathListener");
        }

        if (getConfig().getBoolean("track-events.block-breaks", true)) {
            getServer().getPluginManager().registerEvents(new BlockBreakListener(analyticsEngine), this);
            getLogger().info("Registered BlockBreakListener");
        }

        if (getConfig().getBoolean("track-events.player-moves", true)) {
            playerMoveListener = new PlayerMoveListener(analyticsEngine);
            playerMoveListener.start();
            getLogger().info("Started PlayerMoveListener scheduled task");
        }

        // 5. Register Commands
        PluginCommand cmd = getCommand("heatmap");
        if (cmd != null) {
            HeatmapCommand heatmapCommand = new HeatmapCommand(this, analyticsEngine);
            cmd.setExecutor(heatmapCommand);
            cmd.setTabCompleter(heatmapCommand);
        }

        getLogger().info("Spatial Heatmap Analytics Engine successfully enabled!");
    }

    @Override
    public void onDisable() {
        getLogger().info("Disabling Spatial Heatmap...");

        // 1. Stop components that generate new events
        if (playerMoveListener != null) {
            playerMoveListener.stop();
        }

        // 2. Stop visualizations
        if (heatmapRenderer != null) {
            heatmapRenderer.stopAll();
        }

        // 3. Cancel the periodic scheduler, then perform a final queue flush
        // on a dedicated thread so we don't stall the main server thread.
        if (writeQueue != null) {
            writeQueue.stop();
            getLogger().info("Executing final queue flush on dedicated thread...");
            Thread flushThread = new Thread(() -> {
                writeQueue.forceFlush();
                getLogger().info("[BatchWriteQueue] Final flush thread finished.");
            }, "heatmap-shutdown-flush");
            flushThread.start();
            try {
                // Wait up to 10 seconds for the flush to complete before closing the DB.
                flushThread.join(10_000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                getLogger().warning("[HeatmapPlugin] Interrupted while waiting for flush thread.");
            }
        }

        // 4. Close database connection safely
        if (databaseManager != null) {
            databaseManager.close();
            getLogger().info("Database connection closed.");
        }

        getLogger().info("Disabled efficiently.");
    }
}
