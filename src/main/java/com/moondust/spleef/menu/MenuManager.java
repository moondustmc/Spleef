package com.moondust.spleef.menu;

import com.moondust.spleef.Spleef;
import com.moondust.spleef.content.BattleItemDefinition;
import com.moondust.spleef.content.ContentRegistry;
import com.moondust.spleef.content.CosmeticDefinition;
import com.moondust.spleef.game.GameManager;
import com.moondust.spleef.player.PlayerData;
import com.moondust.spleef.player.PlayerDataManager;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MenuManager implements Listener {
    private static final String COSMETICS_TITLE = "Cosmetics";
    private static final String BATTLE_TITLE = "BattleItems";
    private static final int[] COSMETIC_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
    private static final int[] COMPACT_CATEGORY_SLOTS = {12, 13, 14, 15, 16};
    private static final int EQUIPPED_COMPACT_SLOT = 10;
    private static final int COMPACT_NEXT_PAGE_SLOT = 26;
    private static final int[] GEAR_ITEM_SLOTS = {12, 13, 14, 15, 16, 21, 22, 23, 24, 25, 30, 31, 32, 33, 34, 39, 40, 41, 42, 43};
    private static final int GEAR_HELMET_SLOT = 10;
    private static final int GEAR_CHEST_SLOT = 19;
    private static final int GEAR_LEGS_SLOT = 28;
    private static final int GEAR_BOOTS_SLOT = 37;
    private static final int GEAR_NEXT_PAGE_SLOT = 52;
    private static final int[] BATTLE_OWNED_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
    private static final int BATTLE_SHOVEL_SLOT = 39;
    private static final int[] BATTLE_LOADOUT_SLOTS = {40, 41};

    private final Spleef plugin;
    private final PlayerDataManager dataManager;
    private final ContentRegistry registry;
    private final GameManager gameManager;
    private final Map<UUID, List<String>> battleEditors = new HashMap<>();
    private final Map<UUID, CategoryView> categoryViews = new HashMap<>();

    public MenuManager(Spleef plugin, PlayerDataManager dataManager, ContentRegistry registry, GameManager gameManager) {
        this.plugin = plugin;
        this.dataManager = dataManager;
        this.registry = registry;
        this.gameManager = gameManager;
    }

    public void openCosmetics(Player player) {
        if (gameManager.isActive(player)) {
            return;
        }
        Inventory inventory = Bukkit.createInventory(null, 54, COSMETICS_TITLE);
        decorateCosmeticsRoot(inventory);
        inventory.setItem(19, registry.menuAction("category:" + ContentRegistry.CATEGORY_GEAR, Material.LEATHER_HELMET, "&6Wearable Gear"));
        inventory.setItem(21, registry.menuAction("category:" + ContentRegistry.CATEGORY_SHOVELS, Material.DIAMOND_SHOVEL, "&bShovels"));
        inventory.setItem(23, registry.menuAction("category:" + ContentRegistry.CATEGORY_BLOCK_BREAK_PARTICLES, Material.SNOW_BLOCK, "&fBlock-break Particles"));
        inventory.setItem(25, registry.menuAction("category:" + ContentRegistry.CATEGORY_PLAYER_PARTICLES, Material.BLAZE_POWDER, "&aPlayer Particles"));
        player.openInventory(inventory);
    }

    public void openCategory(Player player, String category) {
        openCategory(player, category, 0);
    }

    public void openCategory(Player player, String category, int page) {
        if (gameManager.isActive(player)) {
            return;
        }
        if (isCompactCategory(category)) {
            openCompactCategory(player, category, page);
            return;
        }
        if (ContentRegistry.CATEGORY_GEAR.equals(category)) {
            openGearCategory(player, page);
            return;
        }
        List<CosmeticDefinition> definitions = new ArrayList<>(registry.cosmetics(category).values());
        int pageSize = COSMETIC_SLOTS.length;
        int pages = Math.max(1, (int) Math.ceil(definitions.size() / (double) pageSize));
        int actualPage = Math.floorMod(page, pages);
        Inventory inventory = Bukkit.createInventory(null, 54, titleFor(category, actualPage));
        decorate(inventory);
        PlayerData data = dataManager.get(player);
        int start = actualPage * pageSize;
        for (int i = 0; i < pageSize && start + i < definitions.size(); i++) {
            CosmeticDefinition definition = definitions.get(start + i);
            boolean owned = data.ownsCosmetic(definition.category(), definition.id());
            boolean equipped = registry.isEquipped(data, definition);
            inventory.setItem(COSMETIC_SLOTS[i], registry.cosmeticItem(definition, owned, equipped));
        }
        inventory.setItem(35, registry.menuAction("page:" + category + ":" + (actualPage + 1), Material.ARROW, "&eNext Page"));
        inventory.setItem(45, registry.menuAction("open_cosmetics", Material.BLAZE_ROD, "&eBack"));
        categoryViews.put(player.getUniqueId(), new CategoryView(category, actualPage));
        player.openInventory(inventory);
    }

    private void openGearCategory(Player player, int page) {
        PlayerData data = dataManager.get(player);
        List<CosmeticDefinition> definitions = registry.cosmetics(ContentRegistry.CATEGORY_GEAR).values().stream()
                .filter(definition -> data.ownsCosmetic(definition.category(), definition.id()))
                .toList();
        int pageSize = GEAR_ITEM_SLOTS.length;
        int pages = Math.max(1, (int) Math.ceil(definitions.size() / (double) pageSize));
        int actualPage = Math.floorMod(page, pages);
        Inventory inventory = Bukkit.createInventory(null, 54, titleFor(ContentRegistry.CATEGORY_GEAR, actualPage));
        decorateGear(inventory);
        setGearSelector(inventory, data, EquipmentSlot.HEAD, GEAR_HELMET_SLOT);
        setGearSelector(inventory, data, EquipmentSlot.CHEST, GEAR_CHEST_SLOT);
        setGearSelector(inventory, data, EquipmentSlot.LEGS, GEAR_LEGS_SLOT);
        setGearSelector(inventory, data, EquipmentSlot.FEET, GEAR_BOOTS_SLOT);
        int start = actualPage * pageSize;
        for (int i = 0; i < pageSize && start + i < definitions.size(); i++) {
            CosmeticDefinition definition = definitions.get(start + i);
            inventory.setItem(GEAR_ITEM_SLOTS[i], registry.cosmeticItem(definition, true, registry.isEquipped(data, definition)));
        }
        inventory.setItem(GEAR_NEXT_PAGE_SLOT, registry.menuAction("page:" + ContentRegistry.CATEGORY_GEAR + ":" + (actualPage + 1), Material.ARROW, "&eNext Page"));
        categoryViews.put(player.getUniqueId(), new CategoryView(ContentRegistry.CATEGORY_GEAR, actualPage));
        player.openInventory(inventory);
    }

    private void openCompactCategory(Player player, String category, int page) {
        List<CosmeticDefinition> definitions = new ArrayList<>(registry.cosmetics(category).values());
        int pageSize = COMPACT_CATEGORY_SLOTS.length;
        int pages = Math.max(1, (int) Math.ceil(definitions.size() / (double) pageSize));
        int actualPage = Math.floorMod(page, pages);
        Inventory inventory = Bukkit.createInventory(null, 27, titleFor(category, actualPage));
        decorateCompactCategory(inventory);
        PlayerData data = dataManager.get(player);
        CosmeticDefinition equipped = registry.cosmetic(category, data.equippedCosmetic(category));
        if (equipped != null) {
            inventory.setItem(EQUIPPED_COMPACT_SLOT, registry.cosmeticItem(equipped, true, true));
        }
        int start = actualPage * pageSize;
        for (int i = 0; i < pageSize && start + i < definitions.size(); i++) {
            CosmeticDefinition definition = definitions.get(start + i);
            boolean owned = data.ownsCosmetic(definition.category(), definition.id());
            boolean equippedInRow = registry.isEquipped(data, definition);
            int slot = COMPACT_CATEGORY_SLOTS[i];
            inventory.setItem(slot, registry.cosmeticItem(definition, owned, equippedInRow));
            if (equippedInRow) {
                inventory.setItem(slot - 9, registry.filler(Material.LIME_STAINED_GLASS));
                inventory.setItem(slot + 9, registry.filler(Material.LIME_STAINED_GLASS));
            }
        }
        inventory.setItem(COMPACT_NEXT_PAGE_SLOT, registry.menuAction("page:" + category + ":" + (actualPage + 1), Material.ARROW, "&eNext Page"));
        categoryViews.put(player.getUniqueId(), new CategoryView(category, actualPage));
        player.openInventory(inventory);
    }

    public void openBattleItems(Player player) {
        if (gameManager.isActive(player)) {
            return;
        }
        PlayerData data = dataManager.get(player);
        List<String> loadout = battleLoadoutForMenu(data);
        battleEditors.put(player.getUniqueId(), loadout);
        player.openInventory(battleInventory(player, loadout));
    }

    private Inventory battleInventory(Player player, List<String> loadout) {
        Inventory inventory = Bukkit.createInventory(null, 54, BATTLE_TITLE);
        decorateBattleItems(inventory);
        PlayerData data = dataManager.get(player);
        int slotIndex = 0;
        for (BattleItemDefinition definition : registry.battleItems()) {
            int count = data.battleItemCount(definition.id());
            if (count <= 0 || slotIndex >= BATTLE_OWNED_SLOTS.length) {
                continue;
            }
            inventory.setItem(BATTLE_OWNED_SLOTS[slotIndex], registry.battleItemStack(definition.id(), Math.min(64, count)));
            slotIndex++;
        }
        inventory.setItem(BATTLE_SHOVEL_SLOT, registry.shovelFor(data));
        for (int i = 0; i < BATTLE_LOADOUT_SLOTS.length; i++) {
            String id = loadout.get(i);
            if (id != null && !id.isBlank() && data.battleItemCount(id) > 0) {
                inventory.setItem(BATTLE_LOADOUT_SLOTS[i], registry.battleItemStack(id, Math.min(64, data.battleItemCount(id))));
            } else {
                inventory.setItem(BATTLE_LOADOUT_SLOTS[i], registry.filler(Material.GRAY_STAINED_GLASS_PANE));
            }
        }
        return inventory;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        String action = menuAction(event.getItem());
        if (!action.equals("open_cosmetics")) {
            return;
        }
        event.setCancelled(true);
        openCosmetics(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        String title = event.getView().getTitle();
        String action = menuAction(event.getCurrentItem());
        if (action.equals("open_cosmetics") && event.getClickedInventory() == player.getInventory()) {
            event.setCancelled(true);
            openCosmetics(player);
            return;
        }
        if (!isManagedTitle(title)) {
            return;
        }
        event.setCancelled(true);
        if (event.getClickedInventory() == null || event.getClickedInventory() == player.getInventory()) {
            return;
        }
        if (BATTLE_TITLE.equals(title)) {
            handleBattleClick(player, event);
            return;
        }
        if (COSMETICS_TITLE.equals(title)) {
            handleRootClick(player, action);
            return;
        }
        if (isGearTitle(title)) {
            handleGearClick(player, event);
            return;
        }
        handleCategoryClick(player, event.getCurrentItem(), action);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (BATTLE_TITLE.equals(event.getView().getTitle())) {
            saveBattleEditor(player);
        }
        clearPluginCursor(player);
        categoryViews.remove(player.getUniqueId());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        String title = event.getView().getTitle();
        if (!isManagedTitle(title)) {
            return;
        }
        event.setCancelled(true);
        if (BATTLE_TITLE.equals(title) && event.getWhoClicked() instanceof Player player) {
            for (int rawSlot : event.getRawSlots()) {
                if (battleLoadoutIndex(rawSlot) >= 0) {
                    equipBattleItemFromCursor(player, event.getOldCursor(), battleLoadoutIndex(rawSlot));
                    clearPluginCursor(player);
                    return;
                }
                if (isBattleOwnedSlot(rawSlot) && !registry.battleItemId(event.getOldCursor()).isBlank()) {
                    clearPluginCursor(player);
                    return;
                }
            }
            return;
        }
        if (isGearTitle(title) && event.getWhoClicked() instanceof Player player) {
            for (int rawSlot : event.getRawSlots()) {
                EquipmentSlot target = gearSlot(rawSlot);
                if (target != null) {
                    equipGearFromItem(player, event.getOldCursor(), target);
                    clearPluginCursor(player);
                    return;
                }
            }
        }
    }

    private void handleRootClick(Player player, String action) {
        if (action.startsWith("category:")) {
            openCategory(player, action.substring("category:".length()), 0);
        }
    }

    private void handleCategoryClick(Player player, ItemStack item, String action) {
        if (action.equals("open_cosmetics")) {
            openCosmetics(player);
            return;
        }
        if (action.startsWith("page:")) {
            String[] parts = action.split(":");
            if (parts.length >= 3) {
                try {
                    openCategory(player, parts[1], Integer.parseInt(parts[2]));
                } catch (NumberFormatException ignored) {
                    openCategory(player, parts[1], 0);
                }
            }
            return;
        }
        if (item == null || !item.hasItemMeta()) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        String category = meta.getPersistentDataContainer().getOrDefault(registry.cosmeticCategoryKey(), PersistentDataType.STRING, "");
        String id = meta.getPersistentDataContainer().getOrDefault(registry.cosmeticIdKey(), PersistentDataType.STRING, "");
        if (category.isBlank() || id.isBlank()) {
            return;
        }
        PlayerData data = dataManager.get(player);
        CosmeticDefinition definition = registry.cosmetic(category, id);
        if (definition == null) {
            return;
        }
        if (!data.ownsCosmetic(category, id)) {
            plugin.message(player, "messages.cosmetic-locked");
            return;
        }
        data.equipCosmetic(registry.equippedKey(definition), id);
        dataManager.save();
        plugin.message(player, "messages.cosmetic-equipped", Map.of("{cosmetic}", registry.cosmeticDisplayName(category, id)));
        CategoryView view = categoryViews.getOrDefault(player.getUniqueId(), new CategoryView(category, 0));
        openCategory(player, category, view.page());
    }

    private void handleGearClick(Player player, InventoryClickEvent event) {
        int rawSlot = event.getRawSlot();
        if (rawSlot >= event.getView().getTopInventory().getSize()) {
            clearPluginCursor(player);
            return;
        }
        String action = menuAction(event.getCurrentItem());
        if (action.startsWith("page:")) {
            String[] parts = action.split(":");
            if (parts.length >= 3) {
                try {
                    openCategory(player, parts[1], Integer.parseInt(parts[2]));
                } catch (NumberFormatException ignored) {
                    openCategory(player, parts[1], 0);
                }
            }
            return;
        }
        EquipmentSlot targetSlot = gearSlot(rawSlot);
        if (targetSlot != null) {
            String cursorCategory = cosmeticCategory(event.getCursor());
            String cursorId = cosmeticId(event.getCursor());
            if (ContentRegistry.CATEGORY_GEAR.equals(cursorCategory) && !cursorId.isBlank()) {
                if (equipGearFromItem(player, event.getCursor(), targetSlot)) {
                    clearPluginCursor(player);
                }
                return;
            }
            String currentCategory = cosmeticCategory(event.getCurrentItem());
            String currentId = cosmeticId(event.getCurrentItem());
            if (ContentRegistry.CATEGORY_GEAR.equals(currentCategory) && !currentId.isBlank()) {
                dataManager.get(player).equippedCosmetics().remove(gearKey(targetSlot));
                dataManager.save();
                CategoryView view = categoryViews.getOrDefault(player.getUniqueId(), new CategoryView(ContentRegistry.CATEGORY_GEAR, 0));
                openGearCategory(player, view.page());
            }
            return;
        }
        String category = cosmeticCategory(event.getCurrentItem());
        String id = cosmeticId(event.getCurrentItem());
        if (!ContentRegistry.CATEGORY_GEAR.equals(category) || id.isBlank()) {
            return;
        }
        CosmeticDefinition definition = registry.cosmetic(category, id);
        PlayerData data = dataManager.get(player);
        if (definition == null || !data.ownsCosmetic(category, id)) {
            return;
        }
        player.setItemOnCursor(registry.cosmeticItem(definition, true, false));
    }

    private boolean equipGearFromItem(Player player, ItemStack item, EquipmentSlot targetSlot) {
        String category = cosmeticCategory(item);
        String id = cosmeticId(item);
        if (!ContentRegistry.CATEGORY_GEAR.equals(category) || id.isBlank()) {
            return false;
        }
        CosmeticDefinition definition = registry.cosmetic(category, id);
        PlayerData data = dataManager.get(player);
        if (definition == null || !data.ownsCosmetic(category, id)) {
            return false;
        }
        if (definition.equipmentSlot() != targetSlot) {
            plugin.messageText(player, "&cThat item cannot go in that armor slot.", Map.of());
            return false;
        }
        data.equipCosmetic(registry.equippedKey(definition), id);
        dataManager.save();
        plugin.message(player, "messages.cosmetic-equipped", Map.of("{cosmetic}", registry.cosmeticDisplayName(category, id)));
        CategoryView view = categoryViews.getOrDefault(player.getUniqueId(), new CategoryView(ContentRegistry.CATEGORY_GEAR, 0));
        openGearCategory(player, view.page());
        return true;
    }

    private void handleBattleClick(Player player, InventoryClickEvent event) {
        int rawSlot = event.getRawSlot();
        if (rawSlot >= event.getView().getTopInventory().getSize()) {
            clearPluginCursor(player);
            return;
        }
        if (rawSlot == BATTLE_SHOVEL_SLOT) {
            clearPluginCursor(player);
            return;
        }
        int loadoutIndex = battleLoadoutIndex(rawSlot);
        if (loadoutIndex >= 0) {
            String cursorId = registry.battleItemId(event.getCursor());
            if (!cursorId.isBlank()) {
                equipBattleItemFromCursor(player, event.getCursor(), loadoutIndex);
                clearPluginCursor(player);
                return;
            }
            List<String> loadout = editorLoadout(player);
            if (!loadout.get(loadoutIndex).isBlank()) {
                ItemStack current = event.getCurrentItem();
                loadout.set(loadoutIndex, "");
                saveBattleEditorValues(player, loadout);
                player.setItemOnCursor(current == null ? null : current.clone());
                event.getView().getTopInventory().setItem(rawSlot, registry.filler(Material.GRAY_STAINED_GLASS_PANE));
            }
            return;
        }
        String cursorId = registry.battleItemId(event.getCursor());
        if (isBattleOwnedSlot(rawSlot) && !cursorId.isBlank()) {
            clearPluginCursor(player);
            return;
        }
        String id = registry.battleItemId(event.getCurrentItem());
        if (id.isBlank()) {
            return;
        }
        PlayerData data = dataManager.get(player);
        if (data.battleItemCount(id) <= 0) {
            return;
        }
        player.setItemOnCursor(registry.battleItemStack(id, Math.min(64, data.battleItemCount(id))));
    }

    private void saveBattleEditor(Player player) {
        List<String> loadout = battleEditors.remove(player.getUniqueId());
        if (loadout == null) {
            return;
        }
        saveBattleEditorValues(player, loadout);
    }

    private void saveBattleEditorValues(Player player, List<String> loadout) {
        PlayerData data = dataManager.get(player);
        for (int i = 0; i < data.battleLoadout().size(); i++) {
            data.battleLoadout().set(i, "");
        }
        for (int i = 0; i < BATTLE_LOADOUT_SLOTS.length; i++) {
            data.battleLoadout().set(i, i < loadout.size() && loadout.get(i) != null ? loadout.get(i) : "");
        }
        dataManager.save();
    }

    private List<String> editorLoadout(Player player) {
        return battleEditors.computeIfAbsent(player.getUniqueId(), uuid -> battleLoadoutForMenu(dataManager.get(player)));
    }

    private List<String> battleLoadoutForMenu(PlayerData data) {
        List<String> loadout = new ArrayList<>();
        for (String id : data.battleLoadout()) {
            if (id == null || id.isBlank() || data.battleItemCount(id) <= 0 || registry.battleItem(id) == null || loadout.contains(id)) {
                continue;
            }
            loadout.add(id);
            if (loadout.size() == BATTLE_LOADOUT_SLOTS.length) {
                break;
            }
        }
        while (loadout.size() < BATTLE_LOADOUT_SLOTS.length) {
            loadout.add("");
        }
        return loadout;
    }

    private boolean equipBattleItemFromCursor(Player player, ItemStack item, int loadoutIndex) {
        String id = registry.battleItemId(item);
        if (id.isBlank()) {
            return false;
        }
        PlayerData data = dataManager.get(player);
        if (data.battleItemCount(id) <= 0 || registry.battleItem(id) == null) {
            return false;
        }
        List<String> loadout = editorLoadout(player);
        for (int i = 0; i < loadout.size(); i++) {
            if (i != loadoutIndex && id.equals(loadout.get(i))) {
                loadout.set(i, "");
            }
        }
        loadout.set(loadoutIndex, id);
        saveBattleEditorValues(player, loadout);
        player.openInventory(battleInventory(player, loadout));
        return true;
    }

    private boolean isManagedTitle(String title) {
        if (COSMETICS_TITLE.equals(title) || BATTLE_TITLE.equals(title)) {
            return true;
        }
        for (String category : registry.cosmeticCategories()) {
            if (title.startsWith(registry.categoryTitle(category))) {
                return true;
            }
        }
        return false;
    }

    private String titleFor(String category, int page) {
        if (page <= 0) {
            return registry.categoryTitle(category);
        }
        return registry.categoryTitle(category) + " Page " + (page + 1);
    }

    private void decorate(Inventory inventory) {
        ItemStack blue = registry.filler(Material.LIGHT_BLUE_STAINED_GLASS_PANE);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            int row = slot / 9;
            int column = slot % 9;
            if (row == 0 || row == 2 || row == 5 || column == 0 || column == 8) {
                inventory.setItem(slot, blue);
            }
        }
    }

    private void decorateBattleItems(Inventory inventory) {
        ItemStack blue = registry.filler(Material.LIGHT_BLUE_STAINED_GLASS_PANE);
        ItemStack gray = registry.filler(Material.GRAY_STAINED_GLASS_PANE);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, blue);
        }
        for (int slot : BATTLE_OWNED_SLOTS) {
            inventory.setItem(slot, gray);
        }
    }

    private void decorateCosmeticsRoot(Inventory inventory) {
        ItemStack blue = registry.filler(Material.LIGHT_BLUE_STAINED_GLASS_PANE);
        ItemStack whitePane = registry.filler(Material.WHITE_STAINED_GLASS_PANE);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            int row = slot / 9;
            if (row == 1 || row == 2 || row == 3) {
                inventory.setItem(slot, whitePane);
            } else {
                inventory.setItem(slot, blue);
            }
        }
        for (int slot : List.of(18, 20, 22, 24, 26)) {
            inventory.setItem(slot, whitePane);
        }
    }

    private void decorateCompactCategory(Inventory inventory) {
        ItemStack blue = registry.filler(Material.LIGHT_BLUE_STAINED_GLASS_PANE);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, blue);
        }
    }

    private void decorateGear(Inventory inventory) {
        ItemStack blue = registry.filler(Material.LIGHT_BLUE_STAINED_GLASS_PANE);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            int row = slot / 9;
            int column = slot % 9;
            if (row == 0 || row == 5 || column == 0 || column == 2 || column == 8) {
                inventory.setItem(slot, blue);
            }
        }
    }

    private void setGearSelector(Inventory inventory, PlayerData data, EquipmentSlot slot, int inventorySlot) {
        CosmeticDefinition equipped = registry.cosmetic(ContentRegistry.CATEGORY_GEAR, data.equippedCosmetic(gearKey(slot)));
        if (equipped != null && equipped.equipmentSlot() == slot) {
            inventory.setItem(inventorySlot, registry.cosmeticItem(equipped, true, true));
            return;
        }
        inventory.setItem(inventorySlot, gearPlaceholder(slot));
    }

    private ItemStack gearPlaceholder(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> registry.menuAction("gear_slot:HEAD", Material.BARRIER, "&cHelmet Slot");
            case CHEST -> registry.menuAction("gear_slot:CHEST", Material.BARRIER, "&cChestplate Slot");
            case LEGS -> registry.menuAction("gear_slot:LEGS", Material.BARRIER, "&cPants Slot");
            case FEET -> registry.menuAction("gear_slot:FEET", Material.BARRIER, "&cBoots Slot");
            default -> registry.menuAction("gear_slot", Material.BARRIER, "&cArmor Slot");
        };
    }

    private boolean isCompactCategory(String category) {
        return ContentRegistry.CATEGORY_SHOVELS.equals(category)
                || ContentRegistry.CATEGORY_BLOCK_BREAK_PARTICLES.equals(category)
                || ContentRegistry.CATEGORY_PLAYER_PARTICLES.equals(category);
    }

    private boolean isGearTitle(String title) {
        return title.startsWith(registry.categoryTitle(ContentRegistry.CATEGORY_GEAR));
    }

    private int battleLoadoutIndex(int rawSlot) {
        for (int i = 0; i < BATTLE_LOADOUT_SLOTS.length; i++) {
            if (BATTLE_LOADOUT_SLOTS[i] == rawSlot) {
                return i;
            }
        }
        return -1;
    }

    private boolean isBattleOwnedSlot(int rawSlot) {
        for (int slot : BATTLE_OWNED_SLOTS) {
            if (slot == rawSlot) {
                return true;
            }
        }
        return false;
    }

    private EquipmentSlot gearSlot(int rawSlot) {
        return switch (rawSlot) {
            case GEAR_HELMET_SLOT -> EquipmentSlot.HEAD;
            case GEAR_CHEST_SLOT -> EquipmentSlot.CHEST;
            case GEAR_LEGS_SLOT -> EquipmentSlot.LEGS;
            case GEAR_BOOTS_SLOT -> EquipmentSlot.FEET;
            default -> null;
        };
    }

    private String gearKey(EquipmentSlot slot) {
        return ContentRegistry.CATEGORY_GEAR + "." + slot.name().toLowerCase();
    }

    private String cosmeticCategory(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return "";
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return "";
        }
        return meta.getPersistentDataContainer().getOrDefault(registry.cosmeticCategoryKey(), PersistentDataType.STRING, "");
    }

    private String cosmeticId(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return "";
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return "";
        }
        return meta.getPersistentDataContainer().getOrDefault(registry.cosmeticIdKey(), PersistentDataType.STRING, "");
    }

    private void clearPluginCursor(Player player) {
        if (!cosmeticCategory(player.getItemOnCursor()).isBlank()) {
            player.setItemOnCursor(null);
            return;
        }
        if (!registry.battleItemId(player.getItemOnCursor()).isBlank()) {
            player.setItemOnCursor(null);
        }
    }

    private String menuAction(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return "";
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return "";
        }
        return meta.getPersistentDataContainer().getOrDefault(registry.menuActionKey(), PersistentDataType.STRING, "");
    }

    private record CategoryView(String category, int page) {
    }
}
