package com.wafflegodxd.enchantment;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.ShapelessRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class EnchantmentPlugin extends JavaPlugin implements Listener, TabExecutor {

    private static final int ANVIL_RESULT_SLOT = 2;

    // The 10 special enchants. They never go above level II.
    private enum Special {
        LIFESTEAL("Lifesteal", "Critical hits give you Regeneration (3s / 5s)", "_SWORD", "_AXE"),
        FROSTBITE("Frostbite", "Hits can chill and slow the target (25% / 40%)", "_SWORD", "_AXE"),
        EXECUTIONER("Executioner", "Bonus damage to targets under 35% health (+1.5 / +3)", "_AXE"),
        BLOODRUSH("Bloodrush", "Kills give you Speed (4s / 6s)", "_SWORD"),
        STATIC_SHOCK("Static Shock", "Attackers may get zapped (15% / 25%)", "_CHESTPLATE"),
        SECOND_WIND("Second Wind", "Low health gives Regeneration II (60s / 40s cooldown)", "_LEGGINGS"),
        NIGHTSIGHT("Nightsight", "Night Vision in dark places", "_HELMET"),
        SPRINGHEEL("Springheel", "Jump Boost I / II", "_BOOTS"),
        PROSPECTOR("Prospector", "Ores can drop extra (12% / 24%)", "_PICKAXE"),
        SNARE("Snare", "Arrows slow the target (Slowness I / II)", "BOW");

        final String display;
        final String description;
        final String[] suffixes;

        Special(String display, String description, String... suffixes) {
            this.display = display;
            this.description = description;
            this.suffixes = suffixes;
        }

        boolean fits(Material material) {
            String name = material.name();
            for (String suffix : suffixes) {
                if (name.endsWith(suffix)) return true;
            }
            return false;
        }

        String id() {
            return name().toLowerCase();
        }
    }

    private NamespacedKey startedKey;
    private NamespacedKey bottleKey;
    private NamespacedKey potionKey;
    private NamespacedKey recipeKey;
    private final Map<Special, NamespacedKey> specialKeys = new EnumMap<>(Special.class);
    private final Set<UUID> pendingLoss = new HashSet<>();
    private final Map<UUID, Long> secondWindReady = new HashMap<>();

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
    private boolean specialEnchants;
    private double specialChance;
    private int specialTwoMinLevel;
    private boolean anyEnchant;
    private int anyEnchantLevel;
    private double anyEnchantChance;

    // ------------------------------------------------------------------ setup

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();
        startedKey = new NamespacedKey(this, "started");
        bottleKey = new NamespacedKey(this, "level_bottle");
        potionKey = new NamespacedKey(this, "level_potion");
        recipeKey = new NamespacedKey(this, "potion_of_levels");
        for (Special special : Special.values()) {
            specialKeys.put(special, new NamespacedKey(this, "se_" + special.id()));
        }
        registerPotionRecipe();
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("enchantment").setExecutor(this);

        // Armor that is too powerful gets taken off (checked 5 times a second).
        getServer().getScheduler().runTaskTimer(this, () -> {
            for (Player player : getServer().getOnlinePlayers()) checkArmor(player);
        }, 20L, 4L);

        // Passive special enchants (Nightsight, Springheel) refresh once a second.
        getServer().getScheduler().runTaskTimer(this, () -> {
            if (!specialEnchants) return;
            for (Player player : getServer().getOnlinePlayers()) passiveEffects(player);
        }, 40L, 20L);
    }

    @Override
    public void onDisable() {
        if (recipeKey != null) getServer().removeRecipe(recipeKey);
    }

    private double chance(String path, double fallback) {
        return Math.max(0.0, Math.min(1.0, getConfig().getDouble(path, fallback)));
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
        potionLevels = Math.max(1, getConfig().getInt("potion-levels", 2));
        specialEnchants = getConfig().getBoolean("special-enchants", true);
        specialChance = chance("special-chance", 0.30);
        specialTwoMinLevel = getConfig().getInt("special-level-2-min-level", 25);
        anyEnchant = getConfig().getBoolean("any-enchant", true);
        anyEnchantLevel = getConfig().getInt("any-enchant-level", 30);
        anyEnchantChance = chance("any-enchant-chance", 0.50);
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

    // The crafted Potion of Levels.
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

    // ---------------------------------------------------------- special enchants

    private String roman(int level) {
        return level >= 2 ? "II" : "I";
    }

    // 0 if the item doesn't have that special enchant.
    private int specialLevel(ItemStack item, Special special) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) return 0;
        Integer level = item.getItemMeta().getPersistentDataContainer()
                .get(specialKeys.get(special), PersistentDataType.INTEGER);
        return level == null ? 0 : level;
    }

    // Put the special enchant on the item (max level II) and show it in the lore.
    private void addSpecial(ItemStack item, Special special, int level) {
        ItemMeta meta = item.getItemMeta();
        int clamped = Math.max(1, Math.min(2, level));
        meta.getPersistentDataContainer().set(specialKeys.get(special), PersistentDataType.INTEGER, clamped);
        refreshLore(meta);
        item.setItemMeta(meta);
    }

    // Special enchant lines go at the top of the lore; other lore is left alone.
    private void refreshLore(ItemMeta meta) {
        var serializer = PlainTextComponentSerializer.plainText();
        List<Component> lore = new ArrayList<>();
        if (meta.lore() != null) lore.addAll(meta.lore());
        lore.removeIf(line -> {
            String text = serializer.serialize(line);
            for (Special special : Special.values()) {
                if (text.equals(special.display + " I") || text.equals(special.display + " II")) return true;
            }
            return false;
        });
        List<Component> mine = new ArrayList<>();
        for (Special special : Special.values()) {
            int have = meta.getPersistentDataContainer()
                    .getOrDefault(specialKeys.get(special), PersistentDataType.INTEGER, 0);
            if (have > 0) mine.add(plain(special.display + " " + roman(have), NamedTextColor.LIGHT_PURPLE));
        }
        lore.addAll(0, mine);
        meta.lore(lore);
    }

    private Special randomSpecialFor(Material material) {
        List<Special> options = new ArrayList<>();
        for (Special special : Special.values()) {
            if (special.fits(material)) options.add(special);
        }
        if (options.isEmpty()) return null;
        return options.get(ThreadLocalRandom.current().nextInt(options.size()));
    }

    private Special findSpecial(String name) {
        for (Special special : Special.values()) {
            if (special.id().equalsIgnoreCase(name) || special.display.equalsIgnoreCase(name)) return special;
        }
        return null;
    }

    // Crit = hitting while falling (the vanilla rule, roughly).
    private boolean isCrit(Player player) {
        return player.getFallDistance() > 0.0F && !player.isOnGround() && !player.isInWater()
                && !player.isSprinting() && !player.isInsideVehicle();
    }

    // Lifesteal, Frostbite, Executioner, Static Shock and Snare.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSpecialHit(EntityDamageByEntityEvent event) {
        if (!specialEnchants) return;
        if (!(event.getEntity() instanceof LivingEntity target)) return;
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        // Snare: arrows that were shot from a Snare bow.
        if (event.getDamager() instanceof Projectile projectile) {
            Integer snare = projectile.getPersistentDataContainer()
                    .get(specialKeys.get(Special.SNARE), PersistentDataType.INTEGER);
            if (snare != null) {
                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, snare - 1));
            }
            return;
        }

        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) return;

        // Static Shock: whoever hits the wearer may get zapped.
        if (target instanceof Player wearer && event.getDamager() instanceof LivingEntity attacker) {
            int shock = specialLevel(wearer.getInventory().getChestplate(), Special.STATIC_SHOCK);
            if (shock > 0 && rng.nextDouble() < (shock == 1 ? 0.15 : 0.25)) {
                attacker.damage(shock == 1 ? 2.0 : 3.0);
                attacker.getWorld().spawnParticle(Particle.ELECTRIC_SPARK,
                        attacker.getLocation().add(0, 1, 0), 10, 0.3, 0.5, 0.3, 0.02);
            }
        }

        // Weapon enchants.
        if (!(event.getDamager() instanceof Player player)) return;
        ItemStack weapon = player.getInventory().getItemInMainHand();

        int lifesteal = specialLevel(weapon, Special.LIFESTEAL);
        if (lifesteal > 0 && isCrit(player)) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, lifesteal == 1 ? 60 : 100, 0));
        }

        int frostbite = specialLevel(weapon, Special.FROSTBITE);
        if (frostbite > 0 && rng.nextDouble() < (frostbite == 1 ? 0.25 : 0.40)) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 0));
            target.setFreezeTicks(Math.max(target.getFreezeTicks(), 100));
        }

        int executioner = specialLevel(weapon, Special.EXECUTIONER);
        if (executioner > 0 && target.getHealth() <= target.getMaxHealth() * 0.35) {
            event.setDamage(event.getDamage() + (executioner == 1 ? 1.5 : 3.0));
        }
    }

    // Bloodrush: kills give Speed.
    @EventHandler
    public void onKill(EntityDeathEvent event) {
        if (!specialEnchants) return;
        Player killer = event.getEntity().getKiller();
        if (killer == null || killer.equals(event.getEntity())) return;
        int bloodrush = specialLevel(killer.getInventory().getItemInMainHand(), Special.BLOODRUSH);
        if (bloodrush > 0) {
            killer.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, bloodrush == 1 ? 80 : 120, 0));
        }
    }

    // Second Wind: dropping to 3 hearts or less gives Regeneration II, with a cooldown.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHurt(EntityDamageEvent event) {
        if (!specialEnchants || !(event.getEntity() instanceof Player player)) return;
        int wind = specialLevel(player.getInventory().getLeggings(), Special.SECOND_WIND);
        if (wind == 0) return;
        double left = player.getHealth() - event.getFinalDamage();
        if (left <= 0.0 || left > 6.0) return;
        long now = System.currentTimeMillis();
        if (secondWindReady.getOrDefault(player.getUniqueId(), 0L) > now) return;
        secondWindReady.put(player.getUniqueId(), now + (wind == 1 ? 60_000L : 40_000L));
        player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, wind == 1 ? 60 : 100, 1));
        tell(player, "Second Wind!", NamedTextColor.LIGHT_PURPLE);
    }

    // Prospector: ores can drop extra.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onProspect(BlockBreakEvent event) {
        if (!specialEnchants) return;
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) return;
        ItemStack tool = player.getInventory().getItemInMainHand();
        int prospector = specialLevel(tool, Special.PROSPECTOR);
        Block block = event.getBlock();
        if (prospector == 0 || !block.getType().name().endsWith("_ORE")) return;
        if (ThreadLocalRandom.current().nextDouble() >= (prospector == 1 ? 0.12 : 0.24)) return;
        for (ItemStack drop : block.getDrops(tool)) {
            block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), drop);
        }
    }

    // Snare: remember which arrows came from a Snare bow.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSnareShoot(EntityShootBowEvent event) {
        if (!specialEnchants) return;
        int snare = specialLevel(event.getBow(), Special.SNARE);
        if (snare == 0) return;
        event.getProjectile().getPersistentDataContainer()
                .set(specialKeys.get(Special.SNARE), PersistentDataType.INTEGER, snare);
    }

    // Nightsight and Springheel are always on while worn.
    private void passiveEffects(Player player) {
        PlayerInventory inv = player.getInventory();

        int sight = specialLevel(inv.getHelmet(), Special.NIGHTSIGHT);
        if (sight > 0 && player.getLocation().getBlock().getLightLevel() < (sight == 1 ? 5 : 8)) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 300, 0, false, false, true));
        }

        int spring = specialLevel(inv.getBoots(), Special.SPRINGHEEL);
        if (spring > 0) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 60, spring - 1, false, false, true));
        }
    }

    // ------------------------------------------------------------ gear gating

    // An item's tier is its highest enchantment level (Sharpness VI = tier 6).
    private int itemTier(ItemStack item) {
        if (item == null || item.getType().isAir()) return 0;
        int best = 0;
        for (int enchantLevel : item.getEnchantments().values()) best = Math.max(best, enchantLevel);
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

    // Level 30 only: the table can also roll an enchant that normally doesn't fit the item
    // (Wind Burst on a sword). It is capped at that enchant's normal max level.
    private void addOffTypeEnchant(ItemStack item, Map<Enchantment, Integer> chosen, int tier) {
        List<Enchantment> options = new ArrayList<>();
        for (Enchantment candidate : Registry.ENCHANTMENT) {
            if (candidate.canEnchantItem(item)) continue;
            if (candidate.getKey().getKey().contains("curse")) continue;
            if (chosen.containsKey(candidate)) continue;
            boolean clash = false;
            for (Enchantment existing : chosen.keySet()) {
                if (existing.conflictsWith(candidate)) clash = true;
            }
            if (!clash) options.add(candidate);
        }
        if (options.isEmpty()) return;
        Enchantment pick = options.get(ThreadLocalRandom.current().nextInt(options.size()));
        chosen.put(pick, Math.max(1, Math.min(pick.getMaxLevel(), tier)));
    }

    // Runs one tick after the table finishes, so the item already has its normal enchants.
    private void applySpecialAtTable(Player player, Special special, int specialLevel) {
        Inventory top = player.getOpenInventory().getTopInventory();
        ItemStack enchanted = top.getItem(0);
        if (enchanted == null || !special.fits(enchanted.getType())) return;
        addSpecial(enchanted, special, specialLevel);
        top.setItem(0, enchanted);
        tell(player, "Special enchant: " + special.display + " " + roman(specialLevel), NamedTextColor.LIGHT_PURPLE);
    }

    // Enchanting table: enchants get stronger as your level goes up (level 30 = tier 6, Sharpness VI).
    // Enchants that only have one level (like Mending) stay at 1.
    // At level 30 it can also roll an off-type enchant, and sometimes a special enchant.
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEnchantScale(EnchantItemEvent event) {
        Player enchanter = event.getEnchanter();
        ItemStack target = event.getItem();
        int tier = playerTier(enchanter);
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        if (scaleEnchants) {
            event.getEnchantsToAdd().replaceAll((enchant, current) ->
                    enchant.getMaxLevel() == 1 ? 1 : Math.max(current, tier));
        }

        if (anyEnchant && enchanter.getLevel() >= anyEnchantLevel && rng.nextDouble() < anyEnchantChance) {
            addOffTypeEnchant(target, event.getEnchantsToAdd(), tier);
        }

        if (specialEnchants && rng.nextDouble() < specialChance) {
            Special pick = randomSpecialFor(target.getType());
            if (pick != null) {
                int rolled = (enchanter.getLevel() >= specialTwoMinLevel && rng.nextDouble() < 0.40) ? 2 : 1;
                getServer().getScheduler().runTask(this, () -> applySpecialAtTable(enchanter, pick, rolled));
            }
        }
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
            sender.sendMessage(Component.text("Usage: /" + label + " <check [player]|list|set <player> <level>|enchant <name> [1-2]|reload>", NamedTextColor.YELLOW));
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
            case "list" -> {
                sender.sendMessage(Component.text("Special enchants (max level II):", NamedTextColor.LIGHT_PURPLE));
                for (Special special : Special.values()) {
                    sender.sendMessage(Component.text(special.display + " - " + special.description, NamedTextColor.GRAY));
                }
            }
            case "enchant" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage(Component.text("Only players can use this.", NamedTextColor.RED));
                    return true;
                }
                if (!sender.hasPermission("enchantment.admin")) {
                    sender.sendMessage(Component.text("You don't have permission.", NamedTextColor.RED));
                    return true;
                }
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Usage: /" + label + " enchant <name> [1-2]. See /" + label + " list", NamedTextColor.YELLOW));
                    return true;
                }
                Special special = findSpecial(args[1]);
                if (special == null) {
                    sender.sendMessage(Component.text("Unknown special enchant. See /" + label + " list", NamedTextColor.RED));
                    return true;
                }
                ItemStack held = player.getInventory().getItemInMainHand();
                if (held.getType().isAir() || !special.fits(held.getType())) {
                    sender.sendMessage(Component.text("Hold an item that fits " + special.display + ".", NamedTextColor.RED));
                    return true;
                }
                int wanted = 1;
                if (args.length > 2) {
                    try {
                        wanted = Integer.parseInt(args[2]);
                    } catch (NumberFormatException e) {
                        sender.sendMessage(Component.text("Level must be 1 or 2.", NamedTextColor.RED));
                        return true;
                    }
                }
                addSpecial(held, special, wanted);
                player.getInventory().setItemInMainHand(held);
                sender.sendMessage(Component.text("Added " + special.display + " " + roman(wanted) + ".", NamedTextColor.GREEN));
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
        if (args.length == 1) return List.of("check", "list", "set", "enchant", "reload");
        if (args.length == 2 && (args[0].equalsIgnoreCase("check") || args[0].equalsIgnoreCase("set"))) {
            return getServer().getOnlinePlayers().stream().map(Player::getName).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("enchant")) {
            List<String> ids = new ArrayList<>();
            for (Special special : Special.values()) ids.add(special.id());
            return ids;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("enchant")) return List.of("1", "2");
        return List.of();
    }
}
