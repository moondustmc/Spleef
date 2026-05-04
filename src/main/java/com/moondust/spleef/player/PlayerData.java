package com.moondust.spleef.player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PlayerData {
    private final UUID uuid;
    private String name;
    private int wins;
    private int currentStreak;
    private double coins;
    private final Set<String> ownedCosmetics = new HashSet<>();
    private final Map<String, String> equippedCosmetics = new HashMap<>();
    private final Map<String, Integer> battleItems = new HashMap<>();
    private final List<String> battleLoadout = new ArrayList<>();
    private final Set<String> claimedRewards = new HashSet<>();

    public PlayerData(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
        while (battleLoadout.size() < 9) {
            battleLoadout.add("");
        }
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name == null ? uuid.toString() : name;
    }

    public void name(String name) {
        this.name = name;
    }

    public int wins() {
        return wins;
    }

    public void wins(int wins) {
        this.wins = Math.max(0, wins);
    }

    public int currentStreak() {
        return currentStreak;
    }

    public void currentStreak(int currentStreak) {
        this.currentStreak = Math.max(0, currentStreak);
    }

    public double coins() {
        return coins;
    }

    public void coins(double coins) {
        this.coins = Math.max(0.0, coins);
    }

    public Set<String> ownedCosmetics() {
        return ownedCosmetics;
    }

    public Map<String, String> equippedCosmetics() {
        return equippedCosmetics;
    }

    public Map<String, Integer> battleItems() {
        return battleItems;
    }

    public List<String> battleLoadout() {
        while (battleLoadout.size() < 9) {
            battleLoadout.add("");
        }
        return battleLoadout;
    }

    public Set<String> claimedRewards() {
        return claimedRewards;
    }

    public boolean ownsCosmetic(String category, String id) {
        return ownedCosmetics.contains(category + ":" + id);
    }

    public void unlockCosmetic(String category, String id) {
        ownedCosmetics.add(category + ":" + id);
    }

    public String equippedCosmetic(String category) {
        return equippedCosmetics.getOrDefault(category, "");
    }

    public void equipCosmetic(String category, String id) {
        equippedCosmetics.put(category, id);
    }

    public int battleItemCount(String id) {
        return Math.max(0, battleItems.getOrDefault(id, 0));
    }

    public void addBattleItem(String id, int amount) {
        if (amount <= 0) {
            return;
        }
        battleItems.put(id, battleItemCount(id) + amount);
    }

    public boolean consumeBattleItem(String id, int amount) {
        int current = battleItemCount(id);
        if (amount <= 0 || current < amount) {
            return false;
        }
        int next = current - amount;
        if (next <= 0) {
            battleItems.remove(id);
        } else {
            battleItems.put(id, next);
        }
        return true;
    }
}
