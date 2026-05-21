package com.moondust.spleef.battle;

import com.moondust.spleef.Spleef;
import com.moondust.spleef.content.BattleItemDefinition;
import com.moondust.spleef.content.ContentRegistry;
import com.moondust.spleef.game.GameManager;
import com.moondust.spleef.player.PlayerData;
import com.moondust.spleef.player.PlayerDataManager;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Snowball;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public final class BattleItemService implements Listener {
    private static final int BOMB_FRAGMENT_COUNT = 15;
    private static final Particle.DustOptions BOMB_REDSTONE_DUST = new Particle.DustOptions(Color.RED, 0.85F);
    private static final Particle.DustOptions BOMB_RADIUS_DUST = new Particle.DustOptions(Color.fromRGB(180, 0, 0), 0.55F);
    private static final String SNOWBALL_BUCKET_ID = "snowball_bucket";
    private static final int SNOWBALL_BUCKET_STACK_CAP = 4;
    private static final int SNOWBALL_BUCKET_ARENA_LIMIT = 32;

    private final Spleef plugin;
    private final GameManager gameManager;
    private final PlayerDataManager dataManager;
    private final ContentRegistry registry;
    private final NamespacedKey bombFragmentKey;
    private final Random random = new Random();

    public BattleItemService(Spleef plugin, GameManager gameManager, PlayerDataManager dataManager, ContentRegistry registry) {
        this.plugin = plugin;
        this.gameManager = gameManager;
        this.dataManager = dataManager;
        this.registry = registry;
        this.bombFragmentKey = new NamespacedKey(plugin, "bomb_fragment");
    }

    public void giveLoadout(Player player) {
        PlayerData data = dataManager.get(player);
        Set<String> uniqueLoadout = new LinkedHashSet<>();
        for (String id : data.battleLoadout()) {
            if (id != null && !id.isBlank() && data.battleItemCount(id) > 0 && registry.battleItem(id) != null) {
                uniqueLoadout.add(id);
                if (uniqueLoadout.size() == 2) {
                    break;
                }
            }
        }
        int slot = 1;
        for (String id : uniqueLoadout) {
            if (SNOWBALL_BUCKET_ID.equals(id)) {
                slot = giveSnowballBucketLoadout(player, data.battleItemCount(id), slot);
                continue;
            }
            int amount = Math.min(64, data.battleItemCount(id));
            ItemStack item = registry.battleItemStack(id, amount);
            if (item != null && slot < player.getInventory().getStorageContents().length) {
                player.getInventory().setItem(slot, item);
                slot++;
            }
        }
    }

    private int giveSnowballBucketLoadout(Player player, int ownedAmount, int firstSlot) {
        BattleItemDefinition definition = registry.battleItem(SNOWBALL_BUCKET_ID);
        if (definition == null || ownedAmount <= 0) {
            return firstSlot;
        }
        int stackCap = definition.maxStackSize() > 0
                ? Math.min(definition.maxStackSize(), SNOWBALL_BUCKET_STACK_CAP)
                : SNOWBALL_BUCKET_STACK_CAP;
        int remaining = Math.min(SNOWBALL_BUCKET_ARENA_LIMIT, ownedAmount);
        int slot = firstSlot;
        int storageSize = player.getInventory().getStorageContents().length;
        while (remaining > 0 && slot < storageSize) {
            int amount = Math.min(stackCap, remaining);
            ItemStack item = registry.battleItemStack(SNOWBALL_BUCKET_ID, amount);
            if (item == null) {
                return slot;
            }
            player.getInventory().setItem(slot, item);
            remaining -= amount;
            slot++;
        }
        return slot;
    }

    public Map<String, Integer> availableLoadout(Player player) {
        PlayerData data = dataManager.get(player);
        Map<String, Integer> available = new HashMap<>();
        for (String id : data.battleLoadout()) {
            if (id != null && !id.isBlank() && data.battleItemCount(id) > 0) {
                available.put(id, data.battleItemCount(id));
                if (available.size() == 2) {
                    break;
                }
            }
        }
        return available;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Player player = event.getPlayer();
        if (!gameManager.isActive(player)) {
            return;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        String id = registry.battleItemId(item);
        if (id.isBlank()) {
            return;
        }
        event.setCancelled(true);
        useBattleItem(player, item, id);
    }

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent event) {
        if (event.getEntity().getPersistentDataContainer().getOrDefault(bombFragmentKey, PersistentDataType.BYTE, (byte) 0) == 1) {
            Location location = event.getHitBlock() == null ? event.getEntity().getLocation() : event.getHitBlock().getLocation().add(0.5, 0.5, 0.5);
            if (event.getHitEntity() instanceof Player hitPlayer && gameManager.isActive(hitPlayer)) {
                location = hitPlayer.getLocation().subtract(0.0, 1.0, 0.0);
            }
            gameManager.breakSnowAtSnowLevel(location, 0);
            event.getEntity().remove();
            return;
        }
        String id = event.getEntity().getPersistentDataContainer().getOrDefault(registry.battleItemKey(), PersistentDataType.STRING, "");
        if (id.isBlank()) {
            return;
        }
        BattleItemDefinition definition = registry.battleItem(id);
        if (definition == null) {
            return;
        }
        Location location = event.getHitBlock() == null ? event.getEntity().getLocation() : event.getHitBlock().getLocation().add(0.5, 0.5, 0.5);
        if (event.getHitEntity() instanceof Player hitPlayer && gameManager.isActive(hitPlayer)) {
            location = hitPlayer.getLocation().subtract(0.0, 1.0, 0.0);
        }
        String action = definition.action().toUpperCase(Locale.ROOT);
        if (action.equals("BOMB")) {
            spawnBomb(bombLandingLocation(event, location), definition);
        } else {
            gameManager.breakSnowAt(location, definition.radius());
        }
        event.getEntity().remove();
    }

    private Location bombLandingLocation(ProjectileHitEvent event, Location fallback) {
        if (event.getHitBlock() != null) {
            return event.getHitBlock().getLocation().add(0.5, 1.05, 0.5);
        }
        if (event.getHitEntity() instanceof Player hitPlayer && gameManager.isActive(hitPlayer)) {
            return hitPlayer.getLocation().subtract(0.0, 1.0, 0.0).getBlock().getLocation().add(0.5, 1.05, 0.5);
        }
        return fallback.clone();
    }

    private void useBattleItem(Player player, ItemStack item, String id) {
        BattleItemDefinition definition = registry.battleItem(id);
        if (definition == null) {
            return;
        }
        PlayerData data = dataManager.get(player);
        if (!data.consumeBattleItem(id, 1)) {
            player.getInventory().setItemInMainHand(null);
            return;
        }
        decrementHeldItem(player, item);
        String action = definition.action().toUpperCase(Locale.ROOT);
        switch (action) {
            case "COFFEE" -> useCoffee(player, definition);
            case "BUCKET" -> useBucket(player, definition);
            case "BOMB" -> launchTaggedSnowball(player, definition);
            default -> launchTaggedSnowball(player, definition);
        }
        plugin.actionBar(player, "messages.battle-item-used", Map.of("{item}", registry.battleItemDisplayName(id)));
    }

    private void useCoffee(Player player, BattleItemDefinition definition) {
        PotionEffectType speed = PotionEffectType.SPEED;
        int amplifier = Math.max(0, definition.speedAmplifier() - 1);
        int duration = Math.max(1, definition.durationSeconds()) * 20;
        player.addPotionEffect(new PotionEffect(speed, duration, amplifier, true, true, true));
    }

    private void useBucket(Player player, BattleItemDefinition definition) {
        int count = Math.max(1, definition.projectileCount());
        for (int i = 0; i < count; i++) {
            Snowball snowball = launchTaggedSnowball(player, definition);
            Vector velocity = player.getLocation().getDirection().normalize().multiply(1.35);
            velocity.add(new Vector(random.nextDouble() - 0.5, (random.nextDouble() - 0.25) * 0.35, random.nextDouble() - 0.5).multiply(0.35));
            snowball.setVelocity(velocity);
        }
    }

    private Snowball launchTaggedSnowball(Player player, BattleItemDefinition definition) {
        Snowball snowball = player.launchProjectile(Snowball.class);
        snowball.getPersistentDataContainer().set(registry.battleItemKey(), PersistentDataType.STRING, definition.id());
        snowball.setItem(new ItemStack(Material.SNOWBALL));
        return snowball;
    }

    private void spawnBomb(Location location, BattleItemDefinition definition) {
        Location detonate = location.clone();
        Item bomb = detonate.getWorld().spawn(detonate, Item.class);
        bomb.setItemStack(new ItemStack(definition.material()));
        bomb.setPickupDelay(Integer.MAX_VALUE);
        bomb.setGravity(false);
        bomb.setVelocity(new Vector(0, 0, 0));
        int fuseTicks = Math.max(1, (int) Math.round(Math.max(1.0, definition.fuseSeconds()) * 20.0));
        int radius = Math.max(1, definition.radius());
        detonate.getWorld().playSound(detonate, Sound.ENTITY_CREEPER_PRIMED, 1.0F, 1.0F);
        startBombAura(bomb, detonate, radius, fuseTicks);
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!bomb.isDead()) {
                    bomb.remove();
                    detonate.getWorld().playSound(detonate, Sound.BLOCK_SNIFFER_EGG_PLOP, 1.0F, 1.0F);
                    spawnBombFragments(detonate);
                    gameManager.breakSnowCircleAtSnowLevel(detonate, radius);
                }
            }
        }.runTaskLater(plugin, fuseTicks);
    }

    private void startBombAura(Item bomb, Location location, int radius, int fuseTicks) {
        new BukkitRunnable() {
            private int elapsedTicks = 0;

            @Override
            public void run() {
                if (bomb.isDead() || elapsedTicks >= fuseTicks) {
                    cancel();
                    return;
                }
                Location center = location.clone().add(0.0, 0.12, 0.0);
                if (center.getWorld() == null) {
                    cancel();
                    return;
                }
                bomb.teleport(location);
                bomb.setVelocity(new Vector(0, 0, 0));
                double pulse = 0.55 + 0.18 * Math.sin(elapsedTicks * 0.35);
                for (int i = 0; i < 14; i++) {
                    double angle = i * Math.PI * 2.0 / 14.0;
                    Location point = center.clone().add(Math.cos(angle) * pulse, 0.0, Math.sin(angle) * pulse);
                    center.getWorld().spawnParticle(Particle.SNOWFLAKE, point, 1, 0.015, 0.01, 0.015, 0.0);
                    if (i % 2 == 0) {
                        center.getWorld().spawnParticle(Particle.DUST, point.clone().add(0.0, 0.03, 0.0), 1, 0.01, 0.01, 0.01, 0.0, BOMB_REDSTONE_DUST);
                    }
                }
                if (elapsedTicks % 10 == 0) {
                    spawnBombRadiusRing(center, radius, elapsedTicks);
                }
                elapsedTicks += 5;
            }
        }.runTaskTimer(plugin, 0L, 5L);
    }

    private void spawnBombRadiusRing(Location center, int radius, int elapsedTicks) {
        int points = Math.max(16, Math.min(48, radius * 8));
        double phase = elapsedTicks * 0.025;
        for (int i = 0; i < points; i++) {
            double angle = phase + i * Math.PI * 2.0 / points;
            Location point = center.clone().add(Math.cos(angle) * radius, 0.02, Math.sin(angle) * radius);
            center.getWorld().spawnParticle(Particle.DUST, point, 1, 0.0, 0.0, 0.0, 0.0, BOMB_RADIUS_DUST);
        }
    }

    private void spawnBombFragments(Location location) {
        if (location.getWorld() == null) {
            return;
        }
        Location origin = location.clone().add(0.0, 0.25, 0.0);
        for (int i = 0; i < BOMB_FRAGMENT_COUNT; i++) {
            Snowball snowball = origin.getWorld().spawn(origin, Snowball.class);
            snowball.getPersistentDataContainer().set(bombFragmentKey, PersistentDataType.BYTE, (byte) 1);
            snowball.setItem(new ItemStack(Material.SNOWBALL));
            double angle = random.nextDouble() * Math.PI * 2.0;
            double speed = 0.55 + random.nextDouble() * 0.55;
            double upward = 0.10 + random.nextDouble() * 0.18;
            snowball.setVelocity(new Vector(Math.cos(angle) * speed, upward, Math.sin(angle) * speed));
        }
    }

    private void decrementHeldItem(Player player, ItemStack item) {
        if (item == null) {
            return;
        }
        if (item.getAmount() <= 1) {
            player.getInventory().setItemInMainHand(null);
            return;
        }
        item.setAmount(item.getAmount() - 1);
        player.getInventory().setItemInMainHand(item);
    }
}
