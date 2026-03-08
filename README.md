# Spatial Heatmap Analytics Engine

A background analytics engine that logs player events and visualizes them as 3D particle heatmaps in-game. Built for Paper MC 1.20.

## Features

- **Event Tracking:** Tracks player deaths, block breaks, and player movements.
- **Asynchronous Processing:** Uses a batch write queue to efficiently store data into an SQLite database without lagging the main server thread.
- **Spatial Hashing:** Groups event data using spatial hashing for efficient querying and visualization.
- **In-Game Heatmaps:** Visualizes activity density using colored particles, reflecting the concentration of events in a given area.

## Prerequisites

- Java 17 or higher
- Maven 3.x
- A Paper MC 1.20+ server

## Build Instructions

To build the plugin from source, clone the repository and run the following Maven command:

```bash
mvn clean package
```

This will produce a shaded JAR file located at `target/SpatialHeatmap-1.0.0.jar`. The shade plugin ensures that the required SQLite JDBC driver is bundled correctly.

## Installation / Run Instructions

1. Copy the built `SpatialHeatmap-1.0.0.jar` from the `target/` directory into your server's `plugins/` directory.
2. Start or restart your server.
3. On the first run, the plugin will generate a default `config.yml` in `plugins/SpatialHeatmap/` and initialize the local SQLite database (`heatmap.db`).

## Commands

All commands are executed via the root `/heatmap` (or `/hm`) command and require the `heatmap.admin` permission.

- `/heatmap show <death|block_break|player_move> [radius]` - Displays a visual particle heatmap around you for the specified event type.
- `/heatmap stop` - Stops your active heatmap rendering.
- `/heatmap info` - View current analytics queue and database statistics.
- `/heatmap reload` - Reloads the `config.yml`.

## Configuration

The `config.yml` allows you to customize the behavior of the analytics engine and the visualization:

- **Batch Writing Settings:** Control flush intervals (default 200 ticks / 10s) and batch size for DB writes.
- **Spatial Hashing:** Adjust `cell-size`. A smaller value gives a more granular heatmap but increases database writes.
- **Visualization:** Set default `render-radius` and refresh intervals.
- **Event Tracking:** Toggle specific event tracking (deaths, block breaks, player moves). For block breaks, you can specify exactly which blocks to track under `interesting-blocks`.

## Permissions

- `heatmap.admin` - Grants full access to all heatmap commands. Defaults to server operators (`op`).
