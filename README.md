<p align="center">
  <img src="img/icon_256x/cookFish.png" alt="BetaAchievement Logo" width="128">
</p>

<h1 align="center">BetaAchievement</h1>

<p align="center">Original achievement detection, persistent progress, and speedrun leaderboards for Minecraft Beta 1.7.3 / Project Poseidon.</p>

<p align="center">
  <img src="https://img.shields.io/badge/Minecraft-Beta%201.7.3-3fb950" alt="Minecraft Beta 1.7.3">
  <img src="https://img.shields.io/badge/Project%20Poseidon-supported-5865F2" alt="Project Poseidon supported">
  <img src="https://img.shields.io/badge/Java-8-orange" alt="Java 8">
  <img src="https://img.shields.io/badge/Database-SQLite%20%2F%20MySQL-blue" alt="SQLite / MySQL">
</p>

<p align="center"><b>English</b> · <a href="README-zh.md">简体中文</a></p>
<p align="center">
  <a href="https://che11111.github.io/BetaAchievement/"><img src="img/achievements-page.png" width="560" alt="Open the online achievement website"></a>
</p>

BetaAchievement is a server-side achievement plugin for Minecraft Beta 1.7.3 Project Poseidon. It evaluates and persists all 16 original achievements on the server, supports SQLite and MySQL/MariaDB, and exposes an API for querying achievement progress.

## Features

- Evaluate all 16 original achievements on the server and enforce parent requirements.
- Handle Beta 1.7.3 server events that are missing or incomplete for inventory, crafting, and special actions.
- Broadcast unlocks to the whole server and optionally write them to the console.
- Track cumulative time, splits, run history, personal bests, and a server leaderboard for 16/16 speedruns.
- Persist players, runs, and individual progress with SQLite or MySQL/MariaDB.
- Provide other plugins with an API for current progress, full-completion rank, and run data.

## Server-Side Achievement Detection

Client and server achievement states are not synchronized because Beta 1.7.3 evaluates achievements only on the client. There are 16 achievements in total, and some triggers differ slightly because the server does not receive every event the client uses.

