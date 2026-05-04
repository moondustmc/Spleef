package com.moondust.spleef.util;

import org.bukkit.ChatColor;

public final class Chat {
    private Chat() {
    }

    public static String color(String value) {
        if (value == null) {
            return "";
        }
        return ChatColor.translateAlternateColorCodes('&', value);
    }

    public static String stripColor(String value) {
        return ChatColor.stripColor(color(value));
    }
}
