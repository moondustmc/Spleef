package com.moondust.spleef.content;

import org.bukkit.Material;

public record BattleItemDefinition(
        String id,
        String name,
        Material material,
        String action,
        int radius,
        int projectileCount,
        double fuseSeconds,
        int speedAmplifier,
        int durationSeconds,
        int maxStackSize,
        String skullTexture
) {
}
