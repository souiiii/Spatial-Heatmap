package com.heatmap.spatial;

import com.heatmap.model.DataPoint;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Groups raw {@link DataPoint} coordinates into uniform 3D grid cells and
 * maintains a density count per cell.
 *
 * <h3>Why spatial hashing?</h3>
 * Instead of rendering a particle for every individual DB row (which could be
 * millions), we bucket events into fixed-size cubes and render one particle per
 * cube whose colour encodes the event density. This keeps render calls O(cells)
 * rather than O(events).
 *
 * <h3>Thread-safety</h3>
 * Uses {@link ConcurrentHashMap} so the map can be populated from an async DB
 * thread while being read from the main thread for rendering.
 */
public class SpatialHashMap {

    private final int cellSize;
    private final ConcurrentHashMap<CellKey, Integer> densityMap = new ConcurrentHashMap<>();

    public SpatialHashMap(int cellSize) {
        this.cellSize = Math.max(1, cellSize);
    }

    // -------------------------------------------------------------------------
    // Population
    // -------------------------------------------------------------------------

    /**
     * Ingest a batch of {@link DataPoint}s, incrementing the density counter for
     * the cell that contains each point.
     */
    public void ingest(Collection<DataPoint> points) {
        for (DataPoint dp : points) {
            CellKey key = CellKey.of(dp.getX(), dp.getY(), dp.getZ(), cellSize);
            densityMap.merge(key, 1, Integer::sum);
        }
    }

    /**
     * Directly record a pre-computed (cellKey, count) pair, usually from a DB
     * SELECT … GROUP BY aggregate query.
     */
    public void record(CellKey key, int count) {
        densityMap.merge(key, count, Integer::sum);
    }

    /**
     * Clear all density data (used when reloading or switching event types).
     */
    public void clear() {
        densityMap.clear();
    }

    // -------------------------------------------------------------------------
    // Queries
    // -------------------------------------------------------------------------

    /** Returns the density count for a specific cell (0 if not present). */
    public int getDensity(CellKey key) {
        return densityMap.getOrDefault(key, 0);
    }

    /**
     * Returns all (CellKey → density) entries as an unmodifiable snapshot.
     * Safe to call from any thread.
     */
    public Map<CellKey, Integer> snapshot() {
        return Collections.unmodifiableMap(new HashMap<>(densityMap));
    }

    /**
     * Returns the maximum density value across all cells (used for normalisation).
     * Returns 1 when the map is empty to avoid division-by-zero.
     */
    public int maxDensity() {
        return densityMap.values().stream().mapToInt(Integer::intValue).max().orElse(1);
    }

    /** Total number of loaded cells. */
    public int cellCount() {
        return densityMap.size();
    }

    /** Configured grid resolution (blocks per cell side). */
    public int getCellSize() {
        return cellSize;
    }

    /**
     * Returns all cells whose centre falls within {@code radius} blocks of a
     * reference world position. Used by the renderer to cull distant cells.
     *
     * @param wx     reference world X
     * @param wy     reference world Y
     * @param wz     reference world Z
     * @param radius maximum distance from reference to cell centre (blocks)
     */
    public Map<CellKey, Integer> cellsWithinRadius(double wx, double wy, double wz, double radius) {
        double r2 = radius * radius;
        Map<CellKey, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<CellKey, Integer> entry : densityMap.entrySet()) {
            CellKey k = entry.getKey();
            double[] centre = k.center(cellSize);
            double dx = centre[0] - wx;
            double dy = centre[1] - wy;
            double dz = centre[2] - wz;
            if ((dx * dx + dy * dy + dz * dz) <= r2) {
                result.put(k, entry.getValue());
            }
        }
        return result;
    }
}
