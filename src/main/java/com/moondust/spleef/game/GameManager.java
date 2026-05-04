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
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
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
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

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
    private final Spleef plugin;
    private final PlayerDataManager dataManager;
    private final ContentRegistry registry;
    private final Set<UUID> queue = new LinkedHashSet<>();
    private final Set<UUID> activePlayers = new LinkedHashSet<>();
    private final Map<UUID, SessionState> sessions = new HashMap<>();
    private final Map<UUID, Long> lastBreak = new HashMap<>();
    private final Map<UUID, Long> lastMove = new HashMap<>();
    private final Map<UUID, Location> lastMoveLocation = new HashMap<>();
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
        giveCosmeticsMenuItem(player);
        plugin.message(player, "messages.join");
        updateScoreboard(player);
        tryStart();
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
                giveCosmeticsMenuItem(player);
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
                        block.setType(Material.AIR, false);
                        spawnBreakParticle(block.getLocation().add(0.5, 0.5, 0.5), null);
                        broken++;
                        break;
                    }
                }
            }
        }
        return broken;
    }

    public void giveCosmeticsMenuItem(Player player) {
        if (!plugin.getConfig().getBoolean("settings.cosmetics-menu-item.enabled", true) || isActive(player)) {
            return;
        }
        int slot = plugin.getConfig().getInt("settings.cosmetics-menu-item.slot", 8);
        if (slot < 0 || slot > 35) {
            return;
        }
        Material material = registry.material(plugin.getConfig().getString("settings.cosmetics-menu-item.material"), Material.CHEST);
        String name = plugin.getConfig().getString("settings.cosmetics-menu-item.name", "&bCosmetics");
        player.getInventory().setItem(slot, registry.menuAction("open_cosmetics", material, name));
    }

    public ItemStack createCosmeticsMenuItem() {
        Material material = registry.material(plugin.getConfig().getString("settings.cosmetics-menu-item.material"), Material.CHEST);
        String name = plugin.getConfig().getString("settings.cosmetics-menu-item.name", "&bCosmetics");
        return registry.menuAction("open_cosmetics", material, name);
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
            activePlayers.add(player.getUniqueId());
            preparePlayerForArena(player, false);
            player.teleport(map.spawn());
            updateScoreboard(player);
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

    private void abortCountdown() {
        for (Player player : onlineActivePlayers()) {
            restoreSession(player, true);
            updateScoreboard(player);
            giveCosmeticsMenuItem(player);
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
        }
        for (Player player : onlineActivePlayers()) {
            restoreSession(player, true);
            updateScoreboard(player);
            giveCosmeticsMenuItem(player);
        }
        activePlayers.clear();
        lastBreak.clear();
        lastMove.clear();
        lastMoveLocation.clear();
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
        PlayerData data = dataManager.get(player);
        data.currentStreak(0);
        if (messagePath != null && !messagePath.isBlank()) {
            plugin.message(player, messagePath);
        }
        if (restoreNow) {
            restoreSession(player, true);
            giveCosmeticsMenuItem(player);
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
        block.setType(Material.AIR, false);
        lastBreak.put(player.getUniqueId(), System.currentTimeMillis());
        spawnBreakParticle(block.getLocation().add(0.5, 0.5, 0.5), player);
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
        ItemStack item = player.getInventory().getItemInMainHand();
        return item != null && item.getType().name().endsWith("_SHOVEL");
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

    private boolean canBypassArenaBreakProtection(Player player) {
        return player != null && player.isOp() && !isActive(player);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.LEFT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        if (handleSnowBreak(event.getPlayer(), event.getClickedBlock())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        ArenaMap map = currentMap();
        if (map == null || !map.inArena(event.getBlock().getLocation())) {
            return;
        }
        if (canBypassArenaBreakProtection(event.getPlayer())) {
            return;
        }
        event.setCancelled(true);
        handleSnowBreak(event.getPlayer(), event.getBlock());
    }

    @EventHandler(ignoreCancelled = true)
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

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        queue.remove(event.getPlayer().getUniqueId());
        if (activePlayers.contains(event.getPlayer().getUniqueId())) {
            eliminate(event.getPlayer(), "", true, false);
        }
        sessions.remove(event.getPlayer().getUniqueId());
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
                    giveCosmeticsMenuItem(event.getPlayer());
                }
            });
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> giveCosmeticsMenuItem(event.getPlayer()));
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        ArenaMap map = currentMap();
        if (map != null && map.lobby() != null) {
            event.setRespawnLocation(map.lobby());
        }
        if (sessions.containsKey(event.getPlayer().getUniqueId())) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                restoreSession(event.getPlayer(), false);
                giveCosmeticsMenuItem(event.getPlayer());
            });
        }
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
