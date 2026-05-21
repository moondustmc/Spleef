package com.moondust.spleef.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public final class ConfigUpdater {
    private final JavaPlugin plugin;
    private final String resourceName;

    public ConfigUpdater(JavaPlugin plugin, String resourceName) {
        this.plugin = plugin;
        this.resourceName = resourceName;
    }

    public UpdateResult update() throws IOException {
        File configFile = new File(plugin.getDataFolder(), resourceName);
        if (!configFile.exists()) {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            plugin.saveResource(resourceName, false);
        }

        YamlConfiguration defaults = loadDefaults();
        YamlConfiguration current = YamlConfiguration.loadConfiguration(configFile);
        int targetVersion = Math.max(1, defaults.getInt("config-version", 1));
        int previousVersion = current.getInt("config-version", 0);
        if (previousVersion >= targetVersion) {
            return new UpdateResult(0, previousVersion, targetVersion, false);
        }

        int addedPaths = applyVersionMigrations(previousVersion, targetVersion, current);
        addedPaths += copyMissing(defaults, current);
        current.set("config-version", targetVersion);
        current.save(configFile);
        return new UpdateResult(addedPaths, previousVersion, targetVersion, true);
    }

    private YamlConfiguration loadDefaults() {
        InputStream resource = plugin.getResource(resourceName);
        if (resource == null) {
            throw new IllegalStateException("Missing bundled " + resourceName);
        }
        try (InputStreamReader reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        } catch (IOException exception) {
            throw new IllegalStateException("Could not read bundled " + resourceName, exception);
        }
    }

    private int copyMissing(ConfigurationSection defaults, ConfigurationSection currentRoot) {
        int added = 0;
        for (String key : defaults.getKeys(false)) {
            String path = path(defaults, key);
            if (path.equals("config-version")) {
                continue;
            }
            ConfigurationSection defaultSection = defaults.getConfigurationSection(key);
            if (defaultSection != null) {
                if (!currentRoot.contains(path, false)) {
                    currentRoot.createSection(path);
                }
                if (currentRoot.isConfigurationSection(path)) {
                    added += copyMissing(defaultSection, currentRoot);
                } else {
                    plugin.getLogger().warning("Could not auto-update config path '" + path + "' because the existing value is not a section.");
                }
                continue;
            }
            if (!currentRoot.contains(path, false)) {
                currentRoot.set(path, defaults.get(key));
                added++;
            }
        }
        return added;
    }

    private int applyVersionMigrations(int previousVersion, int targetVersion, YamlConfiguration current) {
        int changed = 0;
        if (previousVersion < 9 && targetVersion >= 9) {
            changed += multiplyShopPrices(current, 4.0);
        }
        if (previousVersion < 12 && targetVersion >= 12) {
            changed += multiplyShopPrices(current.getConfigurationSection("shops.snowballer"), 0.5);
        }
        if (previousVersion < 15 && targetVersion >= 15) {
            changed += setPrice(current, "shops.coffee.items.coffee.price", 5);
            changed += setPrice(current, "shops.coffee.items.coffee_2.price", 8);
        }
        if (previousVersion < 17 && targetVersion >= 17) {
            changed += setIfParentExists(current, "battle-items.coffee.material", "PLAYER_HEAD");
            changed += setIfParentExists(current, "battle-items.coffee.skull-texture", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvNDc3YzVhM2E5YzM3ZjcxNjljOTExNTQ5OTg5N2JmNmI5ZDFlMmY1Mjk1ZDk5OTYwYmNlMzQ5OGZjMGQ2ZmU2NSJ9fX0=");
            changed += setIfParentExists(current, "battle-items.coffee_2.material", "PLAYER_HEAD");
            changed += setIfParentExists(current, "battle-items.coffee_2.skull-texture", "eyJ0ZXh0dXJlcyI6eyJTS0lOIjp7InVybCI6Imh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOThlNWY3N2VlYzUwNmI4MGEwZGExZDFlMzY0OGFhNGQ2OWRkMzk2YjM1ZjMyOTc0Y2I0ZTdjZjY1YjA0NDY0YSJ9fX0=");
        }
        if (previousVersion < 19 && targetVersion >= 19) {
            changed += setIfParentExists(current, "shops.snowballer.items.snowball_bucket.amount", 4);
            changed += setIfParentExists(current, "battle-items.snowball_bucket.max-stack-size", 4);
        }
        if (previousVersion < 21 && targetVersion >= 21) {
            changed += setIfParentExists(current, "battle-items.snow_bomb.max-stack-size", 1);
        }
        if (previousVersion < 22 && targetVersion >= 22) {
            changed += setIfParentExists(current, "battle-items.snow_bomb.material", "MAGMA_CREAM");
        }
        if (previousVersion < 23 && targetVersion >= 23) {
            changed += setIfParentExists(current, "shops.snowballer.items.snow_bomb.price", 30);
        }
        if (previousVersion < 24 && targetVersion >= 24) {
            changed += setIfParentExists(current, "battle-items.snow_bomb.fuse-seconds", 2.2);
        }
        if (previousVersion < 25 && targetVersion >= 25) {
            changed += setIfParentExists(current, "battle-items.snow_bomb.fuse-seconds", 1.3);
        }
        if (previousVersion < 26 && targetVersion >= 26) {
            changed += setIfParentExists(current, "battle-items.snow_bomb.fuse-seconds", 1.6);
        }
        if (previousVersion < 27 && targetVersion >= 27) {
            changed += raiseDoubleIfBelow(current, "settings.double-jump.height", 0.7);
        }
        return changed;
    }

    private int raiseDoubleIfBelow(YamlConfiguration current, String path, double minimum) {
        if (!current.contains(path, false) || current.getDouble(path, minimum) >= minimum) {
            return 0;
        }
        current.set(path, minimum);
        return 1;
    }

    private int setIfParentExists(YamlConfiguration current, String path, Object value) {
        int lastDot = path.lastIndexOf('.');
        if (lastDot <= 0 || !current.isConfigurationSection(path.substring(0, lastDot))) {
            return 0;
        }
        if (Objects.equals(current.get(path), value)) {
            return 0;
        }
        current.set(path, value);
        return 1;
    }

    private int setPrice(YamlConfiguration current, String path, int price) {
        if (!current.contains(path, false)) {
            return 0;
        }
        current.set(path, price);
        return 1;
    }

    private int multiplyShopPrices(YamlConfiguration current, double multiplier) {
        ConfigurationSection shops = current.getConfigurationSection("shops");
        if (shops == null) {
            return 0;
        }
        return multiplyShopPrices(shops, multiplier);
    }

    private int multiplyShopPrices(ConfigurationSection section, double multiplier) {
        if (section == null) {
            return 0;
        }
        int changed = 0;
        for (String key : section.getKeys(false)) {
            ConfigurationSection child = section.getConfigurationSection(key);
            if (child != null) {
                changed += multiplyShopPrices(child, multiplier);
                continue;
            }
            Object value = section.get(key);
            if (key.equals("price") && value instanceof Number number) {
                double next = number.doubleValue() * multiplier;
                if (Math.abs(next - Math.rint(next)) < 0.001) {
                    section.set(key, (long) Math.round(next));
                } else {
                    section.set(key, next);
                }
                changed++;
            }
        }
        return changed;
    }

    private String path(ConfigurationSection section, String key) {
        String base = section.getCurrentPath();
        return base == null || base.isBlank() ? key : base + "." + key;
    }

    public record UpdateResult(int addedPaths, int previousVersion, int targetVersion, boolean updated) {
    }
}
