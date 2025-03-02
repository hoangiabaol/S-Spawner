package me.nighter.smartSpawner.spawner.interactions;

import me.nighter.smartSpawner.SmartSpawner;
import me.nighter.smartSpawner.hooks.protections.CheckOpenMenu;
import me.nighter.smartSpawner.nms.ParticleWrapper;
import me.nighter.smartSpawner.spawner.gui.main.SpawnerMenuUI;
import me.nighter.smartSpawner.spawner.interactions.stack.SpawnerStackHandler;
import me.nighter.smartSpawner.spawner.interactions.type.SpawnEggHandler;
import me.nighter.smartSpawner.spawner.properties.SpawnerData;
import me.nighter.smartSpawner.spawner.properties.SpawnerManager;
import me.nighter.smartSpawner.utils.ConfigManager;
import me.nighter.smartSpawner.utils.LanguageManager;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.CreatureSpawner;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.geysermc.floodgate.api.FloodgateApi;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages player interactions with spawner blocks
 * Handles right-click events on spawners for various actions:
 * - Opening spawner menu
 * - Changing spawner type with spawn eggs
 * - Stacking spawners
 */
public class SpawnerClickManager implements Listener {
    private static final long COOLDOWN_MS = 250;
    private static final long CLEANUP_INTERVAL_TICKS = 6000L; // 5 minutes

    private final SmartSpawner plugin;
    private final ConfigManager configManager;
    private final LanguageManager languageManager;
    private final SpawnerManager spawnerManager;
    private final SpawnEggHandler spawnEggHandler;
    private final SpawnerStackHandler spawnerStackHandler;
    private final SpawnerMenuUI spawnerMenuUI;

    // Use ConcurrentHashMap for thread safety without explicit synchronization
    private final Map<UUID, Long> playerCooldowns = new ConcurrentHashMap<>();

    public SpawnerClickManager(SmartSpawner plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.languageManager = plugin.getLanguageManager();
        this.spawnerManager = plugin.getSpawnerManager();
        this.spawnEggHandler = plugin.getSpawnEggHandler();
        this.spawnerStackHandler = plugin.getSpawnerStackHandler();
        this.spawnerMenuUI = plugin.getSpawnerMenuUI();
        initCleanupTask();
    }

    /**
     * Checks if a spawner is likely to be naturally generated based on surrounding blocks
     * and efficient contextual analysis.
     *
     * @param spawnerBlock The spawner block to check
     * @return true if it appears to be a natural spawner
     */
    private boolean isNaturalDungeonSpawner(Block spawnerBlock) {
        // Check the block below first (most efficient check)
        Block blockBelow = spawnerBlock.getRelative(BlockFace.DOWN);
        Material belowMaterial = blockBelow.getType();

        // Check common dungeon floor materials
        if (belowMaterial == Material.COBBLESTONE ||
                belowMaterial == Material.MOSSY_COBBLESTONE) {
            return true;
        }

        // Check mineshaft materials (for cave spider spawners)
        if (belowMaterial == Material.COBWEB ||
                belowMaterial == Material.OAK_PLANKS ||
                belowMaterial == Material.OAK_FENCE ||
                belowMaterial == Material.RAIL ||
                belowMaterial == Material.DEEPSLATE) {
            return true;
        }

        // For floating spawners, check if there's no block below
        if (belowMaterial == Material.AIR || belowMaterial == Material.CAVE_AIR) {
            // Quick surroundings check for natural cave features
            int naturalBlockCount = 0;
            int cobwebCount = 0;

            // Only check immediate surroundings (6 adjacent blocks) for better performance
            for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.EAST,
                    BlockFace.SOUTH, BlockFace.WEST,
                    BlockFace.UP}) {
                Block adjacentBlock = spawnerBlock.getRelative(face);
                Material material = adjacentBlock.getType();

                // Count natural blocks and cobwebs
                if (material.toString().contains("STONE") ||
                        material.toString().contains("DEEPSLATE") ||
                        material == Material.DIRT ||
                        material == Material.GRAVEL) {
                    naturalBlockCount++;
                } else if (material == Material.COBWEB) {
                    cobwebCount++;
                }
            }

