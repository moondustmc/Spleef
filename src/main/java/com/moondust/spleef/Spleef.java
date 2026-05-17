package com.moondust.spleef;

import com.moondust.spleef.battle.BattleItemService;
import com.moondust.spleef.command.SpleefCommand;
import com.moondust.spleef.config.ConfigUpdater;
import com.moondust.spleef.content.ContentRegistry;
import com.moondust.spleef.game.GameManager;
import com.moondust.spleef.leaderboard.LeaderboardService;
import com.moondust.spleef.listener.SpleefInteractionListener;
import com.moondust.spleef.menu.MenuManager;
import com.moondust.spleef.player.PlayerDataManager;
import com.moondust.spleef.scoreboard.ScoreboardService;
import com.moondust.spleef.shop.CitizensShopService;
import com.moondust.spleef.shop.NpcShopService;
import com.moondust.spleef.shop.ShopService;
import com.moondust.spleef.util.Chat;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;

public final class Spleef extends JavaPlugin {
    private ConfigUpdater configUpdater;
    private ContentRegistry contentRegistry;
    private PlayerDataManager playerDataManager;
    private GameManager gameManager;
    private BattleItemService battleItemService;
    private MenuManager menuManager;
    private ScoreboardService scoreboardService;
    private LeaderboardService leaderboardService;
    private ShopService shopService;
    private NpcShopService npcShopService;
    private BukkitTask onlineRewardTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        configUpdater = new ConfigUpdater(this, "config.yml");
        ConfigUpdater.UpdateResult configUpdate = updateConfigFromDefaults();
        if (configUpdate.updated()) {
            reloadConfig();
        }

        contentRegistry = new ContentRegistry(this);
        playerDataManager = new PlayerDataManager(this);
        playerDataManager.load();
        playerDataManager.setRegistry(contentRegistry);

        gameManager = new GameManager(this, playerDataManager, contentRegistry);
        battleItemService = new BattleItemService(this, gameManager, playerDataManager, contentRegistry);
        gameManager.setBattleItemService(battleItemService);
        menuManager = new MenuManager(this, playerDataManager, contentRegistry, gameManager);
        shopService = new ShopService(this, playerDataManager, contentRegistry);
        leaderboardService = new LeaderboardService(this, playerDataManager);
        scoreboardService = new ScoreboardService(this, playerDataManager);
        gameManager.setScoreboardService(scoreboardService);
        npcShopService = loadNpcShopService();

        SpleefCommand command = new SpleefCommand(this, gameManager, menuManager, playerDataManager, contentRegistry, leaderboardService, scoreboardService, npcShopService, shopService);
        registerCommand("spleef", command);
        registerCommand("stats", command);
        registerCommand("battleitems", command);

        Bukkit.getPluginManager().registerEvents(gameManager, this);
        Bukkit.getPluginManager().registerEvents(battleItemService, this);
        Bukkit.getPluginManager().registerEvents(menuManager, this);
        Bukkit.getPluginManager().registerEvents(shopService, this);
        Bukkit.getPluginManager().registerEvents(new SpleefInteractionListener(this, gameManager, menuManager, leaderboardService, playerDataManager, contentRegistry, shopService, npcShopService), this);

        gameManager.startTasks();
        scoreboardService.startTasks();
        leaderboardService.startTasks();
        startOnlineRewardTask();
    }

    @Override
    public void onDisable() {
        if (gameManager != null) {
            gameManager.stopTasks();
        }
        if (scoreboardService != null) {
            scoreboardService.stopTasks();
        }
        if (leaderboardService != null) {
            leaderboardService.stopTasks();
        }
        if (onlineRewardTask != null) {
            onlineRewardTask.cancel();
        }
        if (playerDataManager != null) {
            playerDataManager.save();
        }
    }

    public void message(CommandSender sender, String path) {
        message(sender, path, Map.of());
    }

    public void message(CommandSender sender, String path, Map<String, String> replacements) {
        String fallback = path;
        String text = getConfig().getString(path, fallback);
        messageText(sender, text, replacements);
    }

    public void messageText(CommandSender sender, String text, Map<String, String> replacements) {
        String rendered = renderMessage(text, replacements);
        String prefix = getConfig().getString("messages.prefix", "");
        sender.sendMessage(Chat.color(prefix + rendered));
    }

    public void actionBar(Player player, String path, Map<String, String> replacements) {
        String fallback = path;
        String text = getConfig().getString(path, fallback);
        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, TextComponent.fromLegacyText(Chat.color(renderMessage(text, replacements))));
    }

    private String renderMessage(String text, Map<String, String> replacements) {
        String rendered = text == null ? "" : text;
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            rendered = rendered.replace(entry.getKey(), entry.getValue());
        }
        return rendered;
    }

    public ConfigUpdater.UpdateResult updateConfigFromDefaults() {
        if (configUpdater == null) {
            configUpdater = new ConfigUpdater(this, "config.yml");
        }
        try {
            ConfigUpdater.UpdateResult result = configUpdater.update();
            if (result.updated()) {
                getLogger().info("Updated config.yml from version " + result.previousVersion() + " to " + result.targetVersion()
                        + " and applied " + result.addedPaths() + " config update(s).");
            }
            return result;
        } catch (IOException | RuntimeException exception) {
            getLogger().warning("Could not auto-update config.yml: " + exception.getMessage());
            return new ConfigUpdater.UpdateResult(0, -1, -1, false);
        }
    }

    private void registerCommand(String name, SpleefCommand executor) {
        PluginCommand command = Objects.requireNonNull(getCommand(name), "Missing command " + name + " in plugin.yml");
        command.setExecutor(executor);
        command.setTabCompleter(executor);
    }

    private NpcShopService loadNpcShopService() {
        if (!Bukkit.getPluginManager().isPluginEnabled("Citizens")) {
            return null;
        }
        try {
            CitizensShopService service = new CitizensShopService(this, shopService);
            Bukkit.getPluginManager().registerEvents(service, this);
            service.applyExistingTags();
            return service;
        } catch (NoClassDefFoundError error) {
            getLogger().warning("Citizens is enabled, but the Citizens API was not available to Spleef.");
            return null;
        }
    }

    private void startOnlineRewardTask() {
        if (onlineRewardTask != null) {
            onlineRewardTask.cancel();
        }
        if (!getConfig().getBoolean("settings.online-reward.enabled", true)) {
            return;
        }
        int seconds = Math.max(1, getConfig().getInt("settings.online-reward.seconds", 600));
        onlineRewardTask = new BukkitRunnable() {
            @Override
            public void run() {
                double baseCoins = getConfig().getDouble("settings.online-reward.coins", 0.0);
                if (baseCoins <= 0.0) {
                    return;
                }
                for (Player player : Bukkit.getOnlinePlayers()) {
                    double gained = playerDataManager.addCoins(player, baseCoins);
                    if (gained > 0.0) {
                        message(player, "messages.coins-gained", Map.of("{coins}", playerDataManager.formatCoins(gained)));
                    }
                }
                playerDataManager.save();
            }
        }.runTaskTimer(this, seconds * 20L, seconds * 20L);
    }
}