| Icon | **#** | Achievement ID | English name | Requirement | Detection | Parent |
| :---: | :--- | :--- | :--- | :--- | :--- | :--- |
| <img src="img/icon_256x/openInventory.png" width="128" alt="Taking Inventory"> | 1 | `openInventory` | Taking Inventory | Pick up any item | Unlocks when the player picks up any item. Opening the inventory with E in Beta 1.7.3 does not send a packet and produces no server event. | None |
| <img src="img/icon_256x/mineWood.png" width="128" alt="Getting Wood"> | 2 | `mineWood` | Getting Wood | Break a tree and pick up the dropped wood block | Unlocks when the player picks up item ID 17 (wood). | Taking Inventory |
| <img src="img/icon_256x/buildWorkBench.png" width="128" alt="Benchmarking"> | 3 | `buildWorkBench` | Benchmarking | Craft a crafting table from four wood planks | Unlocks when item ID 58 (crafting table) is taken from a crafting output slot. | Getting Wood |
| <img src="img/icon_256x/buildPickaxe.png" width="128" alt="Time to Mine!"> | 4 | `buildPickaxe` | Time to Mine! | Craft a pickaxe from planks and sticks | Unlocks when any pickaxe is taken from a crafting output slot: iron 257, wood 270, stone 274, diamond 278, or gold 285. | Benchmarking |
| <img src="img/icon_256x/buildFurnace.png" width="128" alt="Hot Topic"> | 5 | `buildFurnace` | Hot Topic | Craft a furnace from eight cobblestone | Unlocks when item ID 61 (furnace) is taken from a crafting output slot. | Time to Mine! |
| <img src="img/icon_256x/acquireIron.png" width="128" alt="Acquire Hardware"> | 6 | `acquireIron` | Acquire Hardware | Smelt an iron ingot | Unlocks when item ID 265 (iron ingot) is taken from a furnace result slot. | Hot Topic |
| <img src="img/icon_256x/buildHoe.png" width="128" alt="Time to Farm!"> | 7 | `buildHoe` | Time to Farm! | Craft a hoe from planks and sticks | Unlocks when any hoe is taken from a crafting output slot: wood 290, stone 291, iron 292, diamond 293, or gold 294. | Benchmarking |
| <img src="img/icon_256x/makeBread.png" width="128" alt="Bake Bread"> | 8 | `makeBread` | Bake Bread | Craft bread from wheat | Unlocks when item ID 297 (bread) is taken from a crafting output slot. | Time to Farm! |
| <img src="img/icon_256x/bakeCake.png" width="128" alt="The Lie"> | 9 | `bakeCake` | The Lie | Craft a cake from wheat, sugar, milk, and eggs | Unlocks when item ID 354 (cake) is taken from a crafting output slot. | Time to Farm! |
| <img src="img/icon_256x/buildBetterPickaxe.png" width="128" alt="Getting an Upgrade"> | 10 | `buildBetterPickaxe` | Getting an Upgrade | Craft a pickaxe better than a wooden one | Unlocks when an iron 257, stone 274, diamond 278, or gold 285 pickaxe is taken from a crafting output slot. | Time to Mine! |
| <img src="img/icon_256x/cookFish.png" width="128" alt="Delicious Fish"> | 11 | `cookFish` | Delicious Fish | Catch and cook a fish | Unlocks when item ID 350 (cooked fish) is taken from a furnace result slot. | Hot Topic |
| <img src="img/icon_256x/onARail.png" width="128" alt="On A Rail"> | 12 | `onARail` | On A Rail | Travel by minecart at least 1 km (1,000 m) from the boarding point | Records the boarding position and uses 3D straight-line distance: `dx² + dy² + dz² >= 1000000`, or 1,000 m. Changing worlds resets the starting position. | Acquire Hardware |
| <img src="img/icon_256x/buildSword.png" width="128" alt="Time to Strike!"> | 13 | `buildSword` | Time to Strike! | Craft a sword from planks and sticks | Unlocks when any sword is taken from a crafting output slot: iron 267, wood 268, stone 272, diamond 276, or gold 283. | Benchmarking |
| <img src="img/icon_256x/killEnemy.png" width="128" alt="Monster Hunter"> | 14 | `killEnemy` | Monster Hunter | Attack and kill a hostile mob | Unlocks when a hostile mob damaged by the player dies within 30 seconds. | Time to Strike! |
| <img src="img/icon_256x/killCow.png" width="128" alt="Cow Tipper"> | 15 | `killCow` | Cow Tipper | Obtain leather | Unlocks when the player picks up item ID 334 (leather). | Time to Strike! |
| <img src="img/icon_256x/flyPig.png" width="128" alt="When Pigs Fly"> | 16 | `flyPig` | When Pigs Fly | Ride a pig off a cliff | Unlocks on fall damage greater than 2, corresponding to a fall of more than 5 blocks, when the damaged entity is a pig carrying a player. | Cow Tipper |

Normal gameplay requires parent achievements. `/ach grant` automatically grants any missing parents. Admin grants, revokes, and resets invalidate the current run for leaderboard eligibility.

## Compatibility

| Component | Requirements and Compatibility |
| --- | --- |
| Server | Project Poseidon based on Minecraft Beta 1.7.3 |
| Java | Compiled for Java 8 |
| Storage | SQLite (default) or MySQL/MariaDB |
| Build dependency | `com.legacyminecraft.poseidon:poseidon-craftbukkit:1.1.8` (`provided`) |

## Configuration

The configuration file is `plugins/BetaAchievement/config.yml`. The complete configuration is shown below:

