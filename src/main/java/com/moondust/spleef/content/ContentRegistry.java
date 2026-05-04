package com.moondust.spleef.content;

import com.moondust.spleef.player.PlayerData;
import com.moondust.spleef.util.Chat;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ContentRegistry {
    public static final String CATEGORY_SHOVELS = "shovels";
    public static final String CATEGORY_PLAYER_PARTICLES = "player_particles";
    public static final String CATEGORY_BLOCK_BREAK_PARTICLES = "block_break_particles";
    public static final String CATEGORY_GEAR = "gear";

    private final JavaPlugin plugin;
    private final NamespacedKey battleItemKey;
    private final NamespacedKey menuActionKey;
    private final NamespacedKey cosmeticCategoryKey;
    private final NamespacedKey cosmeticIdKey;
    private final Map<String, BattleItemDefinition> battleItems = new LinkedHashMap<>();
    private final Map<String, Map<String, CosmeticDefinition>> cosmetics = new LinkedHashMap<>();

    public ContentRegistry(JavaPlugin plugin) {
        this.plugin = plugin;
        this.battleItemKey = new NamespacedKey(plugin, "battle_item");
        this.menuActionKey = new NamespacedKey(plugin, "menu_action");
        this.cosmeticCategoryKey = new NamespacedKey(plugin, "cosmetic_category");
        this.cosmeticIdKey = new NamespacedKey(plugin, "cosmetic_id");
        reload();
    }

    public void reload() {
        battleItems.clear();
        cosmetics.clear();
        readBattleItems();
        readCosmetics();
    }

    private void readBattleItems() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("battle-items");
        if (section == null) {
            return;
        }
        for (String id : section.getKeys(false)) {
            ConfigurationSection itemSection = section.getConfigurationSection(id);
            if (itemSection == null) {
                continue;
            }
            BattleItemDefinition definition = new BattleItemDefinition(
                    id,
                    itemSection.getString("name", id),
                    material(itemSection.getString("material"), Material.SNOWBALL),
                    itemSection.getString("action", "SNOWBALL").toUpperCase(Locale.ROOT),
                    itemSection.getInt("radius", 0),
                    itemSection.getInt("projectile-count", 1),
                    itemSection.getInt("fuse-seconds", 3),
                    itemSection.getInt("speed-amplifier", 1),
                    itemSection.getInt("duration-seconds", 8)
            );
            battleItems.put(id, definition);
        }
    }

    private void readCosmetics() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("cosmetics");
        if (section == null) {
            return;
        }
        for (String category : section.getKeys(false)) {
            ConfigurationSection categorySection = section.getConfigurationSection(category);
            if (categorySection == null) {
                continue;
            }
            Map<String, CosmeticDefinition> definitions = new LinkedHashMap<>();
            for (String id : categorySection.getKeys(false)) {
                ConfigurationSection itemSection = categorySection.getConfigurationSection(id);
                if (itemSection == null) {
                    continue;
                }
                Material displayMaterial = material(itemSection.getString("material"), Material.BARRIER);
                EquipmentSlot slot = equipmentSlot(itemSection.getString("slot", ""));
                if (slot == null && CATEGORY_GEAR.equals(category)) {
                    slot = inferredGearSlot(displayMaterial);
                }
                CosmeticDefinition definition = new CosmeticDefinition(
                        category,
                        id,
                        itemSection.getString("name", id),
                        displayMaterial,
                        itemSection.getString("particle", ""),
                        material(itemSection.getString("particle-material"), null),
                        itemSection.getString("color", ""),
                        itemSection.getBoolean("enchanted", false),
                        slot,
                        itemSection.getBoolean("unlocked-by-default", false)
                );
                definitions.put(id, definition);
            }
            cosmetics.put(category, definitions);
        }
    }

    public NamespacedKey battleItemKey() {
        return battleItemKey;
    }

    public NamespacedKey menuActionKey() {
        return menuActionKey;
    }

    public NamespacedKey cosmeticCategoryKey() {
        return cosmeticCategoryKey;
    }

    public NamespacedKey cosmeticIdKey() {
        return cosmeticIdKey;
    }

    public Collection<BattleItemDefinition> battleItems() {
        return battleItems.values();
    }

    public BattleItemDefinition battleItem(String id) {
        return battleItems.get(id);
    }

    public Collection<String> cosmeticCategories() {
        return cosmetics.keySet();
    }

    public Map<String, CosmeticDefinition> cosmetics(String category) {
        return cosmetics.getOrDefault(category, Collections.emptyMap());
    }

    public CosmeticDefinition cosmetic(String category, String id) {
        return cosmetics.getOrDefault(category, Collections.emptyMap()).get(id);
    }

    public List<CosmeticDefinition> defaultCosmetics() {
        List<CosmeticDefinition> defaults = new ArrayList<>();
        for (Map<String, CosmeticDefinition> category : cosmetics.values()) {
            for (CosmeticDefinition definition : category.values()) {
                if (definition.unlockedByDefault()) {
                    defaults.add(definition);
                }
            }
        }
        return defaults;
    }

    public ItemStack battleItemStack(String id, int amount) {
        BattleItemDefinition definition = battleItem(id);
        if (definition == null) {
            return null;
        }
        ItemStack item = new ItemStack(definition.material(), Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Chat.color(definition.name()));
            meta.setLore(List.of(Chat.color("&7Battle Item"), Chat.color("&8" + id)));
            meta.getPersistentDataContainer().set(battleItemKey, PersistentDataType.STRING, id);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }

    public String battleItemId(ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return "";
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return "";
        }
        return meta.getPersistentDataContainer().getOrDefault(battleItemKey, PersistentDataType.STRING, "");
    }

    public boolean isSpleefShovel(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        if (item.hasItemMeta()) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null
                    && CATEGORY_SHOVELS.equals(meta.getPersistentDataContainer().getOrDefault(cosmeticCategoryKey, PersistentDataType.STRING, ""))
                    && !meta.getPersistentDataContainer().getOrDefault(cosmeticIdKey, PersistentDataType.STRING, "").isBlank()) {
                return true;
            }
        }
        Material material = item.getType();
        if (material.name().endsWith("_SHOVEL")) {
            return true;
        }
        for (CosmeticDefinition definition : cosmetics(CATEGORY_SHOVELS).values()) {
            if (definition.material() == material) {
                return true;
            }
        }
        return false;
    }

    public ItemStack cosmeticItem(CosmeticDefinition definition, boolean owned, boolean equipped) {
        ItemStack item = owned ? cosmeticStack(definition) : new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Chat.color(definition.name()));
            List<String> lore = new ArrayList<>();
            lore.add(owned ? Chat.color("&aOwned") : Chat.color("&cLocked"));
            if (equipped) {
                lore.add(Chat.color("&bEquipped"));
            }
            if (owned && (equipped || definition.enchanted())) {
                meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
            meta.getPersistentDataContainer().set(cosmeticCategoryKey, PersistentDataType.STRING, definition.category());
            meta.getPersistentDataContainer().set(cosmeticIdKey, PersistentDataType.STRING, definition.id());
            meta.setLore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }

    public ItemStack shovelFor(PlayerData data) {
        String id = data.equippedCosmetic(CATEGORY_SHOVELS);
        CosmeticDefinition definition = cosmetic(CATEGORY_SHOVELS, id);
        if (definition == null) {
            definition = firstCosmetic(CATEGORY_SHOVELS);
        }
        Material material = definition == null ? Material.WOODEN_SHOVEL : definition.material();
        if (!material.name().endsWith("_SHOVEL")) {
            material = Material.WOODEN_SHOVEL;
        }
        ItemStack shovel = definition == null || !definition.material().name().endsWith("_SHOVEL") ? new ItemStack(material) : cosmeticStack(definition);
        ItemMeta meta = shovel.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Chat.color(definition == null ? "&fSpleef Shovel" : definition.name()));
            meta.setUnbreakable(true);
            if (definition != null) {
                meta.getPersistentDataContainer().set(cosmeticCategoryKey, PersistentDataType.STRING, definition.category());
                meta.getPersistentDataContainer().set(cosmeticIdKey, PersistentDataType.STRING, definition.id());
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_UNBREAKABLE);
            shovel.setItemMeta(meta);
        }
        return shovel;
    }

    public ItemStack cosmeticStack(CosmeticDefinition definition) {
        ItemStack item = new ItemStack(definition.material());
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Chat.color(definition.name()));
            applyLeatherColor(meta, definition.color());
            if (definition.enchanted()) {
                meta.addEnchant(Enchantment.UNBREAKING, 1, true);
                meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }

    public Map<EquipmentSlot, ItemStack> gearFor(PlayerData data) {
        Map<EquipmentSlot, ItemStack> items = new LinkedHashMap<>();
        for (CosmeticDefinition definition : cosmetics(CATEGORY_GEAR).values()) {
            if (definition.equipmentSlot() != null && data.equippedCosmetic(equippedKey(definition)).equals(definition.id())) {
                items.put(definition.equipmentSlot(), cosmeticEquipment(definition));
            }
        }
        return items;
    }

    public Particle particle(String category, String id) {
        ParticleSpec spec = particleSpec(category, id);
        return spec == null ? null : spec.particle();
    }

    public ParticleSpec particleSpec(String category, String id) {
        CosmeticDefinition definition = cosmetic(category, id);
        if (definition == null || definition.particle() == null || definition.particle().isBlank()) {
            return null;
        }
        try {
            Particle particle = Particle.valueOf(definition.particle().toUpperCase(Locale.ROOT));
            return new ParticleSpec(particle, particleData(definition, particle));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    public String cosmeticDisplayName(String category, String id) {
        CosmeticDefinition definition = cosmetic(category, id);
        return definition == null ? id : Chat.color(definition.name());
    }

    public String battleItemDisplayName(String id) {
        BattleItemDefinition definition = battleItem(id);
        return definition == null ? id : Chat.color(definition.name());
    }

    public String categoryTitle(String category) {
        return switch (category) {
            case CATEGORY_SHOVELS -> "Shovels";
            case CATEGORY_PLAYER_PARTICLES -> "Particles";
            case CATEGORY_BLOCK_BREAK_PARTICLES -> "Block-break Particles";
            case CATEGORY_GEAR -> "Wearable Gear";
            default -> category;
        };
    }

    public String equippedKey(CosmeticDefinition definition) {
        if (definition != null && CATEGORY_GEAR.equals(definition.category()) && definition.equipmentSlot() != null) {
            return definition.category() + "." + definition.equipmentSlot().name().toLowerCase(Locale.ROOT);
        }
        return definition == null ? "" : definition.category();
    }

    public boolean isEquipped(PlayerData data, CosmeticDefinition definition) {
        return definition != null && data.equippedCosmetic(equippedKey(definition)).equals(definition.id());
    }

    public ItemStack menuAction(String action, Material material, String name) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Chat.color(name));
            meta.getPersistentDataContainer().set(menuActionKey, PersistentDataType.STRING, action);
            item.setItemMeta(meta);
        }
        return item;
    }

    public ItemStack filler(Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            meta.getPersistentDataContainer().set(menuActionKey, PersistentDataType.STRING, "filler");
            item.setItemMeta(meta);
        }
        return item;
    }

    public Material material(String value, Material fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        Material material = Material.matchMaterial(value);
        return material == null ? fallback : material;
    }

    private CosmeticDefinition firstCosmetic(String category) {
        return cosmetics(category).values().stream().findFirst().orElse(null);
    }

    private ItemStack cosmeticEquipment(CosmeticDefinition definition) {
        ItemStack item = cosmeticStack(definition);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Chat.color(definition.name()));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            item.setItemMeta(meta);
        }
        return item;
    }

    private Object particleData(CosmeticDefinition definition, Particle particle) {
        if (particle.getDataType() == ItemStack.class) {
            Material material = definition.particleMaterial() == null ? definition.material() : definition.particleMaterial();
            return new ItemStack(material);
        }
        if (particle.getDataType() == Particle.DustOptions.class) {
            Color color = color(definition.color());
            return new Particle.DustOptions(color == null ? Color.RED : color, 1.0F);
        }
        return null;
    }

    private void applyLeatherColor(ItemMeta meta, String value) {
        if (!(meta instanceof LeatherArmorMeta leatherMeta)) {
            return;
        }
        Color color = color(value);
        if (color != null) {
            leatherMeta.setColor(color);
        }
    }

    private Color color(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "red" -> Color.RED;
            case "blue" -> Color.BLUE;
            case "dark_blue", "dark-blue", "navy" -> Color.fromRGB(0, 0, 139);
            case "green" -> Color.GREEN;
            case "emerald" -> Color.fromRGB(80, 200, 120);
            default -> colorFromHexOrRgb(normalized);
        };
    }

    private Color colorFromHexOrRgb(String value) {
        try {
            if (value.startsWith("#")) {
                return Color.fromRGB(Integer.parseInt(value.substring(1), 16));
            }
            String[] parts = value.split(",");
            if (parts.length == 3) {
                return Color.fromRGB(
                        Math.max(0, Math.min(255, Integer.parseInt(parts[0].trim()))),
                        Math.max(0, Math.min(255, Integer.parseInt(parts[1].trim()))),
                        Math.max(0, Math.min(255, Integer.parseInt(parts[2].trim())))
                );
            }
        } catch (IllegalArgumentException ignored) {
        }
        return null;
    }

    private EquipmentSlot equipmentSlot(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return EquipmentSlot.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private EquipmentSlot inferredGearSlot(Material material) {
        String name = material.name();
        if (name.endsWith("_HELMET") || name.endsWith("_HEAD") || name.endsWith("_SKULL") || material.isBlock()) {
            return EquipmentSlot.HEAD;
        }
        if (name.endsWith("_CHESTPLATE") || name.equals("ELYTRA")) {
            return EquipmentSlot.CHEST;
        }
        if (name.endsWith("_LEGGINGS")) {
            return EquipmentSlot.LEGS;
        }
        if (name.endsWith("_BOOTS")) {
            return EquipmentSlot.FEET;
        }
        return null;
    }
}
