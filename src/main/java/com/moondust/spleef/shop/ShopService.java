package com.moondust.spleef.shop;

import com.moondust.spleef.Spleef;
import com.moondust.spleef.content.ContentRegistry;
import com.moondust.spleef.content.CosmeticDefinition;
import com.moondust.spleef.player.PlayerData;
import com.moondust.spleef.player.PlayerDataManager;
import com.moondust.spleef.util.Chat;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ShopService implements Listener {
    private static final int[] SHOP_ITEM_SLOTS = {11, 12, 13, 14, 15};
    private static final int SHOP_PREVIOUS_PAGE_SLOT = 18;
    private static final int SHOP_NEXT_PAGE_SLOT = 26;
    private static final String PAGE_ENTRY = "__shop_page__";

    private final Spleef plugin;
    private final PlayerDataManager dataManager;
    private final ContentRegistry registry;
    private final NamespacedKey shopIdKey;
    private final NamespacedKey shopEntryKey;
    private final NamespacedKey shopPageKey;

    public ShopService(Spleef plugin, PlayerDataManager dataManager, ContentRegistry registry) {
        this.plugin = plugin;
        this.dataManager = dataManager;
        this.registry = registry;
        this.shopIdKey = new NamespacedKey(plugin, "shop_id");
        this.shopEntryKey = new NamespacedKey(plugin, "shop_entry");
        this.shopPageKey = new NamespacedKey(plugin, "shop_page");
    }

    public boolean shopExists(String shopId) {
        return plugin.getConfig().isConfigurationSection("shops." + shopId);
    }

    public List<String> shopIds() {
        ConfigurationSection shops = plugin.getConfig().getConfigurationSection("shops");
        if (shops == null) {
            return List.of();
        }
        return new ArrayList<>(shops.getKeys(false));
    }

    public void openShop(Player player, String shopId) {
        openShop(player, shopId, 0);
    }

    private void openShop(Player player, String shopId, int page) {
        if (shopId == null || shopId.isBlank()) {
            return;
        }
        ConfigurationSection shop = plugin.getConfig().getConfigurationSection("shops." + shopId);
        if (shop == null) {
            return;
        }
        List<ShopEntry> entries = entries(shopId, shop);
        if (entries.isEmpty()) {
            return;
        }
        int pages = Math.max(1, (int) Math.ceil(entries.size() / (double) SHOP_ITEM_SLOTS.length));
        int actualPage = Math.floorMod(page, pages);
        ShopInventoryHolder holder = new ShopInventoryHolder(shopId, actualPage);
        Inventory inventory = Bukkit.createInventory(holder, 27, Chat.color(shop.getString("title", generatedTitle(shopId))));
        holder.inventory(inventory);
        decorate(inventory);
        int startIndex = actualPage * SHOP_ITEM_SLOTS.length;
        int itemCount = Math.min(entries.size() - startIndex, SHOP_ITEM_SLOTS.length);
        int start = (SHOP_ITEM_SLOTS.length - itemCount) / 2;
        for (int i = 0; i < itemCount; i++) {
            ShopEntry entry = entries.get(startIndex + i);
            inventory.setItem(SHOP_ITEM_SLOTS[start + i], displayItem(player, entry));
        }
        if (pages > 1) {
            if (actualPage > 0) {
                inventory.setItem(SHOP_PREVIOUS_PAGE_SLOT, pageItem(shopId, actualPage - 1, "&ePrevious Page"));
            }
            inventory.setItem(SHOP_NEXT_PAGE_SLOT, pageItem(shopId, actualPage + 1, "&eNext Page"));
        }
        player.openInventory(inventory);
    }

    public void purchase(Player player, String shopId) {
        if (shopId == null || shopId.isBlank()) {
            return;
        }
        ConfigurationSection shop = plugin.getConfig().getConfigurationSection("shops." + shopId);
        if (shop == null) {
            return;
        }
        List<ShopEntry> entries = entries(shopId, shop);
        if (!entries.isEmpty()) {
            purchase(player, entries.getFirst());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onShopClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !(event.getView().getTopInventory().getHolder() instanceof ShopInventoryHolder)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() == null || event.getClickedInventory() != event.getView().getTopInventory()) {
            return;
        }
        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType().isAir() || !clicked.hasItemMeta()) {
            return;
        }
        ItemMeta meta = clicked.getItemMeta();
        if (meta == null) {
            return;
        }
        String shopId = meta.getPersistentDataContainer().getOrDefault(shopIdKey, PersistentDataType.STRING, "");
        String entryId = meta.getPersistentDataContainer().getOrDefault(shopEntryKey, PersistentDataType.STRING, "");
        if (PAGE_ENTRY.equals(entryId)) {
            int page = meta.getPersistentDataContainer().getOrDefault(shopPageKey, PersistentDataType.INTEGER, 0);
            playPageSound(player);
            openShop(player, shopId, page);
            return;
        }
        ShopEntry entry = entry(shopId, entryId);
        if (entry == null) {
            return;
        }
        purchase(player, entry);
        openShop(player, shopId);
    }

    @EventHandler
    public void onShopDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ShopInventoryHolder) {
            event.setCancelled(true);
        }
    }

    private void purchase(Player player, ShopEntry entry) {
        PlayerData data = dataManager.get(player);
        if (entry.freeOnce() && data.claimedRewards().contains(entry.rewardKey())) {
            plugin.message(player, "messages.shop-claimed");
            return;
        }
        if (entry.type().equals("cosmetic")) {
            CosmeticDefinition definition = registry.cosmetic(entry.category(), entry.item());
            if (definition == null) {
                return;
            }
            if (data.ownsCosmetic(entry.category(), entry.item())) {
                plugin.message(player, "messages.cosmetic-already-owned");
                return;
            }
        } else if (registry.battleItem(entry.item()) == null) {
            return;
        }
        if (entry.price() > 0.0 && data.coins() < entry.price()) {
            plugin.message(player, "messages.not-enough-coins", Map.of("{coins}", dataManager.formatCoins(entry.price())));
            return;
        }
        if (entry.price() > 0.0 && !dataManager.removeCoins(player, entry.price())) {
            return;
        }
        if (entry.type().equals("cosmetic")) {
            data.unlockCosmetic(entry.category(), entry.item());
            if (entry.message() == null || entry.message().isBlank()) {
                plugin.message(player, "messages.cosmetic-received", Map.of("{cosmetic}", registry.cosmeticDisplayName(entry.category(), entry.item())));
            } else {
                plugin.messageText(player, entry.message(), Map.of("{cosmetic}", registry.cosmeticDisplayName(entry.category(), entry.item())));
            }
        } else {
            data.addBattleItem(entry.item(), entry.amount());
            plugin.message(player, "messages.battle-item-received", Map.of("{item}", registry.battleItemDisplayName(entry.item())));
        }
        if (entry.freeOnce()) {
            data.claimedRewards().add(entry.rewardKey());
        }
        if (entry.price() > 0.0) {
            plugin.message(player, "messages.purchase-complete");
        }
        playPurchaseSound(player);
        dataManager.save();
    }

    private void decorate(Inventory inventory) {
        ItemStack filler = registry.filler(Material.LIGHT_BLUE_STAINED_GLASS_PANE);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }
    }

    private ItemStack displayItem(Player player, ShopEntry entry) {
        PlayerData data = dataManager.get(player);
        ItemStack item;
        if (entry.type().equals("cosmetic")) {
            CosmeticDefinition definition = registry.cosmetic(entry.category(), entry.item());
            if (definition == null) {
                item = new ItemStack(Material.BARRIER);
            } else {
                item = registry.cosmeticStack(definition);
            }
        } else {
            item = registry.battleItemStack(entry.item(), Math.min(64, entry.amount()));
            if (item == null) {
                item = new ItemStack(Material.BARRIER);
            }
        }
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Chat.color(displayName(entry)));
            List<String> lore = new ArrayList<>();
            lore.add(Chat.color(entry.type().equals("cosmetic") ? "&7Cosmetic" : "&7Battle Item"));
            if (!entry.type().equals("cosmetic") && entry.amount() > 1) {
                lore.add(Chat.color("&7Amount: &f" + entry.amount()));
            }
            lore.add(Chat.color("&7Price: &f" + dataManager.formatCoins(entry.price()) + " coins"));
            if (entry.freeOnce()) {
                lore.add(Chat.color(data.claimedRewards().contains(entry.rewardKey()) ? "&cAlready claimed" : "&aFree once"));
            }
            if (entry.type().equals("cosmetic") && data.ownsCosmetic(entry.category(), entry.item())) {
                lore.add(Chat.color("&eAlready owned"));
            }
            lore.add(Chat.color("&aClick to buy"));
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(shopIdKey, PersistentDataType.STRING, entry.shopId());
            meta.getPersistentDataContainer().set(shopEntryKey, PersistentDataType.STRING, entry.id());
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack pageItem(String shopId, int page, String name) {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Chat.color(name));
            meta.getPersistentDataContainer().set(shopIdKey, PersistentDataType.STRING, shopId);
            meta.getPersistentDataContainer().set(shopEntryKey, PersistentDataType.STRING, PAGE_ENTRY);
            meta.getPersistentDataContainer().set(shopPageKey, PersistentDataType.INTEGER, page);
            item.setItemMeta(meta);
        }
        return item;
    }

    private String displayName(ShopEntry entry) {
        if (entry.name() != null && !entry.name().isBlank()) {
            return entry.name();
        }
        if (entry.type().equals("cosmetic")) {
            return registry.cosmeticDisplayName(entry.category(), entry.item());
        }
        return registry.battleItemDisplayName(entry.item());
    }

    private String generatedTitle(String shopId) {
        String readable = shopId.replace('-', ' ').replace('_', ' ').trim();
        if (readable.isBlank()) {
            return "&bSpleef Shop";
        }
        StringBuilder title = new StringBuilder("&b");
        for (String word : readable.split("\\s+")) {
            if (word.isBlank()) {
                continue;
            }
            if (title.length() > 2) {
                title.append(' ');
            }
            title.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) {
                title.append(word.substring(1).toLowerCase(Locale.ROOT));
            }
        }
        title.append(" Shop");
        return title.toString();
    }

    private void playPageSound(Player player) {
        if (!plugin.getConfig().getBoolean("settings.shop-page-sound.enabled", true)) {
            return;
        }
        String soundName = plugin.getConfig().getString("settings.shop-page-sound.sound", "BLOCK_BAMBOO_BREAK");
        try {
            Sound sound = Sound.valueOf(soundName == null ? "BLOCK_BAMBOO_BREAK" : soundName.toUpperCase(Locale.ROOT));
            float volume = (float) plugin.getConfig().getDouble("settings.shop-page-sound.volume", 1.0);
            float pitch = (float) plugin.getConfig().getDouble("settings.shop-page-sound.pitch", 1.0);
            player.playSound(player.getLocation(), sound, volume, pitch);
        } catch (IllegalArgumentException ignored) {
            player.playSound(player.getLocation(), Sound.BLOCK_BAMBOO_BREAK, 1.0F, 1.0F);
        }
    }

    private void playPurchaseSound(Player player) {
        if (!plugin.getConfig().getBoolean("settings.shop-purchase-sound.enabled", true)) {
            return;
        }
        String soundName = plugin.getConfig().getString("settings.shop-purchase-sound.sound", "BLOCK_AMETHYST_CLUSTER_PLACE");
        try {
            Sound sound = Sound.valueOf(soundName == null ? "BLOCK_AMETHYST_CLUSTER_PLACE" : soundName.toUpperCase(Locale.ROOT));
            float volume = (float) plugin.getConfig().getDouble("settings.shop-purchase-sound.volume", 1.0);
            float pitch = (float) plugin.getConfig().getDouble("settings.shop-purchase-sound.pitch", 1.0);
            player.playSound(player.getLocation(), sound, volume, pitch);
        } catch (IllegalArgumentException ignored) {
            player.playSound(player.getLocation(), Sound.BLOCK_AMETHYST_CLUSTER_PLACE, 1.0F, 1.0F);
        }
    }

    private ShopEntry entry(String shopId, String entryId) {
        ConfigurationSection shop = plugin.getConfig().getConfigurationSection("shops." + shopId);
        if (shop == null) {
            return null;
        }
        for (ShopEntry entry : entries(shopId, shop)) {
            if (entry.id().equals(entryId)) {
                return entry;
            }
        }
        return null;
    }

    private List<ShopEntry> entries(String shopId, ConfigurationSection shop) {
        ConfigurationSection itemsSection = shop.getConfigurationSection("items");
        if (itemsSection != null) {
            List<ShopEntry> entries = new ArrayList<>();
            for (String id : itemsSection.getKeys(false)) {
                ConfigurationSection section = itemsSection.getConfigurationSection(id);
                if (section != null) {
                    entries.add(entryFromSection(shopId, id, section));
                }
            }
            return entries;
        }
        List<Map<?, ?>> itemList = shop.getMapList("items");
        if (!itemList.isEmpty()) {
            List<ShopEntry> entries = new ArrayList<>();
            for (int i = 0; i < itemList.size(); i++) {
                entries.add(entryFromMap(shopId, "item_" + i, itemList.get(i)));
            }
            return entries;
        }
        return List.of(entryFromSection(shopId, "default", shop));
    }

    private ShopEntry entryFromSection(String shopId, String id, ConfigurationSection section) {
        return new ShopEntry(
                shopId,
                id,
                section.getString("type", "battle").toLowerCase(Locale.ROOT),
                section.getString("category", ""),
                section.getString("item", ""),
                Math.max(1, section.getInt("amount", 1)),
                section.getDouble("price", 0.0),
                section.getBoolean("free-once", false),
                section.getString("message", ""),
                section.getString("name", "")
        );
    }

    private ShopEntry entryFromMap(String shopId, String id, Map<?, ?> map) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            values.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return new ShopEntry(
                shopId,
                id,
                string(values, "type", "battle").toLowerCase(Locale.ROOT),
                string(values, "category", ""),
                string(values, "item", ""),
                Math.max(1, integer(values, "amount", 1)),
                decimal(values, "price", 0.0),
                bool(values, "free-once", false),
                string(values, "message", ""),
                string(values, "name", "")
        );
    }

    private String string(Map<String, Object> values, String key, String fallback) {
        Object value = values.get(key);
        return value == null ? fallback : value.toString();
    }

    private int integer(Map<String, Object> values, String key, int fallback) {
        Object value = values.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return value == null ? fallback : Integer.parseInt(value.toString());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private double decimal(Map<String, Object> values, String key, double fallback) {
        Object value = values.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? fallback : Double.parseDouble(value.toString());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private boolean bool(Map<String, Object> values, String key, boolean fallback) {
        Object value = values.get(key);
        return value == null ? fallback : Boolean.parseBoolean(value.toString());
    }

    private record ShopEntry(
            String shopId,
            String id,
            String type,
            String category,
            String item,
            int amount,
            double price,
            boolean freeOnce,
            String message,
            String name
    ) {
        private String rewardKey() {
            if (id.equals("default")) {
                return "shop:" + shopId;
            }
            return "shop:" + shopId + ":" + id;
        }
    }

    private static final class ShopInventoryHolder implements InventoryHolder {
        private final String shopId;
        private final int page;
        private Inventory inventory;

        private ShopInventoryHolder(String shopId, int page) {
            this.shopId = shopId;
            this.page = page;
        }

        private void inventory(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
