package com.heatmap.commands;

import com.heatmap.HeatmapPlugin;
import com.heatmap.engine.AnalyticsEngine;
import com.heatmap.model.EventType;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Handles the /heatmap command and its subcommands.
 */
public class HeatmapCommand implements CommandExecutor, TabCompleter {

    private final HeatmapPlugin plugin;
    private final AnalyticsEngine engine;

    public HeatmapCommand(HeatmapPlugin plugin, AnalyticsEngine engine) {
        this.plugin = plugin;
        this.engine = engine;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("heatmap.admin")) {
            sender.sendMessage("§cYou don't have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "show":
                handleShow(sender, args);
                break;
            case "stop":
                handleStop(sender);
                break;
            case "info":
                handleInfo(sender);
                break;
            case "reload":
                handleReload(sender);
                break;
            default:
                sendHelp(sender);
                break;
        }

        return true;
    }

    private void handleShow(CommandSender sender, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cOnly players can view heatmaps.");
            return;
        }

        Player player = (Player) sender;

        if (args.length < 2) {
            player.sendMessage("§cUsage: /heatmap show <death|block_break|player_move> [radius]");
            return;
        }

        EventType eventType = EventType.fromString(args[1]);
        if (eventType == null) {
            player.sendMessage("§cInvalid event type. Available types: " +
                    Arrays.stream(EventType.values()).map(Enum::name).collect(Collectors.joining(", ")));
            return;
        }

        int radius = plugin.getConfig().getInt("render-radius", 64);
        if (args.length >= 3) {
            try {
                radius = Integer.parseInt(args[2]);
            } catch (NumberFormatException e) {
                player.sendMessage("§cRadius must be a number. Using default " + radius + ".");
            }
        }

        engine.getRenderer().startRendering(player, eventType, radius);
    }

    private void handleStop(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage("§cOnly players can stop heatmaps.");
            return;
        }

        Player player = (Player) sender;
        if (engine.getRenderer().stopRendering(player)) {
            player.sendMessage("§6[Heatmap] §aHeatmap rendering stopped.");
        } else {
            player.sendMessage("§cYou don't have an active heatmap rendering.");
        }
    }

    private void handleInfo(CommandSender sender) {
        sender.sendMessage("§6=== Heatmap Analytics Info ===");
        sender.sendMessage("§eQueue Size: §f" + engine.getWriteQueue().size() + " events waiting to flush");

        // Fetch DB stats asynchronously so we don't block the main thread
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            String stats = engine.getDbManager().getStats();
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                sender.sendMessage("§eDatabase Stats:\n§f" + stats);
            });
        });
    }

    private void handleReload(CommandSender sender) {
        plugin.reloadConfig();
        sender.sendMessage("§6[Heatmap] §aConfiguration reloaded.");
        sender.sendMessage(
                "§7Note: Changes to DB or major scheduling intervals may require a server restart to apply cleanly.");
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6=== Heatmap Commands ===");
        sender.sendMessage("§e/heatmap show <type> [radius] §7- Show heatmap for an event type");
        sender.sendMessage("§e/heatmap stop §7- Stop your active heatmap");
        sender.sendMessage("§e/heatmap info §7- View analytics queue and db stats");
        sender.sendMessage("§e/heatmap reload §7- Reload config.yml");
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("heatmap.admin"))
            return new ArrayList<>();

        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            String partial = args[0].toLowerCase();
            for (String sub : new String[] { "show", "stop", "info", "reload" }) {
                if (sub.startsWith(partial))
                    completions.add(sub);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("show")) {
            String partial = args[1].toLowerCase();
            for (EventType type : EventType.values()) {
                String typeName = type.name().toLowerCase();
                if (typeName.startsWith(partial)) {
                    completions.add(typeName);
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("show")) {
            if ("64".startsWith(args[2]))
                completions.add("64");
            if ("128".startsWith(args[2]))
                completions.add("128");
        }

        return completions;
    }
}
