package com.heatmap.model;

/**
 * Immutable POJO representing a single analytics event captured during
 * gameplay.
 *
 * This is the unit of data that flows through the entire pipeline:
 * Event Listener → BatchWriteQueue → DatabaseManager → SpatialHashMap →
 * HeatmapRenderer
 */
public final class DataPoint {

    private final String world;
    private final double x;
    private final double y;
    private final double z;
    private final EventType eventType;
    private final String metadata; // e.g. player name, block material
    private final long timestamp; // epoch milliseconds

    public DataPoint(String world, double x, double y, double z,
            EventType eventType, String metadata) {
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.eventType = eventType;
        this.metadata = metadata;
        this.timestamp = System.currentTimeMillis();
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public String getWorld() {
        return world;
    }

    public double getX() {
        return x;
    }

    public double getY() {
        return y;
    }

    public double getZ() {
        return z;
    }

    public EventType getEventType() {
        return eventType;
    }

    public String getMetadata() {
        return metadata;
    }

    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return String.format("DataPoint{world='%s', x=%.1f, y=%.1f, z=%.1f, type=%s, meta='%s'}",
                world, x, y, z, eventType, metadata);
    }
}
