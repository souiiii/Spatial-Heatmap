package com.heatmap.listeners;

import com.heatmap.engine.AnalyticsEngine;
import com.heatmap.model.EventType;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Tracks block-break events, optionally filtering to a specific set of
 * "interesting" blocks (ores, ancient debris, etc.) as configured in
 * config.yml.
 *
 * <p>
 * If the interesting-blocks list is empty, <em>all</em> block breaks are
 * tracked
 * (e.g. for research into player digging patterns).
 * </p>
 */
public class BlockBreakListener implements Listener {

    private final AnalyticsEngine engine;
    private final Set<Material> trackedMaterials;
    private final boolean trackAll;

    public BlockBreakListener(AnalyticsEngine engine) {
        this.engine = engine;

        List<String> configured = engine.getPlugin().getConfig().getStringList("interesting-blocks");
        if (configured.isEmpty()) {
            this.trackAll = true;
            this.trackedMaterials = EnumSet.noneOf(Material.class);
        } else {
            this.trackAll = false;
            Set<Material> set = EnumSet.noneOf(Material.class);
            for (String name : configured) {
                try {
                    set.add(Material.valueOf(name.toUpperCase()));
                } catch (IllegalArgumentException e) {
                    engine.getPlugin().getLogger().warning("[BlockBreakListener] Unknown material in config: " + name);
                }
            }
            this.trackedMaterials = set;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Material mat = event.getBlock().getType();

        if (!trackAll && !trackedMaterials.contains(mat)) {
            return; // not a block we care about
        }

        engine.recordEvent(
                EventType.BLOCK_BREAK,
                event.getBlock().getLocation(),
                mat.name());
    }
}
