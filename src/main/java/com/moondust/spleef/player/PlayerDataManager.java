package com.moondust.spleef.player;

import com.moondust.spleef.content.ContentRegistry;
import com.moondust.spleef.content.CosmeticDefinition;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class PlayerDataManager {
    private final JavaPlugin plugin;
    private final Map<UUID, PlayerData> players = new HashMap<>();
    private File file;
    private YamlConfiguration yaml;
    private ContentRegistry registry;
    private double serverBoosterMultiplier = 1.0;
    private long serverBoosterEndsAt = 0L;

    public PlayerDataManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void setRegistry(ContentRegistry registry) {
        this.registry = registry;
        for (PlayerData data : players.values()) {
            applyDefaults(data);
        }
    }

    public void load() {
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }
        file = new File(plugin.getDataFolder(), "players.yml");
        yaml = YamlConfiguration.loadConfiguration(file);
        players.clear();
        ConfigurationSection section = yaml.getConfigurationSection("players");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                ConfigurationSection playerSection = section.getConfigurationSection(key);
                if (playerSection == null) {
                    continue;
                }
                PlayerData data = new PlayerData(uuid, playerSection.getString("name", key));
                data.wins(playerSection.getInt("wins", 0));
                data.currentStreak(playerSection.getInt("current-streak", 0));
                data.coins(playerSection.getDouble("coins", 0.0));
                data.ownedCosmetics().addAll(playerSection.getStringList("owned-cosmetics"));
                ConfigurationSection equipped = playerSection.getConfigurationSection("equipped-cosmetics");
                if (equipped != null) {
                    readEquippedCosmetics(equipped, "", data.equippedCosmetics());
                }
                ConfigurationSection equippedGear = playerSection.getConfigurationSection("equipped-gear");
                if (equippedGear != null) {
                    readEquippedGear(equippedGear, data.equippedCosmetics());
                }
                ConfigurationSection battleItems = playerSection.getConfigurationSection("battle-items");
                if (battleItems != null) {
                    for (String id : battleItems.getKeys(false)) {
                        data.battleItems().put(id, Math.max(0, battleItems.getInt(id, 0)));
                    }
                }
                List<String> loadout = playerSection.getStringList("battle-loadout");
                for (int i = 0; i < Math.min(9, loadout.size()); i++) {
                    data.battleLoadout().set(i, loadout.get(i) == null ? "" : loadout.get(i));
                }
                data.claimedRewards().addAll(playerSection.getStringList("claimed-rewards"));
                players.put(uuid, data);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    public void save() {
        if (yaml == null || file == null) {
            return;
        }
        yaml.set("players", null);
        for (PlayerData data : players.values()) {
            String path = "players." + data.uuid();
            yaml.set(path + ".name", data.name());
            yaml.set(path + ".wins", data.wins());
            yaml.set(path + ".current-streak", data.currentStreak());
            yaml.set(path + ".coins", data.coins());
            yaml.set(path + ".owned-cosmetics", new ArrayList<>(data.ownedCosmetics()));
            writeEquippedCosmetics(path + ".equipped-cosmetics", data.equippedCosmetics());
            writeEquippedGear(path + ".equipped-gear", data.equippedCosmetics());
            yaml.set(path + ".battle-items", new LinkedHashMap<>(data.battleItems()));
            yaml.set(path + ".battle-loadout", new ArrayList<>(data.battleLoadout()));
            yaml.set(path + ".claimed-rewards", new ArrayList<>(data.claimedRewards()));
        }
        try {
            yaml.save(file);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not save Spleef player data: " + exception.getMessage());
        }
    }

    public PlayerData get(Player player) {
        PlayerData data = players.computeIfAbsent(player.getUniqueId(), uuid -> new PlayerData(uuid, player.getName()));
        data.name(player.getName());
        applyDefaults(data);
        return data;
    }

    public PlayerData get(UUID uuid) {
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(uuid);
        PlayerData data = players.computeIfAbsent(uuid, id -> new PlayerData(id, offlinePlayer.getName()));
        applyDefaults(data);
        return data;
    }

    public List<PlayerData> topWins(int amount) {
        return players.values().stream()
                .sorted(Comparator.<PlayerData>comparingInt(PlayerData::wins).reversed().thenComparing(data -> data.name()))
                .limit(amount)
                .toList();
    }

    public double addCoins(Player player, double baseCoins) {
        if (baseCoins <= 0.0) {
            return 0.0;
        }
        PlayerData data = get(player);
        double amount = baseCoins * totalMultiplier(player);
        data.coins(data.coins() + amount);
        return amount;
    }

    public boolean removeCoins(Player player, double amount) {
        PlayerData data = get(player);
        if (amount <= 0.0) {
            return true;
        }
        if (data.coins() < amount) {
            return false;
        }
        data.coins(data.coins() - amount);
        return true;
    }

    public double globalMultiplier() {
        return Math.max(0.0, plugin.getConfig().getDouble("coin-multipliers.global", 1.0));
    }

    public double totalMultiplier(Player player) {
        double permissionMultiplier = 1.0;
        ConfigurationSection permissions = plugin.getConfig().getConfigurationSection("coin-multipliers.permissions");
        if (permissions != null) {
            for (String key : permissions.getKeys(false)) {
                if (player.hasPermission("spleef.multiplier." + key)) {
                    permissionMultiplier = Math.max(permissionMultiplier, permissions.getDouble(key, 1.0));
                }
            }
        }
        return globalMultiplier() * permissionMultiplier * activeServerBooster();
    }

    public void activateServerBooster(double multiplier, long durationMillis) {
        serverBoosterMultiplier = Math.max(1.0, multiplier);
        serverBoosterEndsAt = System.currentTimeMillis() + Math.max(0L, durationMillis);
    }

    public double activeServerBooster() {
        if (serverBoosterEndsAt <= System.currentTimeMillis()) {
            serverBoosterMultiplier = 1.0;
            return 1.0;
        }
        return serverBoosterMultiplier;
    }

    public String formatCoins(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.001) {
            return Long.toString(Math.round(value));
        }
        return String.format("%.2f", value);
    }

    private void applyDefaults(PlayerData data) {
        if (registry == null) {
            return;
        }
        for (CosmeticDefinition definition : registry.defaultCosmetics()) {
            data.unlockCosmetic(definition.category(), definition.id());
            data.equippedCosmetics().putIfAbsent(registry.equippedKey(definition), definition.id());
        }
        if (data.equippedCosmetic(ContentRegistry.CATEGORY_SHOVELS).isBlank()) {
            for (CosmeticDefinition definition : registry.cosmetics(ContentRegistry.CATEGORY_SHOVELS).values()) {
                data.unlockCosmetic(definition.category(), definition.id());
                data.equipCosmetic(definition.category(), definition.id());
                break;
            }
        }
    }

    private void readEquippedCosmetics(ConfigurationSection section, String prefix, Map<String, String> output) {
        for (Map.Entry<String, Object> entry : section.getValues(false).entrySet()) {
            String key = entry.getKey();
            String fullKey = prefix.isBlank() ? key : prefix + "." + key;
            if (entry.getValue() instanceof ConfigurationSection child) {
                readEquippedCosmetics(child, fullKey, output);
                continue;
            }
            if (entry.getValue() != null) {
                String value = entry.getValue().toString();
                if (!value.isBlank()) {
                    output.put(fullKey, value);
                }
            }
        }
    }

    private void writeEquippedCosmetics(String path, Map<String, String> equippedCosmetics) {
        yaml.set(path, null);
        for (Map.Entry<String, String> entry : equippedCosmetics.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null || entry.getValue().isBlank()) {
                continue;
            }
            if (entry.getKey().startsWith(ContentRegistry.CATEGORY_GEAR + ".")) {
                continue;
            }
            yaml.set(path + "." + entry.getKey(), entry.getValue());
        }
    }

    private void readEquippedGear(ConfigurationSection section, Map<String, String> equippedCosmetics) {
        for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
            String id = section.getString(slot.name().toLowerCase(Locale.ROOT), "");
            if (id != null && !id.isBlank()) {
                equippedCosmetics.put(gearKey(slot), id);
            }
        }
    }

    private void writeEquippedGear(String path, Map<String, String> equippedCosmetics) {
        yaml.set(path, null);
        for (EquipmentSlot slot : List.of(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)) {
            String id = equippedCosmetics.getOrDefault(gearKey(slot), "");
            if (!id.isBlank()) {
                yaml.set(path + "." + slot.name().toLowerCase(Locale.ROOT), id);
            }
        }
    }

    private String gearKey(EquipmentSlot slot) {
        return ContentRegistry.CATEGORY_GEAR + "." + slot.name().toLowerCase(Locale.ROOT);
    }
}