```yaml
# Restart the server after changing this file.
# Back up achievements.db or the MySQL database before upgrading.

database:
  # Enable persistent storage.
  # When false, achievements are kept in memory and lost on restart.
  enabled: true

  # Storage type: sqlite or mysql.
  # mysql uses the MySQL/MariaDB connection settings below.
  type: sqlite

  # SQLite database file, relative to plugins/BetaAchievement/.
  file: achievements.db

  # The following five settings are used only when type is mysql.
  host: 127.0.0.1
  port: 3306
  database: minecraft
  username: minecraft
  password: change_me

  # Table prefix; letters, digits, and underscores only.
  table-prefix: beta_achievements_

runs:
  # Maximum active time for run 2 and later, in hours.
  # Expired active runs and their run_progress rows are deleted.
  # Run 1, READY runs, and COMPLETED runs are never deleted.
  # Set to 0 to disable the timeout.
  repeat-timeout-hours: 12

announce:
  # Broadcast unlock messages to all players.
  # When false, only the unlocking player receives the message.
  broadcast: true

  # Also write unlock messages to the server console.
  console: true

messages:
  # In-game unlock message.
  # Supports & color codes, {player}, and the English {achievement}.
  achievement-unlocked: '&6Achievement Get! &f{player} &eunlocked &f{achievement}'

  # Console unlock message.
  # {player} is the player name.
  achievement-unlocked-console: '[成就] {player} 获得了成就「{achievement}」'

  # Message shown when a player lacks permission.
  no-permission: '&cYou don''t have permission.'
```

## Achievement Runs

The first time a player joins, they receive run 1 in the `READY` state. When the player unlocks their first achievement, normally `openInventory`, the run changes to `ACTIVE` and starts timing. The total time is saved after all 16 achievements are completed.

After completing a run, use `/ach run next` to create the next run:

- Completed runs and manually abandoned runs remain in history. Active runs numbered 2 or later are deleted under the timeout rule if they are not completed within 12 hours.
- A new run starts at 0/16.
- Every run still starts timing when its first achievement is completed.
- Timing uses real server time and continues while the player is offline or the server is stopped.
- Only players who have completed at least one 16/16 run can use `/ach run next` and `/ach run abandon`.
- A `READY` run cannot be abandoned. An active run numbered 2 or later is deleted if it remains incomplete after 12 hours.

The leaderboard includes only `COMPLETED` runs, sorts them by total duration, and keeps each player's fastest run. Admin grants, revokes, and resets close the current run and remove it from leaderboard eligibility. Debug mode never writes data.

## Commands

`/ach` is the only alias for `/achievement`. Every `/ach` command in the tables below is equivalent to `/achievement`.

### Player Commands

| Command | Description |
|---|---|
| `/ach` | View your progress in the current run |
| `/ach run info` | View elapsed time and all splits for the current run |
| `/ach run next` | Start another run after completing all achievements |
| `/ach run abandon` | Abandon an active run numbered 2 or later |
| `/ach history` | View your 10 most recent runs |
| `/ach pb` | View your personal best |
| `/ach top [count]` | View the server leaderboard, with at most 25 entries |

### Administrator Commands

| Command | Description | Permission |
|---|---|---|
| `/ach check <player>` | View an online player's achievement progress | `betaachievement.view.others` |
| `/ach pb <player>` | View an online player's personal best | `betaachievement.view.others` |
| `/ach grant <player> <achievement-id>` | Grant one achievement and its parents; invalidates the current run | `betaachievement.grant` |
| `/ach grantall <player>` | Grant all 16 achievements; invalidates the current run | `betaachievement.grantall` |
| `/ach revoke <player> <achievement-id>` | Revoke one achievement; invalidates the current run | `betaachievement.revoke` |
| `/ach reset <player>` | Invalidate the current run and create a new `READY` run | `betaachievement.reset` |
| `/ach debug <achievement-id>` | Enable achievement debug mode | `betaachievement.debug` |
| `/ach debug` | Disable debug mode | `betaachievement.debug` |

