package me.nighter.smartSpawner.utils;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.HashMap;
import java.util.Map;

public class Hidespawner implements Listener {

    private final JavaPlugin plugin;
    private final int hideDistance = 16;
    private final Map<Player, Map<Location, Boolean>> spawnerCache = new HashMap<>();

    public Hidespawner(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public void register() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        Location playerLoc = player.getLocation();

        spawnerCache.putIfAbsent(player, new HashMap<>());
        Map<Location, Boolean> playerCache = spawnerCache.get(player);

        int radius = hideDistance + 5;

        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    Location checkLoc = playerLoc.clone().add(x, y, z);
                    Block block = checkLoc.getBlock();

                    if (block.getType() == Material.SPAWNER) {
                        double distance = playerLoc.distance(checkLoc);
                        boolean shouldBeVisible = distance <= hideDistance;

                        if (playerCache.getOrDefault(checkLoc, true) != shouldBeVisible) {
                            playerCache.put(checkLoc, shouldBeVisible);
                            player.sendBlockChange(checkLoc, shouldBeVisible ? Material.SPAWNER.createBlockData() : Material.AIR.createBlockData());
                        }
                    }
                }
            }
        }
    }
                              }
