# Player Playback

A Fabric mod for filming big scripted Minecraft scenes (battles, chases, armies charging a base) without needing lots of people online.

Record a few players ("takes"), then play many takes back at once as **fake player actors**. One take can be cloned into a crowd of a hundred soldiers in a formation. Actors can wear any skin, use any name, or have their nametag hidden.

* **Two kinds of actors**, mixable within one scene:
  * **Real players**: real server-side fake players that act just like a real player.
    * Their **ender pearls really teleport them**, and their arrows, tridents and potions really hit.
    * Their recorded sword hits are **repeated for real** against whatever stands there, with the same attack strength and crits.
    * Players and mobs can hit them. They take **knockback**, then ease back onto their recorded path.
    * They can **die** (death messages and all), mobs target them, and they load chunks and spawn mobs around them like a player.
    * `/kill @a`, `/tp @p`, `@a[name=…]` and other player selectors find them too. They're kept out of the tab list and `/list`.
  * **Visual only** (the default): sent to clients as packets only. They aren't entities at all and cost almost nothing, so they're perfect for huge background crowds.
* **Server-side.** Viewers don't need the mod.
* **Optional client GUI.** Install the mod on your client too and press **P** to open the Player Playback screen. Everything in it can also be done with commands.
* **What gets recorded:**
  * movement, head and body rotation
  * sneaking, sprinting, swimming, elytra flying, riptide
  * arm swings, crits, eating/blocking/drawing a bow, hurt animations, totem pops, death
  * armor and held items, and every change to them
  * **projectiles**: ender pearls, arrows, tridents, snowballs, potions, wind charges, fireworks… Visual actors replay the exact flight path. Real actors launch real projectiles with the exact recorded position and velocity.
  * **melee attacks**: what was hit, the attack strength and whether it was a crit
  * **sounds** the player made: hits, pearl throws, eating, hurt…
  * optionally, **blocks** placed and broken. These are replayed and reverted when playback stops.
* **`/tick rate` safe.** Everything is timed with the real-time clock, not by counting ticks. A take recorded at `/tick rate 5` still plays back at the speed the player really moved. Playback also runs on its own 20 Hz thread, so actors keep moving under `/tick freeze`. Add `gameclock` to a play command if you *want* actors to slow down and speed up with the tick rate.

Supported Minecraft versions: **1.21.11, 26.1 / 26.1.1 / 26.1.2, 26.2, 26.3**. Each has its own jar, and each needs Fabric API.

## Quick start: a 100-player charge from 3 takes

```
/pb scene create charge
/pb record start knight1            # run at the base, swing your sword, throw a pearl...
/pb record stop
/pb record start knight2 scene charge   # records while knight1 plays, so you can act against it
/pb record stop                         # knight2 is added to the scene automatically
/pb record start archer scene charge
/pb record stop

/pb crowd add charge knight1 40 wedge 1.5
/pb crowd add charge knight2 40 grid 1.4
/pb crowd add charge archer 20 line 2
/pb crowd skins charge Notch jeb_ Dinnerbone Grumm   # round-robin skins
/pb crowd nametags charge hide
/pb scene mode charge real            # the whole army is real players...
/pb scene mode charge archer* visual  # ...except the archers, who are just for show
/pb scene play charge
```

Crowd copies get small random start delays, ±5% speed and slight position noise, so they don't move in perfect lockstep.

## Commands

`/playback` or `/pb` (needs permission level 2 / operator)

