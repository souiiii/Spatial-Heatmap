package com.heatmap.spatial;

import java.util.Objects;

/**
 * Immutable key identifying a single cell in the 3D spatial hash grid.
 *
 * The grid divides the world into uniform cubes of side length
 * {@code cellSize}.
 * Two coordinates that fall within the same cube share the same CellKey and
 * therefore contribute to the same density count.
 *
 * Hashing formula:
 * cellX = Math.floor(x / cellSize)
 * cellY = Math.floor(y / cellSize)
 * cellZ = Math.floor(z / cellSize)
 */
public final class CellKey {

    public final int cx; // cell column
    public final int cy; // cell row (height)
    public final int cz; // cell depth

    public CellKey(int cx, int cy, int cz) {
        this.cx = cx;
        this.cy = cy;
        this.cz = cz;
    }

    /**
     * Factory method — converts a world coordinate to the containing cell.
     *
     * @param x        world X
     * @param y        world Y
     * @param z        world Z
     * @param cellSize grid resolution in blocks (must be > 0)
     */
    public static CellKey of(double x, double y, double z, int cellSize) {
        return new CellKey(
                (int) Math.floor(x / cellSize),
                (int) Math.floor(y / cellSize),
                (int) Math.floor(z / cellSize));
    }

    /**
     * Returns the world-space center point of this cell.
     *
     * @param cellSize grid resolution used to create this key
     */
    public double[] center(int cellSize) {
        return new double[] {
                (cx * cellSize) + cellSize / 2.0,
                (cy * cellSize) + cellSize / 2.0,
                (cz * cellSize) + cellSize / 2.0
        };
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (!(o instanceof CellKey))
            return false;
        CellKey k = (CellKey) o;
        return cx == k.cx && cy == k.cy && cz == k.cz;
    }

    @Override
    public int hashCode() {
        // Use prime-based mixing to spread cell keys uniformly across the hash table.
        return Objects.hash(cx, cy, cz);
    }

    @Override
    public String toString() {
        return "(" + cx + "," + cy + "," + cz + ")";
    }
}
