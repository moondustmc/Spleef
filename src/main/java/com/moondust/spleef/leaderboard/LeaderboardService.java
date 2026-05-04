package com.moondust.spleef.leaderboard;

import com.moondust.spleef.Spleef;
import com.moondust.spleef.player.PlayerData;
import com.moondust.spleef.player.PlayerDataManager;
import com.moondust.spleef.util.Chat;
import com.moondust.spleef.util.LocationCodec;
import org.bukkit.Location;
import org.bukkit.block.Sign;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;

public final class LeaderboardService {
    private final Spleef plugin;
    private final PlayerDataManager dataManager;
    private final List<ArmorStand> hologramLines = new ArrayList<>();
    private BukkitTask task;

    public LeaderboardService(Spleef plugin, PlayerDataManager dataManager) {
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
                update();
            }
        }.runTaskTimer(plugin, 40L, 20L * 60L);
    }

    public void stopTasks() {
        if (task != null) {
            task.cancel();
        }
        clearHologram();
    }

    public void setHologram(Location location) {
        plugin.getConfig().set("leaderboards.hologram.location", null);
        ConfigurationSection section = plugin.getConfig().createSection("leaderboards.hologram.location");
        LocationCodec.writeLocation(section, location);
        plugin.getConfig().set("leaderboards.hologram.enabled", true);
        plugin.saveConfig();
        update();
    }

    public void addSign(Location location, int rank) {
        List<String> signs = new ArrayList<>(plugin.getConfig().getStringList("leaderboards.signs"));
        signs.add(LocationCodec.compact(location) + ":" + Math.max(1, rank));
        plugin.getConfig().set("leaderboards.signs", signs);
        plugin.saveConfig();
        update();
    }

    public void update() {
        updateHologram();
        updateSigns();
    }

    private void updateHologram() {
        clearHologram();
        if (!plugin.getConfig().getBoolean("leaderboards.hologram.enabled", false)) {
            return;
        }
        Location location = LocationCodec.readLocation(plugin.getConfig().getConfigurationSection("leaderboards.hologram.location"));
        if (location == null || location.getWorld() == null) {
            return;
        }
        for (Entity entity : location.getWorld().getNearbyEntities(location, 4, 4, 4)) {
            if (entity instanceof ArmorStand stand && stand.getScoreboardTags().contains("spleef-hologram")) {
                stand.remove();
            }
        }
        List<String> lines = hologramText(plugin.getConfig().getInt("leaderboards.hologram.lines", 10));
        for (int i = 0; i < lines.size(); i++) {
            Location lineLocation = location.clone().subtract(0, i * 0.28, 0);
            ArmorStand stand = location.getWorld().spawn(lineLocation, ArmorStand.class, armorStand -> {
                armorStand.setInvisible(true);
                armorStand.setMarker(true);
                armorStand.setGravity(false);
                armorStand.setCustomNameVisible(true);
            });
            stand.setCustomName(Chat.color(lines.get(i)));
            stand.addScoreboardTag("spleef-hologram");
            hologramLines.add(stand);
        }
    }

    private void updateSigns() {
        for (String value : plugin.getConfig().getStringList("leaderboards.signs")) {
            SignEntry entry = SignEntry.parse(value);
            if (entry == null || entry.location() == null || !(entry.location().getBlock().getState() instanceof Sign sign)) {
                continue;
            }
            PlayerData data = dataManager.topWins(entry.rank()).size() >= entry.rank()
                    ? dataManager.topWins(entry.rank()).get(entry.rank() - 1)
                    : null;
            sign.setLine(0, Chat.color("&b[Spleef]"));
            sign.setLine(1, Chat.color("&f#" + entry.rank() + " Wins"));
            if (data == null) {
                sign.setLine(2, Chat.color("&7Nobody"));
                sign.setLine(3, Chat.color("&70"));
            } else {
                sign.setLine(2, Chat.color("&f" + data.name()));
                sign.setLine(3, Chat.color("&a" + data.wins()));
            }
            sign.update(true, false);
        }
    }

    private List<String> hologramText(int amount) {
        List<String> lines = new ArrayList<>();
        lines.add(plugin.getConfig().getString("leaderboards.hologram.title", "&b&lSpleef Wins"));
        List<PlayerData> top = dataManager.topWins(amount);
        for (int i = 0; i < amount; i++) {
            if (i >= top.size()) {
                lines.add("&7#" + (i + 1) + " Nobody - 0");
            } else {
                PlayerData data = top.get(i);
                lines.add("&f#" + (i + 1) + " &b" + data.name() + " &7- &a" + data.wins());
            }
        }
        return lines;
    }

    private void clearHologram() {
        for (ArmorStand stand : hologramLines) {
            if (stand != null && !stand.isDead()) {
                stand.remove();
            }
        }
        hologramLines.clear();
    }

    private record SignEntry(Location location, int rank) {
        private static SignEntry parse(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            String[] parts = value.split(":");
            if (parts.length < 5) {
                return null;
            }
            Location location = LocationCodec.fromCompact(String.join(":", parts[0], parts[1], parts[2], parts[3]));
            try {
                return new SignEntry(location, Integer.parseInt(parts[4]));
            } catch (NumberFormatException ignored) {
                return new SignEntry(location, 1);
            }
        }
    }
}
