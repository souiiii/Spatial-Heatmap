package com.heatmap.listeners;

import com.heatmap.engine.AnalyticsEngine;
import com.heatmap.model.EventType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

/**
 * Listens for player death events and records them to the analytics engine.
 *
 * Uses {@link EventPriority#MONITOR} so we observe the final, committed state
 * of
 * the event (after other plugins may have modified it) without interfering with
 * it.
 */
public class PlayerDeathListener implements Listener {

    private final AnalyticsEngine engine;

    public PlayerDeathListener(AnalyticsEngine engine) {
        this.engine = engine;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerDeath(PlayerDeathEvent event) {
        engine.recordEvent(
                EventType.DEATH,
                event.getEntity().getLocation(),
                event.getEntity().getName());
    }
}
