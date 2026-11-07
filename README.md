# Keeper

A caretaker for a Minecraft server that is meant to stay vanilla.

It changes no game mechanic. Not a drop, not a spawn, not a growth rate, not a recipe, not a
game rule. What it does is watch, report, and do the chores: say where the lag is actually
coming from, take a backup that will restore, note who has wandered off, and tell a player the
coordinate they just lost.

That restraint is the feature. A survival world is worth playing because its rules are the ones
everybody already knows, and the moment a server mod starts improving the *game*, nobody can
tell what is vanilla any more. So this one improves the *server*.

**Minecraft 1.21.11 / Fabric, server side.** Clients need nothing - they do not even know it is
there. It also runs in single player, inside the game's built-in server, which is the easy way to
try it before it goes on the real one.

---

## `/keeper lag`

The thing it is really for.

The server rules say to keep big farms away from spawn for performance. This is what makes that
rule checkable instead of a matter of opinion. It counts every entity in every dimension, per
chunk and per type, and names the worst offenders with coordinates you can fly to.

```
32.4 ms/tick over the last minute
overworld: 1847 entities, 1024 chunks loaded
the_nether: 212 entities, 196 chunks loaded
the_end: 8 entities, 64 chunks loaded
total entities: 2067
busiest chunks:
  614 entities at 13 -31 in overworld  (block 216 ~ -488)
  83 entities at -4 12 in overworld  (block -56 ~ 200)
most common entities:
  742 x minecraft:item
  310 x minecraft:zombie
(census took 7 ms, and only runs when asked)
```

Most server lag that is anybody's fault is a pile of entities in one chunk: items a hopper
cannot keep up with, a mob farm with no killing floor, a few hundred minecarts somebody forgot.
Six hundred entities in one chunk is not a mystery, it is an address.

It only runs when asked. A continuous census would be a sampling profiler that costs more than
the thing it watches, and on a server that is already behind, that is the last thing to add.

## Milliseconds, not TPS

`/keeper status` reports **ms per tick** over five seconds, a minute and five minutes.

A tick has 50 ms to finish. TPS stays pinned at 20 right up to the moment it falls off a cliff,
so it hides the whole interesting range between "fine" and "about to be a problem" - which is
exactly where a farm that is slowly getting worse lives. MSPT shows that range. TPS is printed
too, because it is what people ask for.

The measurement is free: the server already times every tick, so this keeps three running sums
and does one division when asked. It also writes a line to the server log when the one-minute
average crosses a threshold, at most once every few minutes.

## Backups that restore

Off, and switched on only by command. `/keeper backup on` says what it would do (how often,
where, how many it keeps), and `/keeper backup on confirm` switches it on. Both need operator
level 4, the server owner. From then on it takes a zip on a timer, without stopping the server.

The switch is not in keeper.json on purpose. A config file gets copied between servers, pasted
from a guide or edited by anyone with file access, and none of that should be enough to start
writing copies of the world to disk. The state lives in `keeper-backups.json` inside the world
folder, along with who turned it on and when, and `/keeper backup off` turns it off again. Until
it is on, `/keeper backup now` refuses too. keeper.json only says *how* backups run.

The sequence is the point:

1. Flush everything to disk on the main thread, and wait.
2. Set every world to stop saving, so nothing is written while the copy runs.
3. Zip the save directory on a low-priority worker thread, so the server keeps ticking.
4. Turn saving back on, whatever happened - including if the zip threw.

Step two is the one most hot-copy backups skip, and it is why their region files sometimes come
back torn: a chunk flushed halfway through being read copies as half of two different versions
of itself. With saving off there is nothing in flight.

The trade, stated plainly: during the copy the world is in memory and not being written, so a
crash mid-backup loses whatever happened since step one. That is a few minutes of play against
a backup that actually restores.

It also refuses to start while the server is already behind (`backupMaxMspt`), skips the
scheduled run when nobody has been on since the last one, refuses a `backupDir` inside the
world directory, and prunes to `backupKeep`.

## The small things

Each of these tells a player something the game already knew and did not mention.

- **Death coordinates**, privately, to the player who died.
- **Sleep count** when someone gets into bed, because otherwise somebody asks in chat.
- **Playtime** in a one-line greeting on join.
- **AFK**, announced when someone goes idle and when they come back. Idle means they have not
  moved or turned for `afkMinutes`.

The line here is easy to cross by accident, so: a death-coordinates message is information. A
one-click "take me back to where I died" would be a mechanic, and a fairly large one. It is not
here. The AFK tracking is only an announcement for the same reason - plenty of mods use AFK
status to move a player out of the mob spawning calculation, and whether an AFK farm should keep
running is not this mod's call to make.

## Commands

```
/keeper                 status: mspt, tps, players, chunks, backups
/keeper lag [n]         the entity census, with coordinates (5 rows, or n)
/keeper lag top [n]     the same, 10 rows by default
/keeper afk             who is idle and for how long
/keeper reload          re-read keeper.json          (admin)
/keeper backup          are backups on, and who switched them on (admin)
/keeper backup on       what switching them on will do (owner)
/keeper backup on confirm   switch them on           (owner)
/keeper backup off      switch them off              (admin)
/keeper backup now      take one immediately, if on  (admin)
/keeper backup list     what is in the backup dir    (admin)
```

The read-only parts are open to everyone, deliberately: a player who is being asked to move
their farm deserves to see the same numbers the admin saw.

## Config

`config/keeper.json`, written with defaults on first run. Everything is off or conservative to
begin with, because a server mod that surprises its admin is worse than no server mod.

| Key | Default | |
| --- | --- | --- |
| `warnMspt` | 45 | Log a warning above this one-minute average |
| `warnEverySeconds` | 300 | Rate limit for that warning |
| `announceAfk` | true | |
| `afkMinutes` | 8 | |
| `deathCoordinates` | true | |
| `sleepProgress` | true | |
| `joinPlaytime` | true | |
| `backupDir` | `backups` | Relative to the server directory |
| `backupEveryMinutes` | 180 | |
| `backupKeep` | 8 | |
| `skipBackupIfIdle` | true | |
| `backupMaxMspt` | 40 | Refuse to back up while the server is behind |

## With Lumi

Nothing to set up. [Lumi](https://github.com/omeldar/lumi) can already run a command and read
the answer, so an operator can ask a Claude session "is the server struggling, and where" and
it will run `/keeper lag` and read the census back. There is deliberately no HTTP endpoint here:
the diagnostics are a command, the command already has a permission model, and opening a second
door into a server to avoid typing six characters is not a trade worth making.

## Install

Fabric loader for 1.21.11 plus [Fabric API](https://modrinth.com/mod/fabric-api) on the server,
then drop the jar in the server's `mods/`. Nothing goes on the client.

`./gradlew build` to build it yourself.

## Licence

MIT. Not made or endorsed by Mojang or Microsoft.
