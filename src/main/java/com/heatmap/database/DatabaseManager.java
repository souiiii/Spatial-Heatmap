package com.heatmap.database;

import com.heatmap.HeatmapPlugin;
import com.heatmap.model.DataPoint;
import com.heatmap.model.EventType;
import com.heatmap.spatial.CellKey;

import java.io.File;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Manages all SQLite database interactions.
 *
 * <h3>Key design decisions</h3>
 * <ul>
 * <li>{@link #batchInsert(List)} uses a single {@link PreparedStatement} with
 * {@code addBatch()} / {@code executeBatch()} wrapped in a transaction.
 * This is orders of magnitude faster than one INSERT per event.</li>
 * <li>All methods in this class MUST be called from an async thread.
 * The plugin enforces this via {@code BatchWriteQueue}.</li>
 * <li>The connection is kept open for the lifetime of the plugin; SQLite WAL
 * mode is enabled for better concurrent read performance.</li>
 * </ul>
 */
public class DatabaseManager {

    private static final String INSERT_SQL = "INSERT INTO heatmap_events (world, x, y, z, event_type, metadata, timestamp) "
            +
            "VALUES (?, ?, ?, ?, ?, ?, ?)";

    private static final String COUNT_SQL = "SELECT COUNT(*) FROM heatmap_events";

    private static final String COUNT_BY_TYPE_SQL = "SELECT event_type, COUNT(*) AS cnt FROM heatmap_events GROUP BY event_type";

    /**
     * Query aggregated density per spatial cell for a given world + event type.
     * The GROUP BY collapses individual rows into cell-level counts.
     *
     * cellSize is embedded at query time via integer division so that the DB can
     * do the grouping without pushing millions of rows to Java.
     */
    private static final String QUERY_DENSITY_SQL = "SELECT CAST(FLOOR(x / ?) AS INTEGER), " +
            "       CAST(FLOOR(y / ?) AS INTEGER), " +
            "       CAST(FLOOR(z / ?) AS INTEGER), " +
            "       COUNT(*) " +
            "FROM heatmap_events " +
            "WHERE world = ? AND event_type = ? " +
            "GROUP BY CAST(FLOOR(x / ?) AS INTEGER), " +
            "         CAST(FLOOR(y / ?) AS INTEGER), " +
            "         CAST(FLOOR(z / ?) AS INTEGER)";

    private final HeatmapPlugin plugin;
    private Connection connection;

    public DatabaseManager(HeatmapPlugin plugin) {
        this.plugin = plugin;
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Opens (or creates) the SQLite database and ensures the schema exists.
     * Must be called during plugin enable, before any other method.
     */
    public void initialize() throws SQLException {
        File dbFile = new File(plugin.getDataFolder(), plugin.getConfig().getString("database-file", "heatmap.db"));
        plugin.getDataFolder().mkdirs();

        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath();
        connection = DriverManager.getConnection(url);

        // Enable WAL journal mode for better concurrent read performance
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL;");
            st.execute("PRAGMA synchronous=NORMAL;");
            st.execute("CREATE TABLE IF NOT EXISTS heatmap_events (" +
                    "id         INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "world      TEXT    NOT NULL," +
                    "x          REAL    NOT NULL," +
                    "y          REAL    NOT NULL," +
                    "z          REAL    NOT NULL," +
                    "event_type TEXT    NOT NULL," +
                    "metadata   TEXT," +
                    "timestamp  INTEGER NOT NULL" +
                    ");");
            // Composite index on the columns most commonly filtered/grouped
            st.execute("CREATE INDEX IF NOT EXISTS idx_world_type ON heatmap_events(world, event_type);");
        }

        plugin.getLogger().info("[DatabaseManager] SQLite database ready: " + dbFile.getName());
    }

    /**
     * Gracefully closes the database connection.
     * Called during plugin disable, after the final queue flush.
     */
    public void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "[DatabaseManager] Error closing connection.", e);
        }
    }

    // -------------------------------------------------------------------------
    // Write
    // -------------------------------------------------------------------------

    /**
     * Inserts a batch of {@link DataPoint}s in a single database transaction.
     *
     * <p>
     * Using a transaction + executeBatch() keeps INSERT throughput high even
     * for thousands of rows; SQLite can sustain ~50,000 rows/sec this way.
     * </p>
     *
     * @param batch list of data points to persist (may be empty)
     */
    public void batchInsert(List<DataPoint> batch) {
        if (batch.isEmpty())
            return;

        try {
            connection.setAutoCommit(false);
            try (PreparedStatement ps = connection.prepareStatement(INSERT_SQL)) {
                for (DataPoint dp : batch) {
                    ps.setString(1, dp.getWorld());
                    ps.setDouble(2, dp.getX());
                    ps.setDouble(3, dp.getY());
                    ps.setDouble(4, dp.getZ());
                    ps.setString(5, dp.getEventType().name());
                    ps.setString(6, dp.getMetadata());
                    ps.setLong(7, dp.getTimestamp());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            connection.commit();
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "[DatabaseManager] Batch insert failed. Rolling back.", e);
            try {
                connection.rollback();
            } catch (SQLException ignored) {
            }
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
            }
        }
    }

    // -------------------------------------------------------------------------
    // Read
    // -------------------------------------------------------------------------

    /**
     * Loads aggregated spatial density data for a world + event type into a list
     * of {@code Object[]{CellKey, count}} pairs.
     *
     * <p>
     * All aggregation happens in SQL so only one row per cell is returned to
     * Java, keeping memory usage proportional to distinct cells, not raw events.
     * </p>
     *
     * @param world     world name to filter
     * @param eventType event category to filter
     * @param cellSize  grid resolution (blocks per cell side)
     */
    public List<Object[]> queryDensity(String world, EventType eventType, int cellSize) {
        List<Object[]> rows = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(QUERY_DENSITY_SQL)) {
            int cs = cellSize;
            ps.setInt(1, cs);
            ps.setInt(2, cs);
            ps.setInt(3, cs);
            ps.setString(4, world);
            ps.setString(5, eventType.name());
            ps.setInt(6, cs);
            ps.setInt(7, cs);
            ps.setInt(8, cs);

            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    CellKey key = new CellKey(rs.getInt(1), rs.getInt(2), rs.getInt(3));
                    int count = rs.getInt(4);
                    rows.add(new Object[] { key, count });
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.SEVERE, "[DatabaseManager] Density query failed.", e);
        }
        return rows;
    }

    /**
     * Returns a summary string: total events and count per type.
     */
    public String getStats() {
        StringBuilder sb = new StringBuilder();
        try (Statement st = connection.createStatement();
                ResultSet rs = st.executeQuery(COUNT_SQL)) {
            if (rs.next()) {
                sb.append("Total events: ").append(rs.getInt(1)).append('\n');
            }
        } catch (SQLException e) {
            sb.append("(error reading total)\n");
        }

        try (Statement st = connection.createStatement();
                ResultSet rs = st.executeQuery(COUNT_BY_TYPE_SQL)) {
            while (rs.next()) {
                sb.append("  ").append(rs.getString(1))
                        .append(": ").append(rs.getInt(2)).append('\n');
            }
        } catch (SQLException e) {
            sb.append("(error reading breakdown)\n");
        }

        return sb.toString().trim();
    }
}
