package com.heatmap.engine;

import com.heatmap.HeatmapPlugin;
import com.heatmap.database.DatabaseManager;
import com.heatmap.model.DataPoint;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * In-memory, non-blocking event queue with periodic async batch flushing.
 *
 * <h3>Big-Data design rationale</h3>
 * <ul>
 * <li>Events are enqueued on the <em>main server thread</em> in O(1) time with
 * no I/O or lock contention — the server tick is never stalled.</li>
 * <li>A dedicated async task drains the queue every N ticks (default 200 = 10s)
 * and executes a single batch SQL INSERT covering all accumulated events.</li>
 * <li>An overflow guard triggers an early flush when the queue exceeds
 * {@code maxQueueSize}, preventing unbounded memory growth under extreme
 * load.</li>
 * <li>On plugin disable, {@link #forceFlush()} performs a final synchronous
 * drain
 * so no events are lost.</li>
 * </ul>
 */
public class BatchWriteQueue {

    private final HeatmapPlugin plugin;
    private final DatabaseManager dbManager;
    private final int batchSize;
    private final int maxQueueSize;

    /** Non-blocking queue: producers (main thread) never wait on consumers. */
    private final ConcurrentLinkedQueue<DataPoint> queue = new ConcurrentLinkedQueue<>();

    /**
     * Guards against two simultaneous flushes (e.g. overflow flush + scheduled
     * flush).
     */
    private final AtomicBoolean flushing = new AtomicBoolean(false);

    private BukkitTask scheduledTask;

    public BatchWriteQueue(HeatmapPlugin plugin, DatabaseManager dbManager) {
        this.plugin = plugin;
        this.dbManager = dbManager;
        this.batchSize = plugin.getConfig().getInt("batch-size", 500);
        this.maxQueueSize = plugin.getConfig().getInt("max-queue-size", 50_000);
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Starts the periodic flush scheduler. Must be called after the DB is ready.
     */
    public void start() {
        int intervalTicks = plugin.getConfig().getInt("flush-interval-ticks", 200);
        scheduledTask = plugin.getServer().getScheduler().runTaskTimerAsynchronously(
                plugin,
                this::flush,
                intervalTicks,
                intervalTicks);
        plugin.getLogger().info("[BatchWriteQueue] Flush scheduler started (every " + intervalTicks + " ticks).");
    }

    /**
     * Cancels the scheduled task. Call before {@link #forceFlush()} during
     * shutdown.
     */
    public void stop() {
        if (scheduledTask != null && !scheduledTask.isCancelled()) {
            scheduledTask.cancel();
        }
    }

    // -------------------------------------------------------------------------
    // Public API (main-thread safe)
    // -------------------------------------------------------------------------

    /**
     * Enqueues a single {@link DataPoint}. This is O(1) and never blocks.
     *
     * If the queue exceeds {@code maxQueueSize}, an early flush is triggered
     * asynchronously.
     */
    public void add(DataPoint point) {
        queue.offer(point);

        // Overflow guard: trigger early flush async if we're getting too big
        if (queue.size() > maxQueueSize) {
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, this::flush);
        }
    }

    /**
     * Blocks the calling thread until all queued events are written to the DB.
     * <strong>Must only be called from a NON-main thread</strong> (e.g. inside
     * plugin disable's async executor, or the final shutdown hook).
     */
    public void forceFlush() {
        flush();
        plugin.getLogger().info("[BatchWriteQueue] Force flush complete.");
    }

    /** Returns the current number of events waiting to be flushed. */
    public int size() {
        return queue.size();
    }

    // -------------------------------------------------------------------------
    // Flush logic (runs async)
    // -------------------------------------------------------------------------

    /**
     * Drains the queue in {@code batchSize} chunks, persisting each chunk via
     * {@link DatabaseManager#batchInsert(List)}.
     *
     * The {@code flushing} flag ensures two concurrent calls don't both drain the
     * same elements and double-count them.
     */
    private void flush() {
        if (!flushing.compareAndSet(false, true)) {
            return; // another flush is already running
        }
        try {
            List<DataPoint> batch = new ArrayList<>(batchSize);
            DataPoint dp;

            while ((dp = queue.poll()) != null) {
                batch.add(dp);

                if (batch.size() >= batchSize) {
                    dbManager.batchInsert(batch);
                    batch = new ArrayList<>(batchSize);
                }
            }

            // flush remainder
            if (!batch.isEmpty()) {
                dbManager.batchInsert(batch);
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "[BatchWriteQueue] Unexpected error during flush.", e);
        } finally {
            flushing.set(false);
        }
    }
}
