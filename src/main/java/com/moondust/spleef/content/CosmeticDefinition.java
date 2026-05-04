package com.moondust.spleef.content;

import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;

public record CosmeticDefinition(
        String category,
        String id,
        String name,
        Material material,
        String particle,
        Material particleMaterial,
        String color,
        boolean enchanted,
        EquipmentSlot equipmentSlot,
        boolean unlockedByDefault
) {
}
