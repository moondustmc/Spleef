package com.moondust.spleef.game;

import com.moondust.spleef.Spleef;
import com.moondust.spleef.battle.BattleItemService;
import com.moondust.spleef.content.ContentRegistry;
import com.moondust.spleef.content.ParticleSpec;
import com.moondust.spleef.player.PlayerData;
import com.moondust.spleef.player.PlayerDataManager;
import com.moondust.spleef.scoreboard.ScoreboardService;
import com.moondust.spleef.util.Chat;
import com.moondust.spleef.util.LocationCodec;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.FireworkEffect;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.FireworkMeta;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class GameManager implements Listener {
    private static final double PLAYER_COLLISION_RADIUS = 0.31;
    private static final double DOUBLE_JUMP_CLEARANCE_STEP = 0.35;

    private final Spleef plugin;
    private final PlayerDataManager dataManager;
    private final ContentRegistry registry;
    private final Set<UUID> queue = new LinkedHashSet<>();
    private final Set<UUID> activePlayers = new LinkedHashSet<>();
    private final Map<UUID, SessionState> sessions = new HashMap<>();
    private final Map<UUID, Long> lastBreak = new HashMap<>();
    private final Map<UUID, Long> lastMove = new HashMap<>();
    private final Map<UUID, Location> lastMoveLocation = new HashMap<>();
    private final Map<UUID, Integer> doubleJumpsRemaining = new HashMap<>();
    private final List<ArenaMap> maps = new ArrayList<>();
    private Material snowMaterial;
    private Material deathMaterial;
    private GameState state = GameState.WAITING;
    private BattleItemService battleItemService;
    private ScoreboardService scoreboardService;
    private BukkitTask monitorTask;
    private BukkitTask countdownTask;
    private File arenaFile;
    private YamlConfiguration arenaYaml;
    private int currentMapIndex = 0;
    private long gameStartedAt = 0L;
    private int mustMineStage = -1;
    private long lastRotationAt = System.currentTimeMillis();
    private boolean rotationWarningSent = false;
    private boolean rotateAfterMatch = false;

    public GameManager(Spleef plugin, PlayerDataManager dataManager, ContentRegistry registry) {
        this.plugin = plugin;
        this.dataManager = dataManager;
        this.registry = registry;
        reload();
    }

    public void setBattleItemService(BattleItemService battleItemService) {
        this.battleItemService = battleItemService;
    }

    public void setScoreboardService(ScoreboardService scoreboardService) {
        this.scoreboardService = scoreboardService;
    }

    public void reload() {
        snowMaterial = registry.material(plugin.getConfig().getString("settings.snow-block"), Material.SNOW_BLOCK);
        deathMaterial = registry.material(plugin.getConfig().getString("settings.death-trigger-block"), Material.WATER);
        loadMaps();
        loadArenaSnapshots();
    }

    public void startTasks() {
        if (monitorTask != null) {
            monitorTask.cancel();
        }
        monitorTask = new BukkitRunnable() {
            @Override
            public void run() {
                tick();
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    public void stopTasks() {
        if (monitorTask != null) {
            monitorTask.cancel();
        }
        if (countdownTask != null) {
            countdownTask.cancel();
        }
    }

    public GameState state() {
        return state;
    }

    public boolean isQueued(Player player) {
        return queue.contains(player.getUniqueId());
    }

    public boolean isActive(Player player) {
        return activePlayers.contains(player.getUniqueId());
    }

    public ArenaMap currentMap() {
        if (maps.isEmpty()) {
            return null;
        }
        currentMapIndex = Math.max(0, Math.min(currentMapIndex, maps.size() - 1));
        return maps.get(currentMapIndex);
    }

    public List<ArenaMap> maps() {
        return List.copyOf(maps);
    }

    public boolean joinQueue(Player player) {
        if (!player.hasPermission("spleef.play")) {
            plugin.message(player, "messages.no-permission");
            return false;
        }
        if (queue.contains(player.getUniqueId())) {
            plugin.message(player, "messages.already-in-queue");
            return false;
        }
        queue.add(player.getUniqueId());
        dataManager.get(player);
        plugin.message(player, "messages.join");
        if (state == GameState.COUNTDOWN) {
            ArenaMap map = currentMap();
            if (map != null && map.ready()) {
                addPlayerToCountdown(player, map);
            }
        } else {
            giveMenuItems(player);
            updateScoreboard(player);
            tryStart();
        }
        return true;
    }

    public void leaveQueue(Player player) {
        boolean removed = queue.remove(player.getUniqueId());
        if (activePlayers.contains(player.getUniqueId())) {
            eliminate(player, "messages.leave", false, true);
        }
        if (removed) {
            plugin.message(player, "messages.leave");
        } else {
            plugin.message(player, "messages.not-in-queue");
        }
        updateScoreboard(player);
    }

    public void forceStart(Player sender) {
        if (state != GameState.WAITING) {
            return;
        }
        if (!queue.contains(sender.getUniqueId())) {
            queue.add(sender.getUniqueId());
        }
        startCountdown();
    }

    public boolean setCurrentMap(String id) {
        for (int i = 0; i < maps.size(); i++) {
            if (maps.get(i).id().equalsIgnoreCase(id)) {
                currentMapIndex = i;
                plugin.getConfig().set("current-map", maps.get(i).id());
                plugin.saveConfig();
                lastRotationAt = System.currentTimeMillis();
                broadcast("messages.welcome-map", Map.of("{map}", maps.get(i).displayName()));
                return true;
            }
        }
        return false;
    }

    public void rotateNow() {
        if (maps.size() <= 1) {
            return;
        }
        currentMapIndex = (currentMapIndex + 1) % maps.size();
        ArenaMap map = currentMap();
        plugin.getConfig().set("current-map", map.id());
        plugin.saveConfig();
        lastRotationAt = System.currentTimeMillis();
        rotationWarningSent = false;
        rotateAfterMatch = false;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!isActive(player) && map.lobby() != null) {
                player.teleport(map.lobby());
                plugin.message(player, "messages.welcome-map", Map.of("{map}", map.displayName()));
                giveMenuItems(player);
            }
        }
    }

    public void saveArenaSnapshot(String mapId) {
        ArenaMap map = mapById(mapId);
        if (map == null || !map.ready()) {
            return;
        }
        List<String> snowBlocks = new ArrayList<>();
        map.forEachSnowBlock(block -> {
            if (block.getType() == snowMaterial) {
                snowBlocks.add(LocationCodec.compact(block.getLocation()));
            }
        });
        arenaYaml.set("maps." + map.id() + ".snow", snowBlocks);
        try {
            arenaYaml.save(arenaFile);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not save arena snapshot: " + exception.getMessage());
        }
    }

    public boolean handleSnowBreak(Player player, Block block) {
        ArenaMap map = currentMap();
        if (state != GameState.ACTIVE || map == null || !isActive(player) || block == null || block.getType() != snowMaterial || !map.inSnowLevel(block.getLocation())) {
            return false;
        }
        if (plugin.getConfig().getBoolean("settings.require-shovel", true) && !isHoldingShovel(player)) {
            return false;
        }
        breakSnowBlock(player, block);
        return true;
    }

    public int breakSnowAt(Location center, int radius) {
        ArenaMap map = currentMap();
        if (center == null || center.getWorld() == null || map == null || !map.ready()) {
            return 0;
        }
        int broken = 0;
        int actualRadius = Math.max(0, radius);
        World world = center.getWorld();
        int centerX = center.getBlockX();
        int centerY = center.getBlockY();
        int centerZ = center.getBlockZ();
        for (int x = centerX - actualRadius; x <= centerX + actualRadius; x++) {
            for (int z = centerZ - actualRadius; z <= centerZ + actualRadius; z++) {
                for (int y = centerY - 1; y <= centerY + 1; y++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (block.getType() == snowMaterial && map.inSnowLevel(block.getLocation())) {
                        removeSnowBlock(block);
                        spawnBreakParticle(block.getLocation().add(0.5, 0.5, 0.5), null);
                        broken++;
                        break;
                    }
                }
            }
        }
        return broken;
    }

    public void giveMenuItems(Player player) {
        if (isActive(player)) {
            return;
        }
        giveQueueJoinMenuItem(player);
        giveQueueLeaveMenuItem(player);
        giveCosmeticsMenuItem(player);
        giveBattleItemsMenuItem(player);
    }

    public void giveQueueJoinMenuItem(Player player) {
        giveMenuItem(player, "settings.queue-join-item", "join_queue", Material.SLIME_BALL, "&aJoin Queue");
    }

    public void giveQueueLeaveMenuItem(Player player) {
        giveMenuItem(player, "settings.queue-leave-item", "leave_queue", Material.REDSTONE, "&cLeave Queue");
    }

    public void giveCosmeticsMenuItem(Player player) {
        giveMenuItem(player, "settings.cosmetics-menu-item", "open_cosmetics", Material.CHEST, "&bCosmetics");
    }

    public void giveBattleItemsMenuItem(Player player) {
        giveMenuItem(player, "settings.battleitems-menu-item", "open_battleitems", Material.MAGMA_CREAM, "&cBattle Items");
    }

    public ItemStack createCosmeticsMenuItem() {
        return createMenuItem("settings.cosmetics-menu-item", "open_cosmetics", Material.CHEST, "&bCosmetics");
    }

    public ItemStack createQueueJoinMenuItem() {
        return createMenuItem("settings.queue-join-item", "join_queue", Material.SLIME_BALL, "&aJoin Queue");
    }

    public ItemStack createQueueLeaveMenuItem() {
        return createMenuItem("settings.queue-leave-item", "leave_queue", Material.REDSTONE, "&cLeave Queue");
    }

    public ItemStack createBattleItemsMenuItem() {
        return createMenuItem("settings.battleitems-menu-item", "open_battleitems", Material.MAGMA_CREAM, "&cBattle Items");
    }

    private void giveMenuItem(Player player, String path, String action, Material fallbackMaterial, String fallbackName) {
        if (!plugin.getConfig().getBoolean(path + ".enabled", true) || isActive(player)) {
            return;
        }
        int slot = plugin.getConfig().getInt(path + ".slot", 8);
        if (slot < 0 || slot > 35) {
            return;
        }
        player.getInventory().setItem(slot, createMenuItem(path, action, fallbackMaterial, fallbackName));
    }

    private ItemStack createMenuItem(String path, String action, Material fallbackMaterial, String fallbackName) {
        Material material = registry.material(plugin.getConfig().getString(path + ".material"), fallbackMaterial);
        String name = plugin.getConfig().getString(path + ".name", fallbackName);
        return registry.menuAction(action, material, name);
    }

    private void tick() {
        if (state == GameState.WAITING) {
            tryStart();
        } else if (state == GameState.ACTIVE) {
            checkWaterLosses();
            checkAfk();
            checkMustMine();
            spawnPlayerParticles();
        }
        checkMapRotation();
    }

    private void tryStart() {
        if (state != GameState.WAITING) {
            return;
        }
        ArenaMap map = currentMap();
        if (map == null || !map.ready()) {
            return;
        }
        purgeOfflineQueue();
        if (onlineQueuedPlayers().size() >= plugin.getConfig().getInt("settings.min-players", 2)) {
            startCountdown();
        }
    }

    private void startCountdown() {
        ArenaMap map = currentMap();
        if (state != GameState.WAITING || map == null || !map.ready()) {
            return;
        }
        List<Player> players = onlineQueuedPlayers();
        if (players.isEmpty()) {
            return;
        }
        state = GameState.COUNTDOWN;
        activePlayers.clear();
        for (Player player : players) {
            addPlayerToCountdown(player, map);
        }
        int countdownSeconds = Math.max(1, plugin.getConfig().getInt("settings.countdown-seconds", 15));
        broadcast("messages.starting", Map.of("{seconds}", Integer.toString(countdownSeconds)));
        final int[] remaining = {countdownSeconds};
        if (countdownTask != null) {
            countdownTask.cancel();
        }
        countdownTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (state != GameState.COUNTDOWN) {
                    cancel();
                    return;
                }
                if (onlineActivePlayers().size() < Math.min(plugin.getConfig().getInt("settings.min-players", 2), 2)) {
                    abortCountdown();
                    cancel();
                    return;
                }
                if (remaining[0] <= 0) {
                    beginGame();
                    cancel();
                    return;
                }
                if (remaining[0] <= 5 || remaining[0] % 5 == 0) {
                    broadcast("messages.countdown", Map.of("{seconds}", Integer.toString(remaining[0])));
                }
                remaining[0]--;
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }

    private void addPlayerToCountdown(Player player, ArenaMap map) {
        if (!activePlayers.add(player.getUniqueId())) {
            return;
        }
        preparePlayerForArena(player, false);
        player.teleport(map.spawn());
        updateScoreboard(player);
    }

    private void abortCountdown() {
        for (Player player : onlineActivePlayers()) {
            restoreSession(player, true);
            doubleJumpsRemaining.remove(player.getUniqueId());
            updateScoreboard(player);
            giveMenuItems(player);
        }
        activePlayers.clear();
        state = GameState.WAITING;
    }

    private void beginGame() {
        state = GameState.ACTIVE;
        gameStartedAt = System.currentTimeMillis();
        mustMineStage = -1;
        long now = System.currentTimeMillis();
        for (Player player : onlineActivePlayers()) {
            PlayerData data = dataManager.get(player);
            PlayerInventory inventory = player.getInventory();
            inventory.clear();
            inventory.setArmorContents(null);
            inventory.setHeldItemSlot(0);
            inventory.setItem(0, registry.shovelFor(data));
            applyGear(player, data);
            if (battleItemService != null) {
                battleItemService.giveLoadout(player);
            }
            lastBreak.put(player.getUniqueId(), now);
            lastMove.put(player.getUniqueId(), now);
            lastMoveLocation.put(player.getUniqueId(), player.getLocation().clone());
            setupDoubleJumps(player);
            awardCoins(player, plugin.getConfig().getDouble("settings.participation-coins", 0.0));
            updateScoreboard(player);
        }
        broadcast("messages.game-started", Map.of());
    }

    private void endGame() {
        if (state == GameState.ENDING) {
            return;
        }
        state = GameState.ENDING;
        Player winner = null;
        if (activePlayers.size() == 1) {
            UUID winnerId = activePlayers.iterator().next();
            winner = Bukkit.getPlayer(winnerId);
        }
        if (winner != null) {
            PlayerData winnerData = dataManager.get(winner);
            winnerData.wins(winnerData.wins() + 1);
            winnerData.currentStreak(winnerData.currentStreak() + 1);
            awardCoins(winner, plugin.getConfig().getDouble("settings.win-coins", 0.0));
            broadcast("messages.win", Map.of("{player}", winner.getName()));
            if (winnerData.currentStreak() > 1) {
                broadcast("messages.streak", Map.of("{player}", winner.getName(), "{streak}", Integer.toString(winnerData.currentStreak())));
            }
            spawnCelebrationFireworks();
        }
        for (Player player : onlineActivePlayers()) {
            restoreSession(player, true);
            updateScoreboard(player);
            giveMenuItems(player);
        }
        activePlayers.clear();
        lastBreak.clear();
        lastMove.clear();
        lastMoveLocation.clear();
        doubleJumpsRemaining.clear();
        regenerateArena();
        dataManager.save();
        int delay = Math.max(1, plugin.getConfig().getInt("settings.restart-delay-seconds", 8));
        new BukkitRunnable() {
            @Override
            public void run() {
                if (rotateAfterMatch) {
                    rotateNow();
                }
                state = GameState.WAITING;
                tryStart();
            }
        }.runTaskLater(plugin, delay * 20L);
    }

    private void eliminate(Player player, String messagePath, boolean removeFromQueue, boolean restoreNow) {
        if (removeFromQueue) {
            queue.remove(player.getUniqueId());
        }
        if (!activePlayers.remove(player.getUniqueId())) {
            return;
        }
        doubleJumpsRemaining.remove(player.getUniqueId());
        PlayerData data = dataManager.get(player);
        data.currentStreak(0);
        if (messagePath != null && !messagePath.isBlank()) {
            plugin.message(player, messagePath);
        }
        if (restoreNow) {
            restoreSession(player, true);
            giveMenuItems(player);
        }
        updateScoreboard(player);
        if (state == GameState.ACTIVE && activePlayers.size() <= 1) {
            endGame();
        }
    }

    private void preparePlayerForArena(Player player, boolean giveItems) {
        saveSession(player);
        player.closeInventory();
        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        player.setGameMode(GameMode.SURVIVAL);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setFoodLevel(20);
        player.setSaturation(20.0F);
        player.setFireTicks(0);
        player.removePotionEffect(PotionEffectType.SPEED);
        if (giveItems) {
            player.getInventory().setItem(0, registry.shovelFor(dataManager.get(player)));
        }
    }

    private void setupDoubleJumps(Player player) {
        int maxJumps = doubleJumpMaxJumps();
        if (!plugin.getConfig().getBoolean("settings.double-jump.enabled", true) || maxJumps <= 0) {
            doubleJumpsRemaining.remove(player.getUniqueId());
            player.setAllowFlight(false);
            player.setFlying(false);
            return;
        }
        doubleJumpsRemaining.put(player.getUniqueId(), maxJumps);
        player.setAllowFlight(true);
        player.setFlying(false);
        showDoubleJumpsRemaining(player, maxJumps);
    }

    private void saveSession(Player player) {
        sessions.computeIfAbsent(player.getUniqueId(), uuid -> new SessionState(
                player.getInventory().getContents().clone(),
                player.getInventory().getArmorContents().clone(),
                player.getLocation().clone(),
                player.getGameMode(),
                player.getAllowFlight(),
                player.isFlying(),
                player.getHealth(),
                player.getFoodLevel(),
                player.getSaturation(),
                player.getInventory().getHeldItemSlot()
        ));
    }

    private void restoreSession(Player player, boolean teleportLobby) {
        SessionState session = sessions.remove(player.getUniqueId());
        if (session != null) {
            player.getInventory().setContents(session.inventory());
            player.getInventory().setArmorContents(session.armor());
            player.setGameMode(session.gameMode());
            player.setAllowFlight(session.allowFlight());
            player.setFlying(session.flying());
            if (!player.isDead()) {
                double health = Math.max(1.0, Math.min(session.health(), player.getMaxHealth()));
                player.setHealth(health);
            }
            player.setFoodLevel(session.foodLevel());
            player.setSaturation(session.saturation());
            player.getInventory().setHeldItemSlot(session.heldSlot());
        }
        player.removePotionEffect(PotionEffectType.SPEED);
        if (teleportLobby) {
            ArenaMap map = currentMap();
            Location destination = map == null ? null : map.lobby();
            if (destination == null && session != null) {
                destination = session.location();
            }
            if (destination != null) {
                player.teleport(destination);
            }
        }
    }

    private void regenerateArena() {
        ArenaMap map = currentMap();
        if (map == null || !map.ready()) {
            return;
        }
        List<String> snowBlocks = arenaYaml.getStringList("maps." + map.id() + ".snow");
        if (snowBlocks.isEmpty()) {
            map.forEachSnowBlock(block -> block.setType(snowMaterial, false));
            return;
        }
        for (String value : snowBlocks) {
            Location location = LocationCodec.fromCompact(value);
            if (location != null && location.getWorld() != null) {
                location.getBlock().setType(snowMaterial, false);
            }
        }
    }

    private void spawnCelebrationFireworks() {
        if (!plugin.getConfig().getBoolean("settings.celebration-fireworks.enabled", true)) {
            return;
        }
        ArenaMap map = currentMap();
        if (map == null || map.spawn() == null || map.spawn().getWorld() == null) {
            return;
        }
        int count = Math.max(1, plugin.getConfig().getInt("settings.celebration-fireworks.count", 3));
        int intervalTicks = Math.max(0, plugin.getConfig().getInt("settings.celebration-fireworks.interval-ticks", 10));
        for (int i = 0; i < count; i++) {
            long delay = (long) i * intervalTicks;
            Bukkit.getScheduler().runTaskLater(plugin, () -> spawnCelebrationFirework(map.spawn()), delay);
        }
    }

    private void spawnCelebrationFirework(Location location) {
        if (location == null || location.getWorld() == null) {
            return;
        }
        Firework firework = location.getWorld().spawn(location.clone().add(0.0, 1.0, 0.0), Firework.class);
        FireworkMeta meta = firework.getFireworkMeta();
        meta.setPower(Math.max(0, plugin.getConfig().getInt("settings.celebration-fireworks.power", 1)));
        meta.addEffect(FireworkEffect.builder()
                .with(FireworkEffect.Type.BALL)
                .withColor(fireworkColor())
                .withFade(Color.WHITE)
                .trail(false)
                .flicker(true)
                .build());
        firework.setFireworkMeta(meta);
    }

    private Color fireworkColor() {
        String value = plugin.getConfig().getString("settings.celebration-fireworks.color", "#6699d8");
        if (value == null || value.isBlank()) {
            return Color.fromRGB(102, 153, 216);
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        try {
            return switch (normalized) {
                case "light_blue", "light-blue" -> Color.fromRGB(102, 153, 216);
                case "blue" -> Color.BLUE;
                case "aqua" -> Color.AQUA;
                case "white" -> Color.WHITE;
                case "red" -> Color.RED;
                case "green" -> Color.GREEN;
                default -> {
                    if (normalized.startsWith("#")) {
                        yield Color.fromRGB(Integer.parseInt(normalized.substring(1), 16));
                    }
                    String[] parts = normalized.split(",");
                    if (parts.length == 3) {
                        yield Color.fromRGB(
                                Math.max(0, Math.min(255, Integer.parseInt(parts[0].trim()))),
                                Math.max(0, Math.min(255, Integer.parseInt(parts[1].trim()))),
                                Math.max(0, Math.min(255, Integer.parseInt(parts[2].trim())))
                        );
                    }
                    yield Color.fromRGB(102, 153, 216);
                }
            };
        } catch (IllegalArgumentException ignored) {
            return Color.fromRGB(102, 153, 216);
        }
    }

    private void checkWaterLosses() {
        for (Player player : new ArrayList<>(onlineActivePlayers())) {
            Location location = player.getLocation();
            if (touchesDeathBlock(location) || fellBelowArena(location)) {
                eliminate(player, "messages.lost", false, true);
            }
        }
    }

    private boolean touchesDeathBlock(Location location) {
        if (location == null || location.getWorld() == null) {
            return false;
        }
        Block feet = location.getBlock();
        Block below = location.clone().subtract(0, 1, 0).getBlock();
        return feet.getType() == deathMaterial || below.getType() == deathMaterial;
    }

    private boolean fellBelowArena(Location location) {
        ArenaMap map = currentMap();
        return map != null && map.ready() && map.inHorizontalArena(location) && location.getY() < map.snowMinY() - 3;
    }

    private void checkAfk() {
        int seconds = plugin.getConfig().getInt("settings.afk-kick-seconds", 45);
        if (seconds <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Player player : new ArrayList<>(onlineActivePlayers())) {
            long moved = lastMove.getOrDefault(player.getUniqueId(), now);
            if (now - moved >= seconds * 1000L) {
                eliminate(player, "messages.afk-kick", true, true);
            }
        }
    }

    private void checkMustMine() {
        if (!plugin.getConfig().getBoolean("settings.must-mine.enabled", true)) {
            return;
        }
        List<Map<?, ?>> intervals = plugin.getConfig().getMapList("settings.must-mine.intervals");
        int stage = -1;
        int breakEverySeconds = 0;
        long elapsedSeconds = (System.currentTimeMillis() - gameStartedAt) / 1000L;
        for (int i = 0; i < intervals.size(); i++) {
            Map<?, ?> entry = intervals.get(i);
            int after = number(entry.get("after-seconds"));
            if (elapsedSeconds >= after) {
                stage = i;
                breakEverySeconds = number(entry.get("break-every-seconds"));
            }
        }
        if (stage < 0 || breakEverySeconds <= 0) {
            return;
        }
        if (stage != mustMineStage) {
            mustMineStage = stage;
            broadcast("messages.must-mine-enabled", Map.of("{seconds}", Integer.toString(breakEverySeconds)));
        }
        long now = System.currentTimeMillis();
        for (Player player : new ArrayList<>(onlineActivePlayers())) {
            if (now - lastBreak.getOrDefault(player.getUniqueId(), gameStartedAt) >= breakEverySeconds * 1000L) {
                eliminate(player, "messages.must-mine-loss", false, true);
            }
        }
    }

    private void checkMapRotation() {
        int rotationSeconds = plugin.getConfig().getInt("settings.map-rotation-seconds", 0);
        if (rotationSeconds <= 0 || maps.size() <= 1) {
            return;
        }
        long elapsed = (System.currentTimeMillis() - lastRotationAt) / 1000L;
        long remaining = rotationSeconds - elapsed;
        if (remaining <= 60 && remaining > 0 && !rotationWarningSent) {
            rotationWarningSent = true;
            broadcast("messages.map-rotate-in", Map.of("{time}", formatTime(remaining)));
        }
        if (elapsed < rotationSeconds) {
            return;
        }
        if (state == GameState.ACTIVE || state == GameState.COUNTDOWN || state == GameState.ENDING) {
            if (!rotateAfterMatch) {
                rotateAfterMatch = true;
                broadcast("messages.map-rotate-after", Map.of());
            }
            return;
        }
        rotateNow();
    }

    private void spawnPlayerParticles() {
        for (Player player : onlineActivePlayers()) {
            PlayerData data = dataManager.get(player);
            ParticleSpec particle = registry.particleSpec(ContentRegistry.CATEGORY_PLAYER_PARTICLES, data.equippedCosmetic(ContentRegistry.CATEGORY_PLAYER_PARTICLES));
            if (particle != null) {
                spawnParticle(player.getLocation().add(0, 1.0, 0), particle, 4, 0.25, 0.35, 0.25, 0.0);
            }
        }
    }

    private void breakSnowBlock(Player player, Block block) {
        removeSnowBlock(block);
        lastBreak.put(player.getUniqueId(), System.currentTimeMillis());
        spawnBreakParticle(block.getLocation().add(0.5, 0.5, 0.5), player);
    }

    private void removeSnowBlock(Block block) {
        sendImmediateAirChange(block);
        block.setType(Material.AIR, false);
    }

    private void sendImmediateAirChange(Block block) {
        if (block == null || block.getWorld() == null) {
            return;
        }
        Location location = block.getLocation();
        BlockData air = Material.AIR.createBlockData();
        for (Player viewer : onlineActivePlayers()) {
            if (viewer.getWorld().equals(block.getWorld())) {
                viewer.sendBlockChange(location, air);
            }
        }
    }

    private void spawnBreakParticle(Location location, Player player) {
        ParticleSpec particle = null;
        if (player != null) {
            PlayerData data = dataManager.get(player);
            particle = registry.particleSpec(ContentRegistry.CATEGORY_BLOCK_BREAK_PARTICLES, data.equippedCosmetic(ContentRegistry.CATEGORY_BLOCK_BREAK_PARTICLES));
        }
        if (particle == null) {
            particle = new ParticleSpec(Particle.SNOWFLAKE, null);
        }
        spawnParticle(location, particle, 10, 0.35, 0.25, 0.35, 0.02);
    }

    private void spawnParticle(Location location, ParticleSpec spec, int count, double offsetX, double offsetY, double offsetZ, double extra) {
        if (location == null || location.getWorld() == null || spec == null || spec.particle() == null) {
            return;
        }
        if (spec.data() == null) {
            location.getWorld().spawnParticle(spec.particle(), location, count, offsetX, offsetY, offsetZ, extra);
            return;
        }
        location.getWorld().spawnParticle(spec.particle(), location, count, offsetX, offsetY, offsetZ, extra, spec.data());
    }

    private void applyGear(Player player, PlayerData data) {
        Map<EquipmentSlot, ItemStack> gear = registry.gearFor(data);
        PlayerInventory inventory = player.getInventory();
        for (Map.Entry<EquipmentSlot, ItemStack> entry : gear.entrySet()) {
            switch (entry.getKey()) {
                case HEAD -> inventory.setHelmet(entry.getValue());
                case CHEST -> inventory.setChestplate(entry.getValue());
                case LEGS -> inventory.setLeggings(entry.getValue());
                case FEET -> inventory.setBoots(entry.getValue());
                default -> {
                }
            }
        }
    }

    private boolean isHoldingShovel(Player player) {
        return registry.isSpleefShovel(player.getInventory().getItemInMainHand());
    }

    private void awardCoins(Player player, double baseCoins) {
        double gained = dataManager.addCoins(player, baseCoins);
        if (gained > 0) {
            plugin.message(player, "messages.coins-gained", Map.of("{coins}", dataManager.formatCoins(gained)));
        }
    }

    private List<Player> onlineQueuedPlayers() {
        List<Player> players = new ArrayList<>();
        for (UUID uuid : queue) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                players.add(player);
            }
        }
        return players;
    }

    private List<Player> onlineActivePlayers() {
        List<Player> players = new ArrayList<>();
        for (UUID uuid : new HashSet<>(activePlayers)) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && player.isOnline()) {
                players.add(player);
            }
        }
        return players;
    }

    private void purgeOfflineQueue() {
        queue.removeIf(uuid -> Bukkit.getPlayer(uuid) == null);
    }

    private void updateScoreboard(Player player) {
        if (scoreboardService != null) {
            scoreboardService.update(player);
        }
    }

    private void broadcast(String path, Map<String, String> replacements) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            plugin.message(player, path, replacements);
        }
    }

    private void loadMaps() {
        maps.clear();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("maps");
        if (section != null) {
            for (String id : section.getKeys(false)) {
                maps.add(ArenaMap.fromConfig(id, section.getConfigurationSection(id)));
            }
        }
        if (maps.isEmpty()) {
            maps.add(ArenaMap.fromConfig("default", null));
        }
        String configuredCurrent = plugin.getConfig().getString("current-map", maps.get(0).id());
        currentMapIndex = 0;
        for (int i = 0; i < maps.size(); i++) {
            if (maps.get(i).id().equalsIgnoreCase(configuredCurrent)) {
                currentMapIndex = i;
                break;
            }
        }
    }

    private void loadArenaSnapshots() {
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }
        arenaFile = new File(plugin.getDataFolder(), "arenas.yml");
        arenaYaml = YamlConfiguration.loadConfiguration(arenaFile);
    }

    private ArenaMap mapById(String mapId) {
        for (ArenaMap map : maps) {
            if (map.id().equalsIgnoreCase(mapId)) {
                return map;
            }
        }
        return null;
    }

    private int number(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(value.toString());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private String formatTime(long seconds) {
        if (seconds >= 60) {
            long minutes = seconds / 60;
            return minutes + " " + (minutes == 1 ? "minute" : "minutes");
        }
        return seconds + " " + (seconds == 1 ? "second" : "seconds");
    }

    private int doubleJumpMaxJumps() {
        return Math.max(0, plugin.getConfig().getInt("settings.double-jump.max-jumps", 3));
    }

    private boolean canBypassArenaBreakProtection(Player player) {
        return player != null && player.isOp() && !isActive(player);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.LEFT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        if (handleSnowBreak(event.getPlayer(), event.getClickedBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        ArenaMap map = currentMap();
        if (map == null || !map.inArena(event.getBlock().getLocation())) {
            return;
        }
        if (canBypassArenaBreakProtection(event.getPlayer())) {
            return;
        }
        event.setDropItems(false);
        event.setExpToDrop(0);
        event.setCancelled(true);
        handleSnowBreak(event.getPlayer(), event.getBlock());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent event) {
        ArenaMap map = currentMap();
        if (map != null && map.inArena(event.getBlock().getLocation())) {
            if (canBypassArenaBreakProtection(event.getPlayer())) {
                return;
            }
            event.setCancelled(true);
            handleSnowBreak(event.getPlayer(), event.getBlock());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        ArenaMap map = currentMap();
        if (map != null && map.inArena(event.getBlockPlaced().getLocation())) {
            if (canBypassArenaBreakProtection(event.getPlayer())) {
                return;
            }
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!isActive(event.getPlayer()) || event.getTo() == null) {
            return;
        }
        Location previous = lastMoveLocation.get(event.getPlayer().getUniqueId());
        Location to = event.getTo();
        if (previous == null || previous.getWorld() != to.getWorld() || previous.distanceSquared(to) > 0.04) {
            lastMove.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
            lastMoveLocation.put(event.getPlayer().getUniqueId(), to.clone());
        }
        if (state == GameState.ACTIVE && (touchesDeathBlock(to) || fellBelowArena(to))) {
            eliminate(event.getPlayer(), "messages.lost", false, true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        Player player = event.getPlayer();
        if (state != GameState.ACTIVE || !isActive(player) || !plugin.getConfig().getBoolean("settings.double-jump.enabled", true)) {
            return;
        }
        event.setCancelled(true);
        player.setFlying(false);
        int remaining = doubleJumpsRemaining.getOrDefault(player.getUniqueId(), 0);
        if (remaining <= 0) {
            player.setAllowFlight(false);
            showDoubleJumpsRemaining(player, 0);
            return;
        }
        remaining--;
        doubleJumpsRemaining.put(player.getUniqueId(), remaining);
        Vector direction = player.getLocation().getDirection();
        direction.setY(0);
        if (direction.lengthSquared() > 0.0) {
            direction.normalize();
        }
        double multiplier = Math.max(0.0, plugin.getConfig().getDouble("settings.double-jump.multiplier", 0.5));
        double height = Math.max(0.0, plugin.getConfig().getDouble("settings.double-jump.height", 0.3));
        if (!hasDoubleJumpClearance(player, direction, multiplier)) {
            direction.zero();
        }
        Vector velocity = direction.multiply(multiplier).setY(height);
        player.setVelocity(velocity);
        resetDoubleJumpFlight(player, remaining);
        showDoubleJumpsRemaining(player, remaining);
    }

    private boolean hasDoubleJumpClearance(Player player, Vector direction, double distance) {
        if (direction.lengthSquared() <= 0.0) {
            return true;
        }
        Location origin = player.getLocation();
        if (origin.getWorld() == null) {
            return false;
        }
        Vector side = new Vector(-direction.getZ(), 0.0, direction.getX());
        if (side.lengthSquared() > 0.0) {
            side.normalize();
        }
        double maxDistance = Math.max(PLAYER_COLLISION_RADIUS, distance + PLAYER_COLLISION_RADIUS);
        for (double forward = DOUBLE_JUMP_CLEARANCE_STEP; forward <= maxDistance; forward += DOUBLE_JUMP_CLEARANCE_STEP) {
            Location center = origin.clone().add(direction.getX() * forward, 0.0, direction.getZ() * forward);
            if (!hasTwoBlockTallClearance(center, side)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasTwoBlockTallClearance(Location center, Vector side) {
        for (double offset : List.of(0.0, PLAYER_COLLISION_RADIUS, -PLAYER_COLLISION_RADIUS)) {
            Location sample = center.clone().add(side.getX() * offset, 0.0, side.getZ() * offset);
            if (!sample.getBlock().isPassable() || !sample.clone().add(0.0, 1.0, 0.0).getBlock().isPassable()) {
                return false;
            }
        }
        return true;
    }

    private void resetDoubleJumpFlight(Player player, int remaining) {
        player.setAllowFlight(false);
        if (remaining <= 0) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (state == GameState.ACTIVE
                    && player.isOnline()
                    && isActive(player)
                    && plugin.getConfig().getBoolean("settings.double-jump.enabled", true)
                    && doubleJumpsRemaining.getOrDefault(player.getUniqueId(), 0) > 0) {
                player.setFlying(false);
                player.setAllowFlight(true);
            }
        }, 1L);
    }

    private void showDoubleJumpsRemaining(Player player, int remaining) {
        plugin.actionBar(player, "messages.double-jumps-remaining", Map.of(
                "{remaining}", Integer.toString(Math.max(0, remaining)),
                "{max}", Integer.toString(doubleJumpMaxJumps())
        ));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        queue.remove(event.getPlayer().getUniqueId());
        if (activePlayers.contains(event.getPlayer().getUniqueId())) {
            eliminate(event.getPlayer(), "", true, false);
        }
        sessions.remove(event.getPlayer().getUniqueId());
        doubleJumpsRemaining.remove(event.getPlayer().getUniqueId());
        dataManager.save();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        dataManager.get(event.getPlayer());
        ArenaMap map = currentMap();
        if (plugin.getConfig().getBoolean("settings.first-join-spawn", true) && map != null && map.lobby() != null) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!isActive(event.getPlayer())) {
                    event.getPlayer().teleport(map.lobby());
                    giveMenuItems(event.getPlayer());
                }
            });
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> giveMenuItems(event.getPlayer()));
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        ArenaMap map = currentMap();
        if (map != null && map.lobby() != null) {
            event.setRespawnLocation(map.lobby());
        }
        boolean hasSavedSession = sessions.containsKey(event.getPlayer().getUniqueId());
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (hasSavedSession) {
                restoreSession(event.getPlayer(), false);
            }
            giveMenuItems(event.getPlayer());
        });
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!isActive(player)) {
            return;
        }
        event.getDrops().clear();
        event.setDroppedExp(0);
        event.setDeathMessage(null);
        eliminate(player, "messages.lost", false, false);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && isActive(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && isActive(player)) {
            event.setCancelled(true);
        }
    }
}
