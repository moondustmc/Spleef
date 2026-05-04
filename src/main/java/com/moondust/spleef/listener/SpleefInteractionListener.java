package com.moondust.spleef.listener;

import com.moondust.spleef.Spleef;
import com.moondust.spleef.content.ContentRegistry;
import com.moondust.spleef.game.GameManager;
import com.moondust.spleef.leaderboard.LeaderboardService;
import com.moondust.spleef.menu.MenuManager;
import com.moondust.spleef.player.PlayerDataManager;
import com.moondust.spleef.shop.NpcShopService;
import com.moondust.spleef.shop.ShopService;
import com.moondust.spleef.util.Chat;
import com.moondust.spleef.util.LocationCodec;
import org.bukkit.Location;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class SpleefInteractionListener implements Listener {
    private final Spleef plugin;
    private final GameManager gameManager;
    private final MenuManager menuManager;
    private final LeaderboardService leaderboardService;
    private final PlayerDataManager dataManager;
    private final ContentRegistry registry;
    private final ShopService shopService;
    private final NpcShopService npcShopService;

    public SpleefInteractionListener(Spleef plugin, GameManager gameManager, MenuManager menuManager,
                                     LeaderboardService leaderboardService, PlayerDataManager dataManager, ContentRegistry registry,
                                     ShopService shopService, NpcShopService npcShopService) {
        this.plugin = plugin;
        this.gameManager = gameManager;
        this.menuManager = menuManager;
        this.leaderboardService = leaderboardService;
        this.dataManager = dataManager;
        this.registry = registry;
        this.shopService = shopService;
        this.npcShopService = npcShopService;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        String firstLine = normalize(event.getLine(0));
        if (!firstLine.startsWith("[spleef")) {
            return;
        }
        if (!event.getPlayer().hasPermission("spleef.admin")) {
            plugin.message(event.getPlayer(), "messages.no-permission");
            return;
        }
        Location location = event.getBlock().getLocation();
        switch (firstLine) {
            case "[spleef]" -> {
                event.setLine(0, Chat.color("&b[Spleef]"));
                event.setLine(1, Chat.color("&fQueue"));
                event.setLine(2, Chat.color("&aRight Click"));
                event.setLine(3, "");
                addUnique("signs.join", LocationCodec.compact(location));
            }
            case "[spleeftop]" -> {
                int rank = parseRank(event.getLine(1));
                leaderboardService.addSign(location, rank);
                event.setLine(0, Chat.color("&b[SpleefTop]"));
                event.setLine(1, Chat.color("&f#" + rank));
                event.setLine(2, Chat.color("&7Loading..."));
                event.setLine(3, "");
            }
            case "[spleefshop]" -> {
                String shopId = Chat.stripColor(event.getLine(1)).trim();
                event.setLine(0, Chat.color("&b[SpleefShop]"));
                event.setLine(1, shopId);
                event.setLine(2, Chat.color("&aRight Click"));
                event.setLine(3, "");
                addUnique("signs.shops", LocationCodec.compact(location));
            }
            default -> {
            }
        }
        plugin.saveConfig();
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null || !(event.getClickedBlock().getState() instanceof Sign sign)) {
            return;
        }
        String first = normalize(sign.getLine(0));
        if (first.equals("[spleef]")) {
            event.setCancelled(true);
            gameManager.joinQueue(event.getPlayer());
        } else if (first.equals("[spleefshop]")) {
            event.setCancelled(true);
            shopService.openShop(event.getPlayer(), Chat.stripColor(sign.getLine(1)).trim());
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        if (npcShopService != null && npcShopService.handlesEntity(event.getRightClicked())) {
            return;
        }
        for (String tag : event.getRightClicked().getScoreboardTags()) {
            if (tag.startsWith("spleef-shop:")) {
                event.setCancelled(true);
                shopService.openShop(event.getPlayer(), tag.substring("spleef-shop:".length()));
                return;
            }
        }
    }

    private void addUnique(String path, String value) {
        List<String> values = new ArrayList<>(plugin.getConfig().getStringList(path));
        if (!values.contains(value)) {
            values.add(value);
            plugin.getConfig().set(path, values);
        }
    }

    private int parseRank(String value) {
        try {
            return Math.max(1, Integer.parseInt(Chat.stripColor(value).replace("#", "").trim()));
        } catch (NumberFormatException ignored) {
            return 1;
        }
    }

    private String normalize(String value) {
        return Chat.stripColor(value).trim().toLowerCase(Locale.ROOT);
    }
}