            // If surrounded by natural blocks or has cobwebs, likely natural
            if (naturalBlockCount >= 3 || cobwebCount >= 1) {
                return true;
            }
        }

        // As a last check, examine the spawner type (lightweight operation)
        BlockState state = spawnerBlock.getState();
        if (state instanceof CreatureSpawner) {
            CreatureSpawner cs = (CreatureSpawner) state;
            EntityType entityType = cs.getSpawnedType();

            // Cave spiders are almost always from natural mineshaft spawners
            if (entityType == EntityType.CAVE_SPIDER) {
                return true;
            }
        }

        return false;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSpawnerClick(PlayerInteractEvent event) {
        // Quick validation checks
        if (!isValidSpawnerInteraction(event)) {
            return;
        }

        Player player = event.getPlayer();
        Block block = event.getClickedBlock();

        if (!configManager.getBoolean("natural-spawner-interaction")) {
            if (isNaturalDungeonSpawner(block)) {
                return;
            }
        }

        // Apply interaction cooldown
        if (!isInteractionAllowed(player)) {
            event.setCancelled(true);
            return;
        }

        ItemStack heldItem = player.getInventory().getItemInMainHand();
        Material itemType = heldItem.getType();

        // Allow normal block placement when sneaking with placeable block
        if (shouldAllowNormalBlockPlacement(player, itemType)) {
            return;
        }

        // Special handling for Bedrock players using tools
        if (isBedrockPlayerUsingTool(player, itemType)) {
            return;
        }

        // Prevent default interaction
        event.setCancelled(true);

        // Process spawner interaction
        handleSpawnerInteraction(player, block, heldItem, itemType);
    }

    /**
     * Validates if the event is a right-click on a spawner block
     */
    private boolean isValidSpawnerInteraction(PlayerInteractEvent event) {
        return event.getAction() == Action.RIGHT_CLICK_BLOCK &&
                event.getClickedBlock() != null &&
                event.getClickedBlock().getType() == Material.SPAWNER;
    }

    /**
     * Checks if the player's interaction should be allowed based on cooldown
     */
    private boolean isInteractionAllowed(Player player) {
        long currentTime = System.currentTimeMillis();
        Long lastInteraction = playerCooldowns.get(player.getUniqueId());

        if (lastInteraction != null && currentTime - lastInteraction < COOLDOWN_MS) {
            return false;
        }

        playerCooldowns.put(player.getUniqueId(), currentTime);
        return true;
    }

    /**
     * Determines if normal block placement should be allowed
     */
    private boolean shouldAllowNormalBlockPlacement(Player player, Material itemType) {
        return player.isSneaking() && itemType.isBlock() && itemType != Material.SPAWNER;
    }

    /**
     * Checks if the player is a Bedrock player using a tool
     */
    private boolean isBedrockPlayerUsingTool(Player player, Material itemType) {
        if (!isBedrockPlayer(player)) {
            return false;
        }

        String itemName = itemType.name();
        boolean isTool = itemName.endsWith("_PICKAXE") ||
                itemName.endsWith("_SHOVEL") ||
                itemName.endsWith("_HOE") ||
                itemName.endsWith("_AXE");

        return isTool;
    }

    /**
     * Main handler for spawner interactions
     */
    private void handleSpawnerInteraction(Player player, Block block, ItemStack heldItem, Material itemType) {

        // Get or create spawner data
        SpawnerData spawner = getOrCreateSpawnerData(block, player);

        // Check permission on claimed land
        if (!CheckOpenMenu.CanPlayerOpenMenu(player.getUniqueId(), block.getLocation())) {
            languageManager.sendMessage(player, "messages.spawner-protected");
            return;
        }

        // Handle spawn egg usage
        if (isSpawnEgg(itemType)) {
            spawnEggHandler.handleSpawnEggUse(player, (CreatureSpawner) block.getState(), spawner, heldItem);
            return;
        }

        // Handle spawner stacking
        if (itemType == Material.SPAWNER) {
            spawnerStackHandler.handleSpawnerStacking(player, block, spawner, heldItem);
            return;
        }

        // Open spawner menu if not using special items
        openSpawnerMenu(player, spawner);
    }

    /**
     * Gets existing spawner data or creates a new one
     */
    private SpawnerData getOrCreateSpawnerData(Block block, Player player) {
        SpawnerData spawner = spawnerManager.getSpawnerByLocation(block.getLocation());

        if (spawner == null) {
            // Create new spawner if it doesn't exist
            spawner = createNewSpawner(block, player);
        } else if (spawner.getEntityType() == null) {
            // Initialize existing spawner with null entity type
            initializeExistingSpawner(spawner, block);
        }

        return spawner;
    }

    /**
     * Opens the spawner menu if possible
     */
    private void openSpawnerMenu(Player player, SpawnerData spawner) {
        // Open the menu as normal
        spawnerMenuUI.openSpawnerMenu(player, spawner, false);
    }

    /**
     * Checks if the material is a spawn egg
     */
    private boolean isSpawnEgg(Material material) {
        return material.name().endsWith("_SPAWN_EGG");
    }

    /**
     * Creates a new spawner with default settings
     */
    private SpawnerData createNewSpawner(Block block, Player player) {
        // Generate short unique ID
        String spawnerId = UUID.randomUUID().toString().substring(0, 8);

        // Update the block state
        CreatureSpawner creatureSpawner = (CreatureSpawner) block.getState();
        EntityType entityType = getValidEntityType(creatureSpawner.getSpawnedType());

        // Apply changes to the block
        creatureSpawner.setSpawnedType(entityType);
        creatureSpawner.update();

        // Show particles if enabled
        if (configManager.isSpawnerCreateParticlesEnabled()) {
            spawnActivationParticles(block.getLocation());
        }

        // Create and save spawner data
        SpawnerData spawner = new SpawnerData(spawnerId, block.getLocation(), entityType, plugin);
        spawner.setSpawnerActive(true);

        spawnerManager.addSpawner(spawnerId, spawner);
        spawnerManager.queueSpawnerForSaving(spawnerId);

        // Notify player
        languageManager.sendMessage(player, "messages.activated");
        configManager.debug("Created new spawner with ID: " + spawnerId + " at " + block.getLocation());

        return spawner;
    }

    /**
     * Gets a valid entity type, defaulting if necessary
     */
    private EntityType getValidEntityType(EntityType type) {
        return (type == null || type == EntityType.UNKNOWN) ?
                configManager.getDefaultEntityType() : type;
    }

    /**
     * Spawns particles at the given location to indicate spawner activation
     */
    private void spawnActivationParticles(Location location) {
        location.getWorld().spawnParticle(
                ParticleWrapper.SPELL_WITCH,
                location.clone().add(0.5, 0.5, 0.5),
                50, 0.5, 0.5, 0.5, 0
        );
    }

    /**
     * Initializes an existing spawner with proper entity type
     */
    private void initializeExistingSpawner(SpawnerData spawner, Block block) {
        CreatureSpawner creatureSpawner = (CreatureSpawner) block.getState();
        spawner.setEntityType(getValidEntityType(creatureSpawner.getSpawnedType()));
    }

    /**
     * Detects if a player is connecting from Bedrock Edition
     */
    private boolean isBedrockPlayer(Player player) {
        try {
            FloodgateApi api = FloodgateApi.getInstance();
            return api != null && api.isFloodgatePlayer(player.getUniqueId());
        } catch (NoClassDefFoundError | NullPointerException e) {
            return false;
        }
    }

    /**
     * Initializes the periodic cooldown cleanup task
     */
    private void initCleanupTask() {
        plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                this::cleanupCooldowns,
                CLEANUP_INTERVAL_TICKS,
                CLEANUP_INTERVAL_TICKS
        );
    }

    /**
     * Removes expired cooldown entries
     */
    public void cleanupCooldowns() {
        long expirationThreshold = System.currentTimeMillis() - (COOLDOWN_MS * 10);
        playerCooldowns.entrySet().removeIf(entry -> entry.getValue() < expirationThreshold);
    }

    public void cleanup() {
        playerCooldowns.clear();
    }
}