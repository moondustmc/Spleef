package com.moondust.spleef.content;

import org.bukkit.Material;

public record BattleItemDefinition(
        String id,
        String name,
        Material material,
        String action,
        int radius,
        int projectileCount,
        int fuseSeconds,
        int speedAmplifier,
        int durationSeconds
) {
}
