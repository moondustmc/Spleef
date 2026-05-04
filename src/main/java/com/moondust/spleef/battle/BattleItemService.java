package com.moondust.spleef.battle;

import com.moondust.spleef.Spleef;
import com.moondust.spleef.content.BattleItemDefinition;
import com.moondust.spleef.content.ContentRegistry;
import com.moondust.spleef.game.GameManager;
import com.moondust.spleef.player.PlayerData;
import com.moondust.spleef.player.PlayerDataManager;
import org.bukkit.Location;
import org.bukkit.Material;
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
    private final Spleef plugin;
    private final GameManager gameManager;
    private final PlayerDataManager dataManager;
    private final ContentRegistry registry;
    private final Random random = new Random();

    public BattleItemService(Spleef plugin, GameManager gameManager, PlayerDataManager dataManager, ContentRegistry registry) {
        this.plugin = plugin;
        this.gameManager = gameManager;
        this.dataManager = dataManager;
        this.registry = registry;
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
            int amount = Math.min(64, data.battleItemCount(id));
            ItemStack item = registry.battleItemStack(id, amount);
            if (item != null && slot <= 2) {
                player.getInventory().setItem(slot, item);
                slot++;
            }
        }
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
            spawnBomb(location, definition);
        } else {
            gameManager.breakSnowAt(location, definition.radius());
        }
        event.getEntity().remove();
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
        Item bomb = location.getWorld().dropItem(location, new ItemStack(definition.material()));
        bomb.setPickupDelay(Integer.MAX_VALUE);
        bomb.setGravity(false);
        bomb.setVelocity(new Vector(0, 0, 0));
        int fuse = Math.max(1, definition.fuseSeconds());
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!bomb.isDead()) {
                    Location detonate = bomb.getLocation();
                    bomb.remove();
                    gameManager.breakSnowAt(detonate, Math.max(1, definition.radius()));
                }
            }
        }.runTaskLater(plugin, fuse * 20L);
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
