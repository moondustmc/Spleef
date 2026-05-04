package com.moondust.spleef.game;

import com.moondust.spleef.util.LocationCodec;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;

import java.util.function.Consumer;

public final class ArenaMap {
    private final String id;
    private final String displayName;
    private final String worldName;
    private final Location spawn;
    private final Location lobby;
    private final Integer minX;
    private final Integer minY;
    private final Integer minZ;
    private final Integer maxX;
    private final Integer maxY;
    private final Integer maxZ;
    private final Integer snowMinX;
    private final Integer snowMinY;
    private final Integer snowMinZ;
    private final Integer snowMaxX;
    private final Integer snowMaxY;
    private final Integer snowMaxZ;

    public ArenaMap(String id, String displayName, String worldName, Location spawn, Location lobby,
                    Integer minX, Integer minY, Integer minZ, Integer maxX, Integer maxY, Integer maxZ,
                    Integer snowMinX, Integer snowMinY, Integer snowMinZ, Integer snowMaxX, Integer snowMaxY, Integer snowMaxZ) {
        this.id = id;
        this.displayName = displayName;
        this.worldName = worldName;
        this.spawn = spawn;
        this.lobby = lobby;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.snowMinX = snowMinX;
        this.snowMinY = snowMinY;
        this.snowMinZ = snowMinZ;
        this.snowMaxX = snowMaxX;
        this.snowMaxY = snowMaxY;
        this.snowMaxZ = snowMaxZ;
    }

    public static ArenaMap fromConfig(String id, ConfigurationSection section) {
        if (section == null) {
            return new ArenaMap(id, id, "", null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null);
        }
        String worldName = section.getString("world", "world");
        Location spawn = LocationCodec.readLocation(withWorld(section.getConfigurationSection("spawn"), worldName));
        Location lobby = LocationCodec.readLocation(withWorld(section.getConfigurationSection("lobby"), worldName));
        ConfigurationSection pos1 = section.getConfigurationSection("arena.pos1");
        ConfigurationSection pos2 = section.getConfigurationSection("arena.pos2");
        Integer minX = null;
        Integer minY = null;
        Integer minZ = null;
        Integer maxX = null;
        Integer maxY = null;
        Integer maxZ = null;
        if (pos1 != null && pos2 != null) {
            int x1 = pos1.getInt("x");
            int y1 = pos1.getInt("y");
            int z1 = pos1.getInt("z");
            int x2 = pos2.getInt("x");
            int y2 = pos2.getInt("y");
            int z2 = pos2.getInt("z");
            minX = Math.min(x1, x2);
            minY = Math.min(y1, y2);
            minZ = Math.min(z1, z2);
            maxX = Math.max(x1, x2);
            maxY = Math.max(y1, y2);
            maxZ = Math.max(z1, z2);
        }
        Bounds snowBounds = readBounds(section.getConfigurationSection("snow-level.pos1"), section.getConfigurationSection("snow-level.pos2"));
        return new ArenaMap(id, section.getString("name", id), worldName, spawn, lobby,
                minX, minY, minZ, maxX, maxY, maxZ,
                snowBounds.minX(), snowBounds.minY(), snowBounds.minZ(), snowBounds.maxX(), snowBounds.maxY(), snowBounds.maxZ());
    }

    private static ConfigurationSection withWorld(ConfigurationSection section, String worldName) {
        if (section != null && section.getString("world") == null) {
            section.set("world", worldName);
        }
        return section;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public World world() {
        return Bukkit.getWorld(worldName);
    }

    public Location spawn() {
        return spawn == null ? null : spawn.clone();
    }

    public Location lobby() {
        return lobby == null ? null : lobby.clone();
    }

    public boolean ready() {
        return world() != null && spawn != null && lobby != null && minX != null;
    }

    public boolean inArena(Location location) {
        if (!ready() || location == null || location.getWorld() == null || !location.getWorld().getName().equals(worldName)) {
            return false;
        }
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    public boolean inHorizontalArena(Location location) {
        if (!ready() || location == null || location.getWorld() == null || !location.getWorld().getName().equals(worldName)) {
            return false;
        }
        int x = location.getBlockX();
        int z = location.getBlockZ();
        return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    public int minY() {
        return minY == null ? 0 : minY;
    }

    public int snowMinY() {
        if (snowConfigured()) {
            return snowMinY;
        }
        return minY();
    }

    public boolean inSnowLevel(Location location) {
        if (!ready() || location == null || location.getWorld() == null || !location.getWorld().getName().equals(worldName)) {
            return false;
        }
        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        if (snowConfigured()) {
            return x >= snowMinX && x <= snowMaxX && y >= snowMinY && y <= snowMaxY && z >= snowMinZ && z <= snowMaxZ;
        }
        return x >= minX && x <= maxX && y == minY && z >= minZ && z <= maxZ;
    }

    public void forEachArenaBlock(Consumer<Block> consumer) {
        World world = world();
        if (!ready() || world == null) {
            return;
        }
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    consumer.accept(world.getBlockAt(x, y, z));
                }
            }
        }
    }

    public void forEachSnowBlock(Consumer<Block> consumer) {
        World world = world();
        if (!ready() || world == null) {
            return;
        }
        int fromX = snowConfigured() ? snowMinX : minX;
        int fromY = snowConfigured() ? snowMinY : minY;
        int fromZ = snowConfigured() ? snowMinZ : minZ;
        int toX = snowConfigured() ? snowMaxX : maxX;
        int toY = snowConfigured() ? snowMaxY : minY;
        int toZ = snowConfigured() ? snowMaxZ : maxZ;
        for (int x = fromX; x <= toX; x++) {
            for (int y = fromY; y <= toY; y++) {
                for (int z = fromZ; z <= toZ; z++) {
                    consumer.accept(world.getBlockAt(x, y, z));
                }
            }
        }
    }

    private boolean snowConfigured() {
        return snowMinX != null;
    }

    private static Bounds readBounds(ConfigurationSection pos1, ConfigurationSection pos2) {
        if (pos1 == null || pos2 == null) {
            return Bounds.empty();
        }
        int x1 = pos1.getInt("x");
        int y1 = pos1.getInt("y");
        int z1 = pos1.getInt("z");
        int x2 = pos2.getInt("x");
        int y2 = pos2.getInt("y");
        int z2 = pos2.getInt("z");
        return new Bounds(
                Math.min(x1, x2),
                Math.min(y1, y2),
                Math.min(z1, z2),
                Math.max(x1, x2),
                Math.max(y1, y2),
                Math.max(z1, z2)
        );
    }

    private record Bounds(Integer minX, Integer minY, Integer minZ, Integer maxX, Integer maxY, Integer maxZ) {
        private static Bounds empty() {
            return new Bounds(null, null, null, null, null, null);
        }
    }
}
