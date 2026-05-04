package com.moondust.spleef.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

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
        return changed;
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
