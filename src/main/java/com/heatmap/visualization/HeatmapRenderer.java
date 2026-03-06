package com.heatmap.visualization;

import com.heatmap.engine.AnalyticsEngine;
import com.heatmap.model.EventType;
import com.heatmap.spatial.CellKey;
import com.heatmap.spatial.SpatialHashMap;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.logging.Level;

/**
 * Renders 3D particle heatmaps in-game by spawning
 * {@link Particle#REDSTONE} (dust) particles at the centre of each spatial
 * cell, with color interpolated from <em>blue</em> (low density) through
 * <em>green → yellow → red</em> (high density).
 *
 * <h3>Per-player rendering</h3>
 * Each admin gets their own {@link BukkitTask} so multiple admins can
 * independently view different event types / radii simultaneously.
 *
 * <h3>Thread model</h3>
 * The data-loading step (DB query + spatial hash population) runs
 * <em>asynchronously</em>. Once loaded, the particle-spawning repeating task
 * runs on the <em>main thread</em>, which is required by the Bukkit particle
 * API.
 */
public class HeatmapRenderer {

    private final AnalyticsEngine engine;

    /** Maps player UUID → their active render task (null if not rendering). */
    private final Map<UUID, BukkitTask> activeTasks = new HashMap<>();

    /** Per-player loaded spatial maps (loaded async, consumed sync). */
    private final Map<UUID, SpatialHashMap> playerMaps = new HashMap<>();

