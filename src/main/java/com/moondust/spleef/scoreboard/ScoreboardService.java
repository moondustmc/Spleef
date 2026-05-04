package com.moondust.spleef.scoreboard;

import com.moondust.spleef.Spleef;
import com.moondust.spleef.player.PlayerData;
import com.moondust.spleef.player.PlayerDataManager;
import com.moondust.spleef.util.Chat;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;

import java.util.List;

public final class ScoreboardService {
    private final Spleef plugin;
    private final PlayerDataManager dataManager;
    private BukkitTask task;

    public ScoreboardService(Spleef plugin, PlayerDataManager dataManager) {
        this.plugin = plugin;
        this.dataManager = dataManager;
    }

    public void startTasks() {
        if (task != null) {
            task.cancel();
        }
        task = new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    update(player);
                }
            }
        }.runTaskTimer(plugin, 20L, 40L);
    }

    public void stopTasks() {
        if (task != null) {
            task.cancel();
        }
    }

    public void update(Player player) {
        if (!plugin.getConfig().getBoolean("scoreboard.enabled", true)) {
            return;
        }
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) {
            return;
        }
        PlayerData data = dataManager.get(player);
        Scoreboard scoreboard = manager.getNewScoreboard();
        Objective objective = scoreboard.registerNewObjective("spleef", "dummy", Chat.color(plugin.getConfig().getString("scoreboard.title", "&b&lSpleef")));
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        List<String> lines = plugin.getConfig().getStringList("scoreboard.lines");
        int score = lines.size();
        int unique = 0;
        for (String raw : lines) {
            String line = placeholders(raw, player, data);
            objective.getScore(uniqueLine(Chat.color(line), unique)).setScore(score);
            score--;
            unique++;
        }
        player.setScoreboard(scoreboard);
    }

    private String placeholders(String line, Player player, PlayerData data) {
        return line
                .replace("{global_multiplier}", formatMultiplier(dataManager.globalMultiplier()))
                .replace("{total_multiplier}", formatMultiplier(dataManager.totalMultiplier(player)))
                .replace("{wins}", Integer.toString(data.wins()))
                .replace("{streak}", Integer.toString(data.currentStreak()))
                .replace("{coins}", dataManager.formatCoins(data.coins()));
    }

    private String uniqueLine(String line, int index) {
        String suffix = ChatColor.COLOR_CHAR + Integer.toHexString(index % 16);
        if (line.length() + suffix.length() > 40) {
            line = line.substring(0, Math.max(0, 40 - suffix.length()));
        }
        return line + suffix;
    }

    private String formatMultiplier(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.001) {
            return Long.toString(Math.round(value));
        }
        return String.format("%.2f", value);
    }
}