| Command | What it does |
|---|---|
| `record start <name> [player] [scene <scene>] [blocks]` | Start recording yourself (or `player`). `scene` plays that scene while you record and adds the new take to it. `blocks` also records block changes. |
| `record stop [player\|all]` | Stop and save. |
| `play <recording> [loop] [speed] [gameclock\|real]` | Play one recording. Add `real` to play it as a real fake player. |
| `stop [id\|all]`, `pause [id]`, `resume [id]` | Control what's playing (`list` shows ids). |
| `list`, `delete <rec>`, `rename <rec> <new>` | Manage recordings. |
| `actor <rec> name <name>\|reset` | Default actor name (max 16 chars). |
| `actor <rec> skin player <username>` | Use any Minecraft account's skin. |
| `actor <rec> skin copy <online player>` / `skin original` | Copy a skin from an online player, or go back to the recorded one. |
| `actor <rec> nametag show\|hide\|reset` | Show or hide the nametag. |
| `scene create\|delete\|info\|clear <scene>`, `scene list` | Manage scenes. |
| `scene add <scene> <rec> [delaySeconds]` | Add a take (can be added many times). |
| `scene remove <scene> <entry>` | Remove one actor (`take1+3`), or `take1*` for all copies. |
| `scene delay <scene> <entry> <seconds>` | Change when an actor starts. |
| `scene offset <scene> <entry> <dx> <dy> <dz>` | Move an actor relative to where it was recorded. |
| `scene actor <scene> <entry> name\|skin\|nametag …` | Appearance for one actor in one scene. |
| `scene mode <scene> real\|visual` | Make a scene's actors real fake players, or visual only. |
| `scene mode <scene> <entry> real\|visual\|default` | Override one actor (`take1+3`), every copy of a take (`take1*`), or everyone (`*`). |
| `scene play <scene> [loop] [speed] [gameclock]`, `scene stop <scene>` | Play/stop a whole scene. |
| `crowd add <scene> <rec> <count> [grid\|line\|column\|wedge\|circle\|random] [spacing] [jitterSeconds]` | Clone a take into a formation. Formations face the direction the take started in. |
| `crowd skins <scene> <user1> <user2> …` | Give every actor in the scene a skin from the list. |
| `crowd names <scene> <prefix>\|reset` | Name everyone `prefix1`, `prefix2`, … |
| `crowd nametags <scene> hide\|show\|reset` | Hide or show every nametag in the scene. |

Scene actors are identified by entry ids: the first copy of `take1` is `take1`, and later copies are `take1+2`, `take1+3`, …

## Files

Everything is stored in the world folder under `playerplayback/`:
* `recordings/*.pprec`: compact gzip'd binary takes
* `scenes/*.json`: hand-editable scenes
* `appearances.json`: per-recording default name, skin and nametag

## Building

The project uses [Stonecutter](https://stonecutter.kikugie.dev/) to build every Minecraft version from one codebase. Gradle needs **JDK 25** (1.21.11 is compiled with JDK 21 through toolchains).

```bash
./gradlew test buildAndCollect
```

Jars end up in `build/libs/<mod version>/`. GitHub Actions (`.github/workflows/build.yml`) builds and tests every version on each push. Pushing a `v*` tag publishes a release with the jars.

## Same result every time

Replays are built to come out the same every time:
* projectiles launch with the exact recorded position, velocity and rotation
* melee hits repeat with the recorded attack strength and crit state
* sounds replay with their recorded random seeds
* knockback only moves an actor off its path for a moment, so the scene stays on script

What can't be made identical is the rest of the world's randomness: mob AI, natural mob spawning, endermites from pearls, damage spread from armor enchantments, and so on. Minecraft shares one random generator across everything in a world, so as soon as anything differs (a viewer walking around, a chunk loading in a different order), those sequences diverge. For fully repeatable shots, control the area: peaceful mode or `doMobSpawning false`, and no stray mobs.

## Notes and limitations

* An actor stays in the dimension where its take started. If the recorded player goes through a portal, the actor won't follow.
* Visual-only actors can't be hit and their pearls are just for show (they still end up where the pearl landed, because they follow the recorded path).
* Real actors are survival-mode players. They drop their items when they die, like a real player (use `/gamerule keepInventory true` to avoid piles of loot). A killed actor stays dead until the scene loops or is played again.
* Real actors are updated every server tick, so at a very low `/tick rate` they move in bigger steps, just like real players would. Visual actors always update 20 times a second.
* Skins from `skin player` are fetched from Mojang's API, so the server needs internet access for that command.
