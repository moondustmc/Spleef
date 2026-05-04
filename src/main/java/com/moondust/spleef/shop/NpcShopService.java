package com.moondust.spleef.shop;

import org.bukkit.entity.Player;
import org.bukkit.entity.Entity;

public interface NpcShopService {
    boolean available();

    boolean handlesEntity(Entity entity);

    boolean bindShop(Player player, String shopId);

    boolean clearShop(Player player);

    void reload();
}