The target player must be online. `betaachievement.admin` can replace all of the specific permissions above. If a player's data is still loading, administrative actions are queued and run in order after loading finishes.

Debug mode applies only to the administrator who ran the command. It ignores parent requirements and reports detection information when triggered, but does not write to the database or change leaderboard data.

## Plugin API

Other plugins can obtain the API from the main plugin class:

```java
BetaAchievement beta = (BetaAchievement) getServer()
        .getPluginManager()
        .getPlugin("BetaAchievement");
BetaAchievementApi api = beta.getApi();
```

### Query Cached Progress

```java
AchievementProgress cached = api.getCachedProgress(player);
int completed = cached.getCompletedCount();
int total = cached.getTotalCount();
int percent = cached.getPercent();
```

`getCachedProgress(UUID)` can be used with an offline UUID, but offline players are usually absent from the memory cache and return empty progress. Cached and asynchronous progress queries refer to the player's latest run. Read the run tables directly or use the run query API when historical runs are needed.

### Query Persistent Progress Asynchronously

```java
api.queryProgress(player.getUniqueId(), new AchievementQueryCallback() {
    @Override
    public void onResult(AchievementProgress progress) {
        player.sendMessage("Progress: " + progress.getCompletedCount()
                + "/" + progress.getTotalCount());
    }

    @Override
    public void onFailure(Exception exception) {
        getLogger().warning("Could not read achievements: " + exception.getMessage());
    }
});
```

Queries read the database on an asynchronous thread. Callbacks always return to the Bukkit main thread.

### Query Full-Completion Rank

```java
api.queryCompletionRankByName(name, new CompletionRankCallback() {
    @Override
    public void onResult(CompletionStatus status) {
        if (status.isComplete()) {
            getLogger().info(name + " was player #" + status.getRank()
                    + " to unlock every achievement");
        }
    }

    @Override
    public void onFailure(Exception exception) {
        getLogger().warning("Completion query failed: " + exception.getMessage());
    }
});
```

Use `queryCompletionRank(UUID, ...)`, `queryCompletionRank(Player, ...)`, or `queryCompletionRankByName(String, ...)`. Ranks start at 1, and `getRank()` returns `0` until the player has completed every achievement. Name lookup checks online players first and then saved historical names. Unknown names are returned through `onFailure`.

### Run Data API

```java
AchievementRun current = api.getCachedRun(player);
api.queryPersonalBest(player.getUniqueId(), personalBestCallback);
api.queryLeaderboard(10, leaderboardCallback);
api.queryRunHistory(player.getUniqueId(), 10, historyCallback);
api.queryRunSplits(runId, splitsCallback);
```

These methods use `RunQueryCallback<T>` and return callbacks to the server main thread after the database query finishes. `RunProgressEntry` provides both `getElapsedMs()` and `getSplitMs()`.

Public API classes are under `dev.che1s.betaachievement.api`:

- `BetaAchievementApi`
- `AchievementProgress`
- `AchievementQueryCallback`
- `CompletionStatus`
- `CompletionRankCallback`

Run data classes are under `dev.che1s.betaachievement.run`: `AchievementRun`, `RunProgressEntry`, `RunLeaderboardEntry`, `RunStatus`, and `RunQueryCallback`.

## Sponsorship and Support

If this project is useful to you, you can support development through Ko-fi or Afdian. Sponsors can receive help with installation, configuration, and troubleshooting.

<p align="center">
  <a href='https://ko-fi.com/J5C529ZWWL' target='_blank'><img height='36' style='border:0px;height:36px;' src='https://storage.ko-fi.com/cdn/kofi6.png?v=6' border='0' alt='Buy Me a Coffee at ko-fi.com' /></a>
  <a href='https://afdian.com/a/sss000' target='_blank'><img height='36' style='border:0px;height:36px;' src='https://img.shields.io/badge/Afdian-Support-946CE6?style=for-the-badge&logo=afdian&logoColor=white' border='0' alt='Support on Afdian' /></a>
</p>
