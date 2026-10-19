package com.wafflegodxd.enchantment;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class EnchantmentPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final int ANVIL_RESULT_SLOT = 2;

    private NamespacedKey startedKey;
    private NamespacedKey bottleKey;
    private final Set<UUID> pendingLoss = new HashSet<>();

    private int startLevel;
    private int minLevel;
    private int maxLevel;
    private boolean killBonus;
    private boolean scaleEnchants;
    private int levelsPerTier;
    private boolean blockXpGain;
    private boolean freeEnchanting;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        startedKey = new NamespacedKey(this, "started");
        bottleKey = new NamespacedKey(this, "level_bottle");
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("enchantment").setExecutor(this);
    }

    private void loadSettings() {
        reloadConfig();
        startLevel = getConfig().getInt("start-level", 15);
        minLevel = getConfig().getInt("min-level", 0);
        maxLevel = getConfig().getInt("max-level", 30);
        killBonus = getConfig().getBoolean("kill-bonus", false);
        scaleEnchants = getConfig().getBoolean("scale-enchants", true);
        levelsPerTier = Math.max(1, getConfig().getInt("levels-per-tier", 5));
        blockXpGain = getConfig().getBoolean("block-xp-gain", true);
        freeEnchanting = getConfig().getBoolean("free-enchanting", true);
    }

    private void setLevel(Player player, int level) {
        player.setExp(0f);
        player.setLevel(Math.max(minLevel, Math.min(maxLevel, level)));
    }

    private void tell(Player player, String text, NamedTextColor color) {
        player.sendActionBar(Component.text(text, color));
    }

    private Component plain(String text, NamedTextColor color) {
        return Component.text(text, color).decoration(TextDecoration.ITALIC, false);
    }

    // A bottle o' enchanting that is worth one level.
    private ItemStack levelBottle() {
        ItemStack item = new ItemStack(Material.EXPERIENCE_BOTTLE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plain("Bottle of Levels", NamedTextColor.GREEN));
        meta.lore(List.of(plain("Right-click to gain 1 level", NamedTextColor.GRAY)));
        meta.getPersistentDataContainer().set(bottleKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    private boolean isLevelBottle(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(bottleKey, PersistentDataType.BYTE);
    }

    // Everyone starts at the start level, once.
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        var data = player.getPersistentDataContainer();
        if (data.has(startedKey, PersistentDataType.BYTE)) return;
        data.set(startedKey, PersistentDataType.BYTE, (byte) 1);
        setLevel(player, startLevel);
    }

    // Levels only come from bottles (and the optional kill bonus).
    @EventHandler
    public void onXp(PlayerExpChangeEvent event) {
        if (blockXpGain) event.setAmount(0);
    }

    // Any death costs one level and drops a bottle worth one level.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        event.setKeepLevel(true);
        event.setDroppedExp(0);

        if (victim.getLevel() > minLevel) {
            pendingLoss.add(victim.getUniqueId());
            victim.getWorld().dropItemNaturally(victim.getLocation(), levelBottle());
        }

        Player killer = victim.getKiller();
        if (killBonus && killer != null && !killer.equals(victim) && killer.getLevel() < maxLevel) {
            setLevel(killer, killer.getLevel() + 1);
            tell(killer, "+1 level (" + killer.getLevel() + ")", NamedTextColor.GREEN);
        }
    }

    // The level is taken once the player respawns.
    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!pendingLoss.remove(player.getUniqueId())) return;
        getServer().getScheduler().runTask(this, () -> {
            setLevel(player, player.getLevel() - 1);
            tell(player, "-1 level (" + player.getLevel() + ")", NamedTextColor.RED);
        });
    }

    // Right-click a bottle of levels to drink it instead of throwing it.
    @EventHandler(priority = EventPriority.HIGH)
    public void onUseBottle(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        if (!isLevelBottle(item)) return;

        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);

        Player player = event.getPlayer();
        if (player.getLevel() >= maxLevel) {
            tell(player, "You're already at the max level (" + maxLevel + ")", NamedTextColor.YELLOW);
            return;
        }
        item.setAmount(item.getAmount() - 1);
        setLevel(player, player.getLevel() + 1);
        tell(player, "+1 level (" + player.getLevel() + ")", NamedTextColor.GREEN);
    }

    // Dispensers can't throw bottles of levels.
    @EventHandler(ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        if (isLevelBottle(event.getItem())) event.setCancelled(true);
    }

    // Enchanting table: every enchant gets stronger as your level goes up (level 30 = tier 6, so Sharpness VI).
    // Enchants that only have one level (like Mending) stay at 1.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnchantScale(EnchantItemEvent event) {
        if (!scaleEnchants) return;
        int tier = Math.max(1, event.getEnchanter().getLevel() / levelsPerTier);
        event.getEnchantsToAdd().replaceAll((enchant, level) ->
                enchant.getMaxLevel() == 1 ? 1 : Math.max(level, tier));
    }

    // Enchanting table: give the cost back.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEnchant(EnchantItemEvent event) {
        if (!freeEnchanting) return;
        Player player = event.getEnchanter();
        int before = player.getLevel();
        getServer().getScheduler().runTask(this, () -> player.setLevel(before));
    }

    // Anvil: give the cost back when the result is taken.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onAnvil(InventoryClickEvent event) {
        if (!freeEnchanting) return;
        if (!(event.getInventory() instanceof AnvilInventory)) return;
        if (event.getRawSlot() != ANVIL_RESULT_SLOT) return;
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int before = player.getLevel();
        getServer().getScheduler().runTask(this, () -> {
            if (player.getLevel() < before) player.setLevel(before);
        });
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Component.text("Usage: /" + label + " <check [player]|set <player> <level>|reload>", NamedTextColor.YELLOW));
            return true;
        }

        switch (args[0].toLowerCase()) {
            case "check" -> {
                Player target = args.length > 1 ? getServer().getPlayerExact(args[1])
                        : (sender instanceof Player self ? self : null);
                if (target == null) {
                    sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
                    return true;
                }
                sender.sendMessage(Component.text(target.getName() + " is level " + target.getLevel() + ".", NamedTextColor.AQUA));
            }
            case "set" -> {
                if (!sender.hasPermission("enchantment.admin")) {
                    sender.sendMessage(Component.text("You don't have permission.", NamedTextColor.RED));
                    return true;
                }
                if (args.length < 3) {
                    sender.sendMessage(Component.text("Usage: /" + label + " set <player> <level>", NamedTextColor.YELLOW));
                    return true;
                }
                Player target = getServer().getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(Component.text("Player not found.", NamedTextColor.RED));
                    return true;
                }
                try {
                    setLevel(target, Integer.parseInt(args[2]));
                } catch (NumberFormatException e) {
                    sender.sendMessage(Component.text("Level must be a number.", NamedTextColor.RED));
                    return true;
                }
                sender.sendMessage(Component.text(target.getName() + " is now level " + target.getLevel() + ".", NamedTextColor.GREEN));
            }
            case "reload" -> {
                if (!sender.hasPermission("enchantment.admin")) {
                    sender.sendMessage(Component.text("You don't have permission.", NamedTextColor.RED));
                    return true;
                }
                loadSettings();
                sender.sendMessage(Component.text("Config reloaded.", NamedTextColor.GREEN));
            }
            default -> sender.sendMessage(Component.text("Unknown subcommand.", NamedTextColor.RED));
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return List.of("check", "set", "reload");
        if (args.length == 2 && (args[0].equalsIgnoreCase("check") || args[0].equalsIgnoreCase("set"))) {
            return getServer().getOnlinePlayers().stream().map(Player::getName).toList();
        }
        return List.of();
    }
}