    public HeatmapRenderer(AnalyticsEngine engine) {
        this.engine = engine;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Starts rendering a heatmap for {@code player}.
     *
     * <ol>
     * <li>Loads data from the DB asynchronously into a fresh
     * {@link SpatialHashMap}.</li>
     * <li>Once loaded, schedules a repeating main-thread task that re-spawns
     * particles every {@code renderRefreshTicks} ticks.</li>
     * </ol>
     *
     * @param player    the admin requesting the heatmap
     * @param eventType which type of event to visualize
     * @param radius    render radius in blocks around the player
     */
    public void startRendering(Player player, EventType eventType, int radius) {
        // Cancel any existing render for this player
        stopRendering(player);

        player.sendMessage("§6[Heatmap] §eLoading " + eventType.name() + " data from database…");

        UUID uuid = player.getUniqueId();
        int cellSize = engine.getPlugin().getConfig().getInt("cell-size", 4);
        int maxCells = engine.getPlugin().getConfig().getInt("max-render-cells", 1024);
        int refreshT = engine.getPlugin().getConfig().getInt("render-refresh-ticks", 40);

        // Step 1: load data asynchronously
        engine.getPlugin().getServer().getScheduler().runTaskAsynchronously(
                engine.getPlugin(),
                () -> {
                    SpatialHashMap spatialMap = new SpatialHashMap(cellSize);

                    try {
                        List<Object[]> rows = engine.getDbManager()
                                .queryDensity(player.getWorld().getName(), eventType, cellSize);

                        for (Object[] row : rows) {
                            spatialMap.record((CellKey) row[0], (Integer) row[1]);
                        }
                    } catch (Exception ex) {
                        engine.getPlugin().getLogger().log(Level.SEVERE, "[HeatmapRenderer] Failed to load data.", ex);
                        // schedule feedback on main thread
                        engine.getPlugin().getServer().getScheduler().runTask(
                                engine.getPlugin(),
                                () -> player.sendMessage("§c[Heatmap] Failed to load data. See console."));
                        return;
                    }

                    final int totalCells = spatialMap.cellCount();

                    // Step 2: back to main thread to start particle task
                    engine.getPlugin().getServer().getScheduler().runTask(
                            engine.getPlugin(),
                            () -> {
                                if (!player.isOnline())
                                    return; // player left while loading

                                playerMaps.put(uuid, spatialMap);

                                player.sendMessage("§6[Heatmap] §aLoaded §f" + totalCells +
                                        " §acells. Rendering §f" + eventType.name() + "§a…");
                                player.sendMessage("§7Use §f/heatmap stop §7to dismiss.");

                                BukkitTask task = new BukkitRunnable() {
                                    @Override
                                    public void run() {
                                        if (!player.isOnline()) {
                                            cancel();
                                            activeTasks.remove(uuid);
                                            playerMaps.remove(uuid);
                                            return;
                                        }
                                        SpatialHashMap sm = playerMaps.get(uuid);
                                        if (sm == null) {
                                            cancel();
                                            return;
                                        }

                                        renderParticles(player, sm, radius, maxCells);
                                    }
                                }.runTaskTimer(engine.getPlugin(), 0L, refreshT);

                                activeTasks.put(uuid, task);
                            });
                });
    }

    /**
     * Stops and cleans up the render task for a player.
     *
     * @return {@code true} if a task was actually cancelled
     */
    public boolean stopRendering(Player player) {
        UUID uuid = player.getUniqueId();
        BukkitTask t = activeTasks.remove(uuid);
        playerMaps.remove(uuid);
        if (t != null && !t.isCancelled()) {
            t.cancel();
            return true;
        }
        return false;
    }

    /** Returns {@code true} if the player currently has an active render task. */
    public boolean isRendering(Player player) {
        return activeTasks.containsKey(player.getUniqueId());
    }

    /** Stops all active render tasks (called during plugin disable). */
    public void stopAll() {
        for (BukkitTask task : activeTasks.values()) {
            if (!task.isCancelled())
                task.cancel();
        }
        activeTasks.clear();
        playerMaps.clear();
    }

    // -------------------------------------------------------------------------
    // Particle rendering (main thread)
    // -------------------------------------------------------------------------

    private void renderParticles(Player player, SpatialHashMap sm, int radius, int maxCells) {
        Location centre = player.getLocation();
        World world = centre.getWorld();
        if (world == null)
            return;

        // Gather nearby cells
        Map<CellKey, Integer> nearby = sm.cellsWithinRadius(
                centre.getX(), centre.getY(), centre.getZ(), radius);

        if (nearby.isEmpty())
            return;

        int maxDensity = nearby.values().stream().mapToInt(i -> i).max().orElse(1);
        int cellSize = sm.getCellSize();
        int rendered = 0;

        // Sort descending by density so hotspots are always shown if we hit the cap
        List<Map.Entry<CellKey, Integer>> sorted = new ArrayList<>(nearby.entrySet());
        sorted.sort((a, b) -> b.getValue() - a.getValue());

        for (Map.Entry<CellKey, Integer> entry : sorted) {
            if (rendered++ >= maxCells)
                break;

            CellKey key = entry.getKey();
            int density = entry.getValue();
            double norm = (double) density / maxDensity; // 0.0 – 1.0

            Color color = interpolateColor(norm);
            double[] ctr = key.center(cellSize);

            Location loc = new Location(world, ctr[0], ctr[1], ctr[2]);
            Particle.DustOptions dust = new Particle.DustOptions(color, 1.2f);

            // DustOptions particle is client-side; spawn only for this one player
            player.spawnParticle(Particle.REDSTONE, loc, 3, 0.3, 0.3, 0.3, 0, dust);
        }
    }

    // -------------------------------------------------------------------------
    // Color math
    // -------------------------------------------------------------------------

    /**
     * Interpolates a heatmap colour from blue (cold, t=0) → cyan → green →
     * yellow → red (hot, t=1) using piecewise HSL interpolation.
     *
     * <p>
     * This is a four-segment gradient across the 240°→0° hue arc:
     * </p>
     * 
     * <pre>
     *   [0.00 – 0.25]  blue  → cyan    (H 240 → 180)
     *   [0.25 – 0.50]  cyan  → green   (H 180 → 120)
     *   [0.50 – 0.75]  green → yellow  (H 120 → 60 )
     *   [0.75 – 1.00]  yellow→ red     (H  60 → 0  )
     * </pre>
     *
     * @param t normalised density value in [0, 1]
     */
    private static Color interpolateColor(double t) {
        t = Math.max(0.0, Math.min(1.0, t));

        // Hue: linearly map t from 240° (blue) down to 0° (red)
        float hue = (float) (240.0 - t * 240.0);
        float saturation = 1.0f;
        // Bump up brightness slightly for hot cells so they visually pop
        float brightness = (float) (0.7 + 0.3 * t);

        return hsvToColor(hue, saturation, brightness);
    }

    /**
     * Converts HSV (H in 0–360, S and V in 0–1) to a Bukkit {@link Color}.
     */
    private static Color hsvToColor(float h, float s, float v) {
        float r, g, b;

        if (s == 0) {
            r = g = b = v;
        } else {
            float sector = h / 60f;
            int i = (int) sector;
            float f = sector - i;
            float p = v * (1 - s);
            float q = v * (1 - s * f);
            float t2 = v * (1 - s * (1 - f));

            switch (i % 6) {
                case 0 -> {
                    r = v;
                    g = t2;
                    b = p;
                }
                case 1 -> {
                    r = q;
                    g = v;
                    b = p;
                }
                case 2 -> {
                    r = p;
                    g = v;
                    b = t2;
                }
                case 3 -> {
                    r = p;
                    g = q;
                    b = v;
                }
                case 4 -> {
                    r = t2;
                    g = p;
                    b = v;
                }
                default -> {
                    r = v;
                    g = p;
                    b = q;
                }
            }
        }

        return Color.fromRGB(
                (int) (r * 255),
                (int) (g * 255),
                (int) (b * 255));
    }
}
