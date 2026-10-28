package com.wafflegodxd.enchantment;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
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
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerExpChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.AnvilInventory;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
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
    private NamespacedKey potionKey;
    private NamespacedKey recipeKey;
    private final Set<UUID> pendingLoss = new HashSet<>();

    private int startLevel;
    private int minLevel;
    private int maxLevel;
    private boolean killBonus;
    private boolean scaleEnchants;
    private int levelsPerTier;
    private boolean gearGating;
    private boolean blockPickup;
    private boolean blockXpGain;
    private boolean freeEnchanting;
    private boolean potionRecipe;
    private int potionLevels;

    // ------------------------------------------------------------------ setup

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        startedKey = new NamespacedKey(this, "started");
        bottleKey = new NamespacedKey(this, "level_bottle");
        potionKey = new NamespacedKey(this, "level_potion");
        recipeKey = new NamespacedKey(this, "potion_of_levels");
        registerPotionRecipe();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("enchantment").setExecutor(this);

        // Armor that is too powerful gets taken off (checked 5 times a second).
        getServer().getScheduler().runTaskTimer(this, () -> {
            for (Player player : getServer().getOnlinePlayers()) checkArmor(player);
        }, 20L, 4L);
    }

    @Override
    public void onDisable() {
        if (recipeKey != null) getServer().removeRecipe(recipeKey);
    }

    private void loadSettings() {
        reloadConfig();
        startLevel = getConfig().getInt("start-level", 15);
        minLevel = getConfig().getInt("min-level", 0);
        maxLevel = getConfig().getInt("max-level", 30);
        killBonus = getConfig().getBoolean("kill-bonus", false);
        scaleEnchants = getConfig().getBoolean("scale-enchants", true);
        levelsPerTier = Math.max(1, getConfig().getInt("levels-per-tier", 5));
        gearGating = getConfig().getBoolean("gear-gating", true);
        blockPickup = getConfig().getBoolean("block-pickup", false);
        blockXpGain = getConfig().getBoolean("block-xp-gain", true);
        freeEnchanting = getConfig().getBoolean("free-enchanting", true);
        potionRecipe = getConfig().getBoolean("potion-recipe", true);
        potionLevels = Math.max(1, getConfig().getInt("potion-levels", 5));
    }

    // 2 gold ingots + 4 diamonds + 2 netherite ingots + 1 glass bottle, in any arrangement.
    private void registerPotionRecipe() {
        if (!potionRecipe) return;
        ShapelessRecipe recipe = new ShapelessRecipe(recipeKey, levelPotion());
        recipe.addIngredient(2, Material.GOLD_INGOT);
        recipe.addIngredient(4, Material.DIAMOND);
        recipe.addIngredient(2, Material.NETHERITE_INGOT);
        recipe.addIngredient(1, Material.GLASS_BOTTLE);
        getServer().addRecipe(recipe);
    }

    // ---------------------------------------------------------------- helpers

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

    // The Bottle of Levels dropped on death: a bottle o' enchanting worth 1 level.
    private ItemStack levelBottle() {
        ItemStack item = new ItemStack(Material.EXPERIENCE_BOTTLE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plain("Bottle of Levels", NamedTextColor.GREEN));
        meta.lore(List.of(plain("Right-click to gain 1 level", NamedTextColor.GRAY)));
        meta.getPersistentDataContainer().set(bottleKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    // The crafted Potion of Levels: worth several levels.
    private ItemStack levelPotion() {
        ItemStack item = new ItemStack(Material.POTION);
        PotionMeta meta = (PotionMeta) item.getItemMeta();
        meta.displayName(plain("Potion of Levels", NamedTextColor.LIGHT_PURPLE));
        meta.lore(List.of(plain("Right-click to gain levels", NamedTextColor.GRAY)));
        meta.setColor(Color.fromRGB(0x55FF55));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(potionKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    // How many levels one of our items is worth (0 if it isn't ours).
    private int levelsIn(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 0;
        var data = item.getItemMeta().getPersistentDataContainer();
        if (data.has(bottleKey, PersistentDataType.BYTE)) return 1;
        if (data.has(potionKey, PersistentDataType.BYTE)) return potionLevels;
        return 0;
    }

    // ------------------------------------------------------------ gear gating

    // An item's tier is its highest enchantment level (Sharpness VI = tier 6).
    private int itemTier(ItemStack item) {
        if (item == null || item.getType().isAir()) return 0;
        int best = 0;
        for (int level : item.getEnchantments().values()) best = Math.max(best, level);
        return best;
    }

    // Every levels-per-tier levels is one tier (level 15 = tier 3, level 30 = tier 6).
    private int playerTier(Player player) {
        return Math.max(1, player.getLevel() / levelsPerTier);
    }

    private boolean canUse(Player player, ItemStack item) {
        if (!gearGating) return true;
        if (item == null || item.getType().isAir()) return true;
        if (player.hasPermission("enchantment.bypass")) return true;
        return itemTier(item) <= playerTier(player);
    }

    private void warn(Player player, ItemStack item) {
        int need = itemTier(item) * levelsPerTier;
        tell(player, "Too powerful! Needs level " + need + " (you're level " + player.getLevel() + ")", NamedTextColor.RED);
    }

    // Take off any armor that is too powerful and put it in the inventory.
    private void checkArmor(Player player) {
        if (!gearGating) return;
        PlayerInventory inv = player.getInventory();
        ItemStack[] armor = inv.getArmorContents();
        boolean changed = false;
        for (int i = 0; i < armor.length; i++) {
            ItemStack piece = armor[i];
            if (piece == null || canUse(player, piece)) continue;
            armor[i] = null;
            changed = true;
            warn(player, piece);
            inv.addItem(piece).values().forEach(left ->
                    player.getWorld().dropItemNaturally(player.getLocation(), left));
        }
        if (changed) inv.setArmorContents(armor);
    }

    // Too-powerful weapons do no damage.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;
        ItemStack weapon = player.getInventory().getItemInMainHand();
        if (canUse(player, weapon)) return;
        event.setCancelled(true);
        warn(player, weapon);
    }

    // Too-powerful tools can't break blocks.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        ItemStack tool = player.getInventory().getItemInMainHand();
        if (canUse(player, tool)) return;
        event.setCancelled(true);
        warn(player, tool);
    }

    // Too-powerful bows and crossbows can't shoot.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        ItemStack bow = event.getBow();
        if (canUse(player, bow)) return;
        event.setCancelled(true);
        warn(player, bow);
    }

    // Too-powerful items can't be used with right-click (tridents, shields, tools on blocks).
    @EventHandler(priority = EventPriority.HIGH)
    public void onGearUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        Player player = event.getPlayer();
        if (canUse(player, item)) return;
        event.setUseItemInHand(Event.Result.DENY);
        warn(player, item);
    }

    // Optional: too-powerful items can't even be picked up.
    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!blockPickup || !(event.getEntity() instanceof Player player)) return;
        ItemStack item = event.getItem().getItemStack();
        if (canUse(player, item)) return;
        event.setCancelled(true);
        warn(player, item);
    }

    // ------------------------------------------------------------ level rules

    // Everyone starts at the start level, once.
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (potionRecipe) player.discoverRecipe(recipeKey);
        var data = player.getPersistentDataContainer();
        if (data.has(startedKey, PersistentDataType.BYTE)) return;
        data.set(startedKey, PersistentDataType.BYTE, (byte) 1);
        setLevel(player, startLevel);
    }

    // Levels only come from bottles and potions (and the optional kill bonus).
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

    // Right-click a bottle or potion of levels to drink it.
    @EventHandler(priority = EventPriority.HIGH)
    public void onUseBottle(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        EquipmentSlot hand = event.getHand();
        if (hand == null) return;
        Player player = event.getPlayer();
        ItemStack held = player.getInventory().getItem(hand);
        int gained = levelsIn(held);
        if (gained == 0) return;

        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);

        if (player.getLevel() >= maxLevel) {
            tell(player, "You're already at the max level (" + maxLevel + ")", NamedTextColor.YELLOW);
            return;
        }

        ItemStack rest = held.clone();
        rest.setAmount(held.getAmount() - 1);
        player.getInventory().setItem(hand, rest.getAmount() > 0 ? rest : null);
        setLevel(player, player.getLevel() + gained);
        tell(player, "+" + gained + (gained == 1 ? " level (" : " levels (") + player.getLevel() + ")", NamedTextColor.GREEN);
    }

    // Dispensers can't throw or use these items.
    @EventHandler(ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        if (levelsIn(event.getItem()) > 0) event.setCancelled(true);
    }

    // ------------------------------------------------------------- enchanting

    // Enchanting table: enchants get stronger as your level goes up (level 30 = tier 6, Sharpness VI).
    // Enchants that only have one level (like Mending) stay at 1.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnchantScale(EnchantItemEvent event) {
        if (!scaleEnchants) return;
        int tier = playerTier(event.getEnchanter());
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

    // ---------------------------------------------------------------- command

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
                sender.sendMessage(Component.text(target.getName() + " is level " + target.getLevel()
                        + " (gear tier " + playerTier(target) + ").", NamedTextColor.AQUA));
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
                sender.sendMessage(Component.text("Config reloaded. (The recipe only changes after a full restart.)", NamedTextColor.GREEN));
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
