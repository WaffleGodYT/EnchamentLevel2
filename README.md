# Enchantment

Lifesteal, but with XP levels. For Paper 1.21.11 (Java 21).

## Rules
- Everyone starts at level 15.
- Dying for any reason costs 1 level.
- When you die you drop a Bottle of Levels (looks like a bottle o' enchanting). Right-click it to gain 1 level. Whoever grabs it gets the level.
- Craft a Potion of Levels with 2 gold ingots, 4 diamonds, 2 netherite ingots and 1 glass bottle (any arrangement). Right-click it to gain 2 levels.
- XP from mobs and mining is blocked, so bottles and potions are the only way to level up.
- Levels cap at 30 and can't go below 0.

## Enchanting scales with your level
Every 5 levels is one tier. Level 15 = tier 3, level 30 = tier 6 (Sharpness VI).
Single-level enchants like Mending stay at 1. Enchanting tables and anvils cost no levels.

### Any enchant on any item (level 30)
At level 30 the enchanting table has a 50% chance to add one extra enchant that normally can't go on that item (for example Wind Burst on a sword or axe). It is capped at that enchant's normal max level. Anvils still follow vanilla rules.

## Special enchants (max level II)
At the enchanting table there is a 30% chance to also get one special enchant that fits the item. Level II needs player level 25+ and is a 40% roll. They show up as purple lines in the item's lore.

| Enchant | Goes on | What it does (I / II) |
|---|---|---|
| Lifesteal | sword, axe | Critical hits give Regeneration for 3s / 5s |
| Frostbite | sword, axe | 25% / 40% chance a hit chills and slows the target |
| Executioner | axe | +1.5 / +3 damage to targets under 35% health |
| Bloodrush | sword | Kills give Speed for 4s / 6s |
| Static Shock | chestplate | 15% / 25% chance an attacker gets zapped for 1 / 1.5 hearts |
| Second Wind | leggings | Under 3 hearts: Regeneration II for 3s / 5s (60s / 40s cooldown) |
| Nightsight | helmet | Night Vision in dark places (darker than light 5 / 8) |
| Springheel | boots | Jump Boost I / II |
| Prospector | pickaxe | 12% / 24% chance ores drop extra |
| Snare | bow, crossbow | Arrows slow the target (Slowness I / II) |

## Gear is locked by your level
An item's tier is its highest enchantment level. You can only use gear at or below your own tier.
- Level 15 (tier 3) can't use a Sharpness VI sword (needs level 30).
- Too-powerful armor is taken off and put in your inventory.
- Too-powerful weapons do no damage, tools can't break blocks, and bows, crossbows and tridents don't work.
- You can still pick it up (turn on `block-pickup` to stop that too).
- Lose a level and you can drop a tier, so your best gear can lock up until you get the level back.
- Special enchants don't count toward the lock.

## Build
1. Install JDK 21 and Maven.
2. In this folder, run: `mvn package`
3. Your plugin is `target/Enchantment-1.0.0.jar`. Drop it in your server's `plugins` folder.

## Commands
- `/enchantment check [player]` shows a player's level and gear tier
- `/enchantment list` lists the special enchants
- `/enchantment enchant <name> [1-2]` puts a special enchant on the item you're holding (op only, good for testing)
- `/enchantment set <player> <level>` (op only)
- `/enchantment reload` (op only)

## Permissions
- `enchantment.admin` (op): set, enchant and reload
- `enchantment.bypass` (nobody by default): ignore the gear lock. Ops are NOT exempt unless you give them this.

## Config (`plugins/Enchantment/config.yml`)

| Setting | Default | What it does |
|---|---|---|
| `start-level` | 15 | Level new players start at |
| `min-level` | 0 | Floor (no loss or bottle at this level) |
| `max-level` | 30 | Cap |
| `kill-bonus` | false | Killer also gets 1 level instantly |
| `block-xp-gain` | true | Only bottles and potions give levels |
| `scale-enchants` | true | Table enchants scale with your level |
| `levels-per-tier` | 5 | Player levels per enchant tier |
| `free-enchanting` | true | Enchanting and anvils cost nothing |
| `any-enchant` | true | Level 30 table can add off-type enchants |
| `any-enchant-level` | 30 | Level needed for that |
| `any-enchant-chance` | 0.5 | Chance per enchant |
| `special-enchants` | true | Turn the 10 special enchants on or off |
| `special-chance` | 0.3 | Chance the table adds a special enchant |
| `special-level-2-min-level` | 25 | Level needed to roll a level II special enchant |
| `gear-gating` | true | Lock gear by enchantment level |
| `block-pickup` | false | Also block picking up too-powerful gear |
| `potion-recipe` | true | Enable the Potion of Levels recipe |
| `potion-levels` | 2 | Levels one Potion of Levels gives |
