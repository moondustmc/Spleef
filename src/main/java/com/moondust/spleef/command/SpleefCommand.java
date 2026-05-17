package com.moondust.spleef.command;

import com.moondust.spleef.Spleef;
import com.moondust.spleef.config.ConfigUpdater;
import com.moondust.spleef.content.ContentRegistry;
import com.moondust.spleef.game.ArenaMap;
import com.moondust.spleef.game.GameManager;
import com.moondust.spleef.leaderboard.LeaderboardService;
import com.moondust.spleef.menu.MenuManager;
import com.moondust.spleef.player.PlayerData;
import com.moondust.spleef.player.PlayerDataManager;
import com.moondust.spleef.scoreboard.ScoreboardService;
import com.moondust.spleef.shop.NpcShopService;
import com.moondust.spleef.shop.ShopService;
import com.moondust.spleef.util.LocationCodec;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SpleefCommand implements CommandExecutor, TabCompleter {
    private static final List<String> USER_COMMANDS = List.of(
            "join", "leave", "stats", "cosmetics", "battleitems", "inventory", "particles",
            "blockbreakparticles", "clothes", "shovels", "admin"
    );
    private static final List<String> ADMIN_COMMANDS = List.of(
            "forcestart", "reload", "setlobby", "setspawn", "pos1", "setpos1", "pos2", "setpos2",
            "snowpos1", "setsnowpos1", "snowpos2", "setsnowpos2", "savearena",
            "sethologram", "rotate", "map", "givebattle", "givecosmetic", "unlock", "npcshop", "openshop", "coins", "booster"
    );
    private static final List<String> UNLOCK_TYPES = List.of(
            "particle", "player_particle", "blockbreakparticle", "block_break_particle", "shovel", "gear"
    );

    private final Spleef plugin;
    private final GameManager gameManager;
    private final MenuManager menuManager;
    private final PlayerDataManager dataManager;
    private final ContentRegistry registry;
    private final LeaderboardService leaderboardService;
    private final ScoreboardService scoreboardService;
    private final NpcShopService npcShopService;
    private final ShopService shopService;

    public SpleefCommand(Spleef plugin, GameManager gameManager, MenuManager menuManager,
                         PlayerDataManager dataManager, ContentRegistry registry, LeaderboardService leaderboardService,
                         ScoreboardService scoreboardService, NpcShopService npcShopService, ShopService shopService) {
        this.plugin = plugin;
        this.gameManager = gameManager;
        this.menuManager = menuManager;
        this.dataManager = dataManager;
        this.registry = registry;
        this.leaderboardService = leaderboardService;
        this.scoreboardService = scoreboardService;
        this.npcShopService = npcShopService;
        this.shopService = shopService;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String commandName = command.getName().toLowerCase(Locale.ROOT);
        if (commandName.equals("stats")) {
            return stats(sender);
        }
        if (commandName.equals("battleitems")) {
            return battleItems(sender);
        }
        if (args.length == 0) {
            sendUserHelp(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("admin")) {
            return handleAdminCommand(sender, dropFirst(args));
        }
        if (ADMIN_COMMANDS.contains(sub)) {
            plugin.messageText(sender, "&cAdmin commands use &f/spleef admin " + sub + "&c.", Map.of());
            return true;
        }
        return handleUserCommand(sender, sub);
    }

    private boolean handleUserCommand(CommandSender sender, String sub) {
        return switch (sub) {
            case "join" -> join(sender);
            case "leave", "quit" -> leave(sender);
            case "stats" -> stats(sender);
            case "battleitems", "inventory" -> battleItems(sender);
            case "cosmetics", "menu" -> cosmetics(sender);
            case "particles" -> openCategory(sender, ContentRegistry.CATEGORY_PLAYER_PARTICLES);
            case "blockbreakparticles", "block-break-particles" -> openCategory(sender, ContentRegistry.CATEGORY_BLOCK_BREAK_PARTICLES);
            case "clothes", "gear" -> openCategory(sender, ContentRegistry.CATEGORY_GEAR);
            case "shovels" -> openCategory(sender, ContentRegistry.CATEGORY_SHOVELS);
            default -> {
                plugin.messageText(sender, "&cUnknown Spleef command.", Map.of());
                yield true;
            }
        };
    }

    private boolean handleAdminCommand(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        if (args.length == 0) {
            sendAdminHelp(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "forcestart" -> forceStart(sender);
            case "reload" -> reload(sender);
            case "setlobby" -> setLocation(sender, args, "lobby");
            case "setspawn" -> setLocation(sender, args, "spawn");
            case "pos1", "setpos1" -> setArenaPos(sender, args, "pos1");
            case "pos2", "setpos2" -> setArenaPos(sender, args, "pos2");
            case "snowpos1", "setsnowpos1" -> setSnowPos(sender, args, "pos1");
            case "snowpos2", "setsnowpos2" -> setSnowPos(sender, args, "pos2");
            case "savearena" -> saveArena(sender, args);
            case "sethologram" -> setHologram(sender);
            case "rotate" -> rotate(sender);
            case "map" -> setMap(sender, args);
            case "givebattle" -> giveBattle(sender, args);
            case "givecosmetic" -> giveCosmetic(sender, args);
            case "unlock" -> unlockCosmetic(sender, args);
            case "npcshop" -> npcShop(sender, args);
            case "openshop" -> openShop(sender, args);
            case "coins" -> coins(sender, args);
            case "booster" -> booster(sender, args);
            default -> {
                plugin.messageText(sender, "&cUnknown Spleef command.", Map.of());
                yield true;
            }
        };
    }

    private void sendUserHelp(CommandSender sender) {
        plugin.messageText(sender, "&b/spleef join &7- join the Spleef queue", Map.of());
        plugin.messageText(sender, "&b/spleef leave &7- leave the Spleef queue", Map.of());
        plugin.messageText(sender, "&b/spleef stats &7- show stats", Map.of());
        plugin.messageText(sender, "&b/spleef cosmetics &7- open cosmetics", Map.of());
        plugin.messageText(sender, "&b/spleef battleitems &7- open BattleItems", Map.of());
        if (sender.hasPermission("spleef.admin")) {
            plugin.messageText(sender, "&b/spleef admin &7- show admin commands", Map.of());
        }
    }

    private void sendAdminHelp(CommandSender sender) {
        plugin.messageText(sender, "&b/spleef admin setlobby [map] &7- set lobby spawn", Map.of());
        plugin.messageText(sender, "&b/spleef admin setspawn [map] &7- set arena spawn", Map.of());
        plugin.messageText(sender, "&b/spleef admin pos1 [map] &7- set arena corner 1", Map.of());
        plugin.messageText(sender, "&b/spleef admin pos2 [map] &7- set arena corner 2", Map.of());
        plugin.messageText(sender, "&b/spleef admin snowpos1 [map] &7- set snow floor corner 1", Map.of());
        plugin.messageText(sender, "&b/spleef admin snowpos2 [map] &7- set snow floor corner 2", Map.of());
        plugin.messageText(sender, "&b/spleef admin savearena [map] &7- save snow snapshot", Map.of());
        plugin.messageText(sender, "&b/spleef admin unlock <player> <type> <id> &7- unlock cosmetic", Map.of());
        plugin.messageText(sender, "&b/spleef admin npcshop <shop_id|clear> &7- bind selected Citizens NPC", Map.of());
        plugin.messageText(sender, "&b/spleef admin openshop <player> <shop_id> &7- open a Spleef shop", Map.of());
        plugin.messageText(sender, "&b/spleef admin givebattle <player> <item> [amount]", Map.of());
        plugin.messageText(sender, "&b/spleef admin givecosmetic <player> <category> <id>", Map.of());
        plugin.messageText(sender, "&b/spleef admin reload &7- reload config", Map.of());
    }

    private boolean join(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player != null) {
            gameManager.joinQueue(player);
        }
        return true;
    }

    private boolean leave(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player != null) {
            gameManager.leaveQueue(player);
        }
        return true;
    }

    private boolean stats(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }
        PlayerData data = dataManager.get(player);
        plugin.message(player, "messages.stats", Map.of(
                "{wins}", Integer.toString(data.wins()),
                "{streak}", Integer.toString(data.currentStreak()),
                "{coins}", dataManager.formatCoins(data.coins())
        ));
        return true;
    }

    private boolean battleItems(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player != null) {
            menuManager.openBattleItems(player);
        }
        return true;
    }

    private boolean cosmetics(CommandSender sender) {
        Player player = requirePlayer(sender);
        if (player != null) {
            menuManager.openCosmetics(player);
        }
        return true;
    }

    private boolean openCategory(CommandSender sender, String category) {
        Player player = requirePlayer(sender);
        if (player != null) {
            menuManager.openCategory(player, category);
        }
        return true;
    }

    private boolean forceStart(CommandSender sender) {
        if (!requireAdmin(sender)) {
            return true;
        }
        Player player = requirePlayer(sender);
        if (player != null) {
            gameManager.forceStart(player);
        }
        return true;
    }

    private boolean reload(CommandSender sender) {
        if (!requireAdmin(sender)) {
            return true;
        }
        ConfigUpdater.UpdateResult configUpdate = plugin.updateConfigFromDefaults();
        plugin.reloadConfig();
        registry.reload();
        dataManager.setRegistry(registry);
        gameManager.reload();
        leaderboardService.update();
        if (npcShopService != null) {
            npcShopService.reload();
        }
        if (configUpdate.updated()) {
            plugin.messageText(sender, "&aSpleef reloaded. Applied &f" + configUpdate.addedPaths() + " &aconfig update(s).", Map.of());
        } else {
            plugin.messageText(sender, "&aSpleef reloaded.", Map.of());
        }
        return true;
    }

    private boolean setLocation(CommandSender sender, String[] args, String key) {
        if (!requireAdmin(sender)) {
            return true;
        }
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }
        String mapId = mapArg(args);
        String path = "maps." + mapId + "." + key;
        plugin.getConfig().set("maps." + mapId + ".world", player.getWorld().getName());
        plugin.getConfig().set(path, null);
        ConfigurationSection section = plugin.getConfig().createSection(path);
        LocationCodec.writeLocation(section, player.getLocation());
        plugin.saveConfig();
        gameManager.reload();
        plugin.message(player, "messages.setup-saved");
        return true;
    }

    private boolean setArenaPos(CommandSender sender, String[] args, String key) {
        if (!requireAdmin(sender)) {
            return true;
        }
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }
        String mapId = mapArg(args);
        Location location = player.getLocation();
        String path = "maps." + mapId + ".arena." + key;
        plugin.getConfig().set("maps." + mapId + ".world", player.getWorld().getName());
        plugin.getConfig().set(path + ".x", location.getBlockX());
        plugin.getConfig().set(path + ".y", location.getBlockY());
        plugin.getConfig().set(path + ".z", location.getBlockZ());
        plugin.saveConfig();
        gameManager.reload();
        plugin.message(player, "messages.setup-saved");
        return true;
    }

    private boolean setSnowPos(CommandSender sender, String[] args, String key) {
        if (!requireAdmin(sender)) {
            return true;
        }
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }
        String mapId = mapArg(args);
        Location location = player.getLocation();
        String path = "maps." + mapId + ".snow-level." + key;
        plugin.getConfig().set("maps." + mapId + ".world", player.getWorld().getName());
        plugin.getConfig().set(path + ".x", location.getBlockX());
        plugin.getConfig().set(path + ".y", location.getBlockY());
        plugin.getConfig().set(path + ".z", location.getBlockZ());
        plugin.saveConfig();
        gameManager.reload();
        plugin.message(player, "messages.setup-saved");
        return true;
    }

    private boolean saveArena(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        String mapId = mapArg(args);
        gameManager.saveArenaSnapshot(mapId);
        plugin.message(sender, "messages.setup-saved");
        return true;
    }

    private boolean setHologram(CommandSender sender) {
        if (!requireAdmin(sender)) {
            return true;
        }
        Player player = requirePlayer(sender);
        if (player != null) {
            leaderboardService.setHologram(player.getLocation());
            plugin.message(player, "messages.setup-saved");
        }
        return true;
    }

    private boolean rotate(CommandSender sender) {
        if (!requireAdmin(sender)) {
            return true;
        }
        gameManager.rotateNow();
        return true;
    }

    private boolean setMap(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        if (args.length < 2) {
            plugin.messageText(sender, "&cUsage: /spleef admin map <id>", Map.of());
            return true;
        }
        if (!gameManager.setCurrentMap(args[1])) {
            plugin.messageText(sender, "&cUnknown map id.", Map.of());
        }
        return true;
    }

    private boolean giveBattle(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        if (args.length < 3) {
            plugin.messageText(sender, "&cUsage: /spleef admin givebattle <player> <item> [amount]", Map.of());
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null || registry.battleItem(args[2]) == null) {
            plugin.messageText(sender, "&cUnknown player or battle item.", Map.of());
            return true;
        }
        int amount = args.length >= 4 ? parseInt(args[3], 1) : 1;
        dataManager.get(target).addBattleItem(args[2], amount);
        dataManager.save();
        plugin.message(target, "messages.battle-item-received", Map.of("{item}", registry.battleItemDisplayName(args[2])));
        return true;
    }

    private boolean giveCosmetic(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        if (args.length < 4) {
            plugin.messageText(sender, "&cUsage: /spleef admin givecosmetic <player> <category> <id>", Map.of());
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null || registry.cosmetic(args[2], args[3]) == null) {
            plugin.messageText(sender, "&cUnknown player or cosmetic.", Map.of());
            return true;
        }
        dataManager.get(target).unlockCosmetic(args[2], args[3]);
        dataManager.save();
        plugin.message(target, "messages.cosmetic-received", Map.of("{cosmetic}", registry.cosmeticDisplayName(args[2], args[3])));
        return true;
    }

    private boolean unlockCosmetic(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        if (args.length < 4) {
            plugin.messageText(sender, "&cUsage: /spleef admin unlock <player> <particle|blockbreakparticle|shovel|gear> <id>", Map.of());
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        String category = unlockCategory(args[2]);
        String id = args[3];
        if (target == null) {
            plugin.messageText(sender, "&cUnknown player.", Map.of());
            return true;
        }
        if (category.isBlank()) {
            plugin.messageText(sender, "&cUnknown unlock type. Use particle, blockbreakparticle, shovel, or gear.", Map.of());
            return true;
        }
        if (registry.cosmetic(category, id) == null) {
            plugin.messageText(sender, "&cUnknown cosmetic id for that type.", Map.of());
            return true;
        }
        PlayerData data = dataManager.get(target);
        if (data.ownsCosmetic(category, id)) {
            plugin.message(target, "messages.cosmetic-already-owned");
            plugin.messageText(sender, "&eThat player already owns this cosmetic.", Map.of());
            return true;
        }
        data.unlockCosmetic(category, id);
        dataManager.save();
        plugin.message(target, "messages.cosmetic-received", Map.of("{cosmetic}", registry.cosmeticDisplayName(category, id)));
        plugin.messageText(sender, "&aUnlocked &f" + registry.cosmeticDisplayName(category, id) + " &afor &f" + target.getName() + "&a.", Map.of());
        return true;
    }

    private boolean npcShop(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        Player player = requirePlayer(sender);
        if (player == null) {
            return true;
        }
        if (npcShopService == null || !npcShopService.available()) {
            plugin.messageText(sender, "&cCitizens is not enabled or is not available.", Map.of());
            return true;
        }
        if (args.length < 2) {
            plugin.messageText(sender, "&cUsage: /spleef admin npcshop <shop_id|clear>", Map.of());
            return true;
        }
        if (args[1].equalsIgnoreCase("clear") || args[1].equalsIgnoreCase("remove")) {
            npcShopService.clearShop(player);
            return true;
        }
        npcShopService.bindShop(player, args[1]);
        return true;
    }

    private boolean openShop(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        if (args.length < 3) {
            plugin.messageText(sender, "&cUsage: /spleef admin openshop <player> <shop_id>", Map.of());
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            plugin.messageText(sender, "&cUnknown player.", Map.of());
            return true;
        }
        String shopId = args[2];
        if (!shopService.shopExists(shopId)) {
            plugin.messageText(sender, "&cUnknown shop id.", Map.of());
            return true;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> shopService.openShop(target, shopId), 2L);
        plugin.messageText(sender, "&aOpening Spleef shop &f" + shopId + " &afor &f" + target.getName() + "&a.", Map.of());
        return true;
    }

    private boolean coins(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        if (args.length < 3) {
            plugin.messageText(sender, "&cUsage: /spleef admin coins <player> <amount>", Map.of());
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            plugin.messageText(sender, "&cUnknown player.", Map.of());
            return true;
        }
        PlayerData data = dataManager.get(target);
        data.coins(data.coins() + parseDouble(args[2], 0.0));
        dataManager.save();
        plugin.messageText(sender, "&aCoins updated.", Map.of());
        return true;
    }

    private boolean booster(CommandSender sender, String[] args) {
        if (!requireAdmin(sender)) {
            return true;
        }
        if (args.length < 3) {
            plugin.messageText(sender, "&cUsage: /spleef admin booster <multiplier> <minutes>", Map.of());
            return true;
        }
        double multiplier = parseDouble(args[1], 1.0);
        long minutes = Math.max(1, parseInt(args[2], 1));
        dataManager.activateServerBooster(multiplier, minutes * 60_000L);
        for (Player player : Bukkit.getOnlinePlayers()) {
            plugin.message(player, "messages.booster", Map.of(
                    "{player}", sender.getName(),
                    "{multiplier}", Double.toString(multiplier)
            ));
        }
        scoreboardService.updateAll();
        return true;
    }

    private Player requirePlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        plugin.message(sender, "messages.need-player");
        return null;
    }

    private boolean requireAdmin(CommandSender sender) {
        if (sender.hasPermission("spleef.admin")) {
            return true;
        }
        plugin.message(sender, "messages.no-permission");
        return false;
    }

    private String mapArg(String[] args) {
        if (args.length >= 2) {
            return args[1];
        }
        ArenaMap current = gameManager.currentMap();
        return current == null ? "default" : current.id();
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private String unlockCategory(String type) {
        return switch (type.toLowerCase(Locale.ROOT).replace("-", "_")) {
            case "particle", "particles", "player_particle", "player_particles" -> ContentRegistry.CATEGORY_PLAYER_PARTICLES;
            case "blockbreakparticle", "blockbreakparticles", "block_break_particle", "block_break_particles", "block_particle", "block_particles" ->
                    ContentRegistry.CATEGORY_BLOCK_BREAK_PARTICLES;
            case "shovel", "shovels" -> ContentRegistry.CATEGORY_SHOVELS;
            case "gear", "clothes", "clothing" -> ContentRegistry.CATEGORY_GEAR;
            default -> "";
        };
    }

    private String[] dropFirst(String[] args) {
        if (args.length <= 1) {
            return new String[0];
        }
        String[] result = new String[args.length - 1];
        System.arraycopy(args, 1, result, 0, result.length);
        return result;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("spleef")) {
            return List.of();
        }
        if (args.length == 1) {
            List<String> values = new ArrayList<>(USER_COMMANDS);
            if (!sender.hasPermission("spleef.admin")) {
                values.remove("admin");
            }
            return filter(values, args[0]);
        }
        if (!args[0].equalsIgnoreCase("admin") || !sender.hasPermission("spleef.admin")) {
            return List.of();
        }
        if (args.length == 2) {
            return filter(ADMIN_COMMANDS, args[1]);
        }
        String adminSub = args[1].toLowerCase(Locale.ROOT);
        if (args.length == 3 && List.of("map", "setlobby", "setspawn", "pos1", "setpos1", "pos2", "setpos2",
                "snowpos1", "setsnowpos1", "snowpos2", "setsnowpos2", "savearena").contains(adminSub)) {
            return filter(gameManager.maps().stream().map(ArenaMap::id).toList(), args[2]);
        }
        if (args.length == 3 && adminSub.equals("npcshop")) {
            List<String> values = new ArrayList<>(shopService.shopIds());
            values.add("clear");
            return filter(values, args[2]);
        }
        if (args.length == 3 && List.of("givebattle", "givecosmetic", "unlock", "openshop", "coins").contains(adminSub)) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[2]);
        }
        if (args.length == 4 && adminSub.equals("givebattle")) {
            return filter(registry.battleItems().stream().map(item -> item.id()).toList(), args[3]);
        }
        if (args.length == 4 && adminSub.equals("givecosmetic")) {
            return filter(new ArrayList<>(registry.cosmeticCategories()), args[3]);
        }
        if (args.length == 4 && adminSub.equals("unlock")) {
            return filter(UNLOCK_TYPES, args[3]);
        }
        if (args.length == 4 && adminSub.equals("openshop")) {
            return filter(shopService.shopIds(), args[3]);
        }
        if (args.length == 5 && adminSub.equals("givecosmetic")) {
            return filter(new ArrayList<>(registry.cosmetics(args[3]).keySet()), args[4]);
        }
        if (args.length == 5 && adminSub.equals("unlock")) {
            return filter(new ArrayList<>(registry.cosmetics(unlockCategory(args[3])).keySet()), args[4]);
        }
        return List.of();
    }

    private List<String> filter(List<String> values, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }
}
