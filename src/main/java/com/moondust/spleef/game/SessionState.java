package com.moondust.spleef.game;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

public record SessionState(
        ItemStack[] inventory,
        ItemStack[] armor,
        Location location,
        GameMode gameMode,
        boolean allowFlight,
        boolean flying,
        double health,
        int foodLevel,
        float saturation,
        int heldSlot
) {
}
