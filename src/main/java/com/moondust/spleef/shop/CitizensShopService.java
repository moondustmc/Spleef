package com.moondust.spleef.shop;

import com.moondust.spleef.Spleef;
import net.citizensnpcs.api.CitizensAPI;
import net.citizensnpcs.api.event.NPCRightClickEvent;
import net.citizensnpcs.api.event.NPCSpawnEvent;
import net.citizensnpcs.api.npc.NPC;
import net.citizensnpcs.api.npc.NPCRegistry;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.util.RayTraceResult;

import java.util.ArrayList;
import java.util.Map;

public final class CitizensShopService implements NpcShopService, Listener {
    private static final String METADATA_SHOP = "spleef.shop";
    private static final String SCOREBOARD_TAG_PREFIX = "spleef-shop:";
    private static final String CONFIG_PATH = "npc-shops.citizens";

    private final Spleef plugin;
    private final ShopService shopService;

    public CitizensShopService(Spleef plugin, ShopService shopService) {
        this.plugin = plugin;
        this.shopService = shopService;
    }

    @Override
    public boolean available() {
        return Bukkit.getPluginManager().isPluginEnabled("Citizens") && CitizensAPI.hasImplementation();
    }

    @Override
    public boolean handlesEntity(Entity entity) {
        return available() && npcFromEntity(entity) != null;
    }

    @Override
    public boolean bindShop(Player player, String shopId) {
        if (!available()) {
            plugin.messageText(player, "&cCitizens is not enabled.", Map.of());
            return false;
        }
        if (!shopService.shopExists(shopId)) {
            plugin.messageText(player, "&cUnknown shop id.", Map.of());
            return false;
        }
        NPC npc = selectedOrTargetedNpc(player);
        if (npc == null) {
            plugin.messageText(player, "&cSelect a Citizens NPC or look directly at one.", Map.of());
            return false;
        }
        clearShopTag(npc);
        saveBinding(npc, shopId);
        npc.data().setPersistent(METADATA_SHOP, shopId);
        applyShopTag(npc);
        npc.getOwningRegistry().saveToStore();
        plugin.messageText(player, "&aBound Citizens NPC &f" + npc.getName() + " &ato shop &f" + shopId + "&a.", Map.of());
        return true;
    }

    @Override
    public boolean clearShop(Player player) {
        if (!available()) {
            plugin.messageText(player, "&cCitizens is not enabled.", Map.of());
            return false;
        }
        NPC npc = selectedOrTargetedNpc(player);
        if (npc == null) {
            plugin.messageText(player, "&cSelect a Citizens NPC or look directly at one.", Map.of());
            return false;
        }
        clearShopTag(npc);
        removeBinding(npc);
        npc.data().remove(METADATA_SHOP);
        npc.getOwningRegistry().saveToStore();
        plugin.messageText(player, "&aCleared Spleef shop binding from Citizens NPC &f" + npc.getName() + "&a.", Map.of());
        return true;
    }

    @Override
    public void reload() {
        applyExistingTags();
    }

    public void applyExistingTags() {
        if (!available()) {
            return;
        }
        for (NPCRegistry registry : CitizensAPI.getNPCRegistries()) {
            for (NPC npc : registry) {
                syncMetadataToConfig(npc);
                applyShopTag(npc);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onNpcRightClick(NPCRightClickEvent event) {
        String shopId = shopId(event.getNPC());
        if (shopId.isBlank()) {
            return;
        }
        event.setCancelled(true);
        shopService.openShop(event.getClicker(), shopId);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onNpcSpawn(NPCSpawnEvent event) {
        applyShopTag(event.getNPC());
    }

    private NPC selectedOrTargetedNpc(Player player) {
        NPC selected = CitizensAPI.getDefaultNPCSelector().getSelected(player);
        if (selected != null) {
            return selected;
        }
        RayTraceResult result = player.getWorld().rayTraceEntities(
                player.getEyeLocation(),
                player.getEyeLocation().getDirection(),
                8.0,
                1.0,
                entity -> npcFromEntity(entity) != null
        );
        if (result == null || result.getHitEntity() == null) {
            return null;
        }
        return npcFromEntity(result.getHitEntity());
    }

    private NPC npcFromEntity(Entity entity) {
        if (entity == null) {
            return null;
        }
        for (NPCRegistry registry : CitizensAPI.getNPCRegistries()) {
            NPC npc = registry.getNPC(entity);
            if (npc != null) {
                return npc;
            }
        }
        return null;
    }

    private String shopId(NPC npc) {
        if (npc == null) {
            return "";
        }
        String configured = configuredShopId(npc);
        if (!configured.isBlank()) {
            return configured;
        }
        if (!npc.data().has(METADATA_SHOP)) {
            return "";
        }
        String shopId = npc.data().get(METADATA_SHOP, "");
        return shopId == null ? "" : shopId;
    }

    private void applyShopTag(NPC npc) {
        String shopId = shopId(npc);
        if (!shopId.isBlank()) {
            npc.data().setPersistent(METADATA_SHOP, shopId);
        }
        if (!npc.isSpawned() || npc.getEntity() == null) {
            return;
        }
        clearShopTag(npc);
        if (shopId.isBlank()) {
            return;
        }
        npc.getEntity().addScoreboardTag(SCOREBOARD_TAG_PREFIX + shopId);
    }

    private void clearShopTag(NPC npc) {
        if (npc == null || !npc.isSpawned() || npc.getEntity() == null) {
            return;
        }
        Entity entity = npc.getEntity();
        for (String tag : new ArrayList<>(entity.getScoreboardTags())) {
            if (tag.startsWith(SCOREBOARD_TAG_PREFIX)) {
                entity.removeScoreboardTag(tag);
            }
        }
    }

    private void saveBinding(NPC npc, String shopId) {
        plugin.getConfig().set(bindingPath(npc), shopId);
        plugin.saveConfig();
    }

    private void removeBinding(NPC npc) {
        plugin.getConfig().set(bindingPath(npc), null);
        plugin.saveConfig();
    }

    private void syncMetadataToConfig(NPC npc) {
        if (npc == null || !configuredShopId(npc).isBlank() || !npc.data().has(METADATA_SHOP)) {
            return;
        }
        String shopId = npc.data().get(METADATA_SHOP, "");
        if (shopId != null && !shopId.isBlank() && shopService.shopExists(shopId)) {
            saveBinding(npc, shopId);
        }
    }

    private String configuredShopId(NPC npc) {
        String shopId = plugin.getConfig().getString(bindingPath(npc), "");
        if (shopId == null || shopId.isBlank() || !shopService.shopExists(shopId)) {
            return "";
        }
        return shopId;
    }

    private String bindingPath(NPC npc) {
        return CONFIG_PATH + "." + npc.getUniqueId();
    }
}
