# Enchantment

Lifesteal, but with XP levels. For Paper 1.21.11 (Java 21).

- Everyone starts at level 15 (enough for level 2 enchantments).
- Dying for any reason costs you 1 level.
- When you die you drop a Bottle of Levels, which looks like a bottle o' enchanting. Right-click it to gain 1 level. Whoever grabs it gets the level.
- Enchants from the enchanting table scale with your level: every 5 levels is one tier. Level 15 = tier 3, level 30 = tier 6 (Sharpness VI). Single-level enchants like Mending stay at 1.
- XP from mobs and mining is blocked, so bottles are the only way to level up.
- Enchanting tables and anvils don't spend your levels.
- You keep your levels on death (minus the 1) and drop no XP.

## Build

1. Install JDK 21 and Maven.
2. In this folder, run: `mvn package`
3. Your plugin is `target/Enchantment-1.0.0.jar`. Drop it in your server's `plugins` folder.

## Commands

- `/enchantment check [player]` shows a player's level
- `/enchantment set <player> <level>` (op only)
- `/enchantment reload` (op only)

## Config (`plugins/Enchantment/config.yml`)

| Setting | Default | What it does |
|---|---|---|
| `start-level` | 15 | Level new players start at |
| `min-level` | 0 | Floor (no loss or bottle at this level) |
| `max-level` | 30 | Cap |
| `kill-bonus` | false | Killer also gets 1 level instantly |
| `scale-enchants` | true | Table enchants scale with your level |
| `levels-per-tier` | 5 | Player levels per enchant level |
| `block-xp-gain` | true | Only bottles give levels |
| `free-enchanting` | true | Enchanting and anvils cost nothing |
