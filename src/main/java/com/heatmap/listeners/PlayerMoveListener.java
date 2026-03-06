package com.heatmap.listeners;

import com.heatmap.engine.AnalyticsEngine;
import com.heatmap.model.EventType;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Periodically snapshots player positions and records them as
 * {@link EventType#PLAYER_MOVE}
 * events.
 *
 * <h3>Why not use PlayerMoveEvent?</h3>
 * {@code PlayerMoveEvent} fires on every packet — hundreds of times per second
 * per
 * player. Even with look-only filtering, this produces far more data than is
 * meaningful and burns significant CPU. A scheduled snapshot every N ticks
 * (default
 * 100 = 5 s) is more than sufficient for a heat-of-activity map and incurs
 * negligible
 * overhead.
 */
public class PlayerMoveListener {

    private final AnalyticsEngine engine;
    private final Plugin plugin;
    private BukkitTask task;

    public PlayerMoveListener(AnalyticsEngine engine) {
        this.engine = engine;
        this.plugin = engine.getPlugin();
    }

    /** Starts the position-snapshot scheduler. */
    public void start() {
        int interval = plugin.getConfig().getInt("track-events.player-move-interval-ticks", 100);

        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                engine.recordEvent(
                        EventType.PLAYER_MOVE,
                        player.getLocation(),
                        player.getName());
            }
        }, interval, interval);

        plugin.getLogger().info("[PlayerMoveListener] Snapshot scheduler started (every " + interval + " ticks).");
    }

    /** Cancels the scheduler. Must be called during plugin disable. */
    public void stop() {
        if (task != null && !task.isCancelled()) {
            task.cancel();
        }
    }
}
