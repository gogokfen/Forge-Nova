# Forge Nova

A faster front end and engine tune-up for [Forge](https://github.com/Card-Forge/forge), built to stay smooth
when the battlefield is huge (Commander, token decks).

**Players:** download `ForgeNova.exe` from the latest release and run it. It installs Java, the exact Forge build
Nova needs and Nova itself into `%LOCALAPPDATA%\Forge Nova` (with Desktop and Start-menu shortcuts), updates them on
every start, then opens the game. No administrator rights needed; if a check or download fails it plays the installed
version, and `launcher.log` in that folder says what happened.

**From a Forge folder:** double-click `forge-nova.cmd`. Java 21+ is required. Classic Forge (`forge.cmd`) is unchanged
and still works. Both share your decks, preferences and card-image cache (`%APPDATA%\Forge`).

## What changed and why

Forge has two parts: a rules engine (Java, ~150k lines plus ~34,000 card scripts) and a Swing desktop UI.
Profiling showed two separate bottlenecks:

| Problem | Cause | Fix |
|---|---|---|
| UI crawls with many cards | Swing draws every card on the CPU as its own widget (Forge even disables Direct3D) | New **WebGL2 renderer**: all cards live in one GPU texture array and the whole board is ~1 instanced draw call |
| AI turns take minutes on big boards | Rules queries rebuild ability lists on every call; the AI re-runs the whole static-ability layer system twice for every spell it considers; combat AI loops scan the whole board for every attacker × blocker pair | **Engine patches**: epoch-keyed ability caches, a static-ability index, cheaper layer passes (per-pass memo of affected cards and counts, per-zone summaries) and indexed damage-prevention lookups, all verified move-for-move against the original engine |

The engine stays in Java on purpose. The slowness came from the algorithms, not from Java, so porting it
would have kept the same problems and lost years of card support.

### Measured results (this PC)

| Scenario | Before | After |
|---|---|---|
| Drawing a 640-card board | Swing: CPU-bound | **0.8 ms/frame** (0.4 ms without relayout) |
| AI turn, 60 creatures per player | 182 s | **~36 s** (5×) |
| AI turn, 30 creatures per player | 11.8 s | **~5.5 s** (2.1×) |
| 24 AI-vs-AI Commander games (your decks) | 391 s | **298 s** (1.31×) |
| 8 four-player AI Commander games (your decks, 2026-09 patches) | 2639 s | **646 s** (4.1×) |
| 32 seeded AI Commander games, 16 four-player + 16 two-player (your decks, 2026-09-26) | 9181 s | **1059 s** (8.7×) |
| Heaviest of those: Vazi / Rocco / Mirmoldo / Bananas Akibo, 46 turns | 1361 s | **142 s** (9.6×) |

Equivalence: 23/24 of those games produce byte-identical logs. The remaining one (Vazi vs Rocco, seed 303) also
gives different results between runs of the *unpatched* engine, because an AI tie-break depends on hash iteration order.
A 29-turn 4-player game run with `--verify-caches` reported zero mismatches.
The 32-game set (`novadiff.py --deterministic`) plays 31 games byte-identically; in the 32nd the original engine hits
the one-hour clock, and its 1498 played lines are an exact prefix of the patched game.

## Layout

```
forge-nova.cmd          starts the engine host and opens an app window (ForgeNova.exe runs it hidden)
forge-nova-dev.cmd      developer launcher, untracked (see Development)
nova/
  VERSION               the next release's version (read by tools/release.ps1)
  launcher/             ForgeNova.exe: one-click install/update (C# WinForms, .NET Framework 4.5+), icon, build.cmd
  host/src/             Java engine host: Forge's IGuiBase/IGuiGame implemented over WebSocket (Netty, already in Forge's jar)
    forge/nova/online/  online rooms: seats, invites, deck import, UPnP port mapping, Cloudflare link, card thumbnails
    forge/nova/moxfield/ Moxfield sync: Moxfield's web API, deck comparison, writing Forge deck files
    forge/nova/discord/ Discord Rich Presence (Discord's local IPC: named pipe / Unix socket)
  client/               WebGL2 client, plain ES modules with no build step or npm packages
    js/gl/              instanced renderer, texture-array manager, SDF font, procedural card faces
    js/board/           layout (piles, rows, multiplayer strips), sprites/animation/picking, arrows
    js/ui/              lobby, online room, HUD, dialogs (scry, dice), zone viewers, achievements, options menu, audio
    js/builder/         deck builder: card catalog and search, deck model and checks, dialogs
  engine-patches/src/   replacements for 74 Forge engine classes (put first on the classpath): the speed-ups, plus
                        overflow-safe numbers and the house rules
  bench/                difftest.py (behaviour-equivalence + speed), BigBoardBench.java
  client/brand/         the Forge Nova icon (favicon, logo)
  tools/build.cmd       compiles the host + patches against the installed Forge jar
  tools/release.ps1     builds and publishes a GitHub release for the launcher
```

## Using it

- **Lobby:** pick a format (Commander, Constructed, Brawl, Oathbreaker, Tiny Leaders), the number of opponents (1–7)
  and everyone's starting life (the format's default unless you set one), then decks (yours, precons or generated)
  and AI profiles. "Watch AI vs AI" spectates. The setup is remembered.
  - **House rules** (under the game setup, and in an online room's settings): *Free mulligan on 0 or 7 lands*: the
    first time a player sends back a hand with no lands or with seven lands, that mulligan doesn't count (once per
    game, for every player, the AI included). The mulligan question says when it applies. Needs the engine patches.
  - **Achievements** (lobby → *Achievements*): classic Forge's trophy case for every collection (Constructed, Draft,
    Quest, planeswalker ultimates, challenges…), with each trophy's tiers, what they take and your best so far.
    Trophy pictures come from Forge's own download server (cached like classic Forge's; card art when unavailable).
- **Board:** identical permanents stack into piles with a ×N badge. **Shift+click** applies a click to the whole pile.
  Equipment and auras stand side by side behind the card they're attached to, each showing a strip you can point at
  or click. Two layouts: **Rows** (opponents stacked above you; scroll over an opponent's strip to enlarge it) and
  **Table** (everyone seated around the table in turn order, 2×2 with four players). Over a card whose text doesn't
  fit the side panel, the mouse wheel scrolls that text instead (no need to steer the pointer past other cards).
- **Command zone:** your commander waits in a purple *Command zone* tray left of your hand (click it to cast it; it
  glows while you can, and shows the commander tax). Everyone else's command zone (and your emblems and effects) is
  a *CMD* pile next to their library; click it to see it all.
- **Zone windows:** when a card makes you choose among cards in several zones at once (every graveyard, say), they
  share one window with a section per zone, the ones you can choose from first. Your own library's window has
  **Cards left in library**: your deck minus every card of yours you can see elsewhere (hand, battlefield, graveyard,
  exile, command zone, stack), grouped by type, with the land count and the odds that the next card is a land. Cards
  of yours hidden elsewhere (face down in exile) are counted in, since you can't tell them apart either.
- **Scry and surveil:** a window with only the cards you look at: click a card to move it between *Top of library* and
  *Bottom of library* (or *Graveyard*), drag them (or use ◀ ▶) to set the order; each card says where it will end up.
  Scrying one card is a single click (*Keep on top* / *Bottom*).
- **Dice:** a die roll shows the right die (d4, d6, d8, d10, d12, d20, or Planechase's planar die) tumbling onto the
  table and landing on the result; click it to skip. Options → *Dice animation* turns it off (just the result).
- **Invalid attacks:** instead of Forge's "Attack declaration invalid", the window says why: the rule a creature
  breaks ("can't attack alone", "no more than one creature can attack each combat"…), the creatures that must attack
  (goaded, "attacks each combat if able", "must attack that player"…) with the cards that make them, and an attack
  Forge would accept.
- **Huge numbers:** power, toughness, counters, damage and life stop at Forge's largest number (2,147,483,647) instead
  of wrapping around. A power doubled past it used to become 0 or negative and deal no damage.
- **Playable cards:** when you get priority, the cards you can cast or play right now glow: spells you have the mana
  for, lands while you may still play one, abilities you can activate. Forge's rules engine decides (timing, land drops,
  costs, targets). Switch it off in Options → *Highlight playable cards*.
- **Hand:** drag a card to rearrange your hand, or turn on *Auto-sort* in Options; its *Customize…* window sets the
  order: the card types from left to right (drag them, use the arrow keys, or ⇄ Flip) and the priority of the rules
  (card type, mana value, color, name), each one reversible or switchable off. A preview shows the result; dragging is
  off while auto-sort is on.
- **Side panel:** hovering a card shows classic Forge's card details (its current abilities, including keywords and
  abilities other cards gave it, attachments, counters, choices) and *Effects from other cards*, each with its source.
  The game log follows the newest entries; scroll up to read and it stays put until you scroll back down, click
  *Latest*, or move the pointer away from it for a few seconds. With Forge's developer mode on (Options →
  *Developer mode*), a *Dev* tab next to the game log has classic Forge's dev buttons (games against the AI only).
- **Options (Esc or ☰):** layout, side panel, fullscreen, performance stats, hand auto-sort, playable-card highlights,
  dice animation, developer mode, sound and music volume (applied live), Discord, keyboard shortcuts, concede, back to
  the main menu (ends the match right away). Esc also closes zone viewers you opened.
- **Discord Rich Presence** (Options → *Discord*): your Discord status shows what you're doing in Nova: the menu, the
  deck builder, or a match with its format, your deck, your commander's art, your life total and the turn. Discord
  needs an application of yours to show it under: create one (free) at the Discord Developer Portal
  (discord.com/developers/applications, *New Application*, name it e.g. "Forge Nova"), copy its *Application ID* into
  Options and switch Rich Presence on. Discord's desktop app must be running; Nova connects whenever it is.
- **Choose X:** a small window with − / + (hold to repeat; or the arrow keys, the mouse wheel, typed digits) and
  **Max**: the most X your mana can pay right now (untapped lands, mana abilities, floating mana, cost reductions and
  mana restrictions, the way Forge's *Auto* pay would pay), as in MTG Arena. Max only sets the number; OK casts. The
  same window answers Forge's other number questions ("how many times", "how many?").
- **Keys:** Space/Enter = OK, Backspace = cancel/end turn, F2 = pass until end of turn, Ctrl+Z = undo, A = attack with all,
  Tab = side panel. Change them in Options → *Keyboard shortcuts* (click a key, then press the new one; up to two keys
  per action). They work with any keyboard layout. While Forge asks something in a window, the OK key presses that
  window's highlighted button (not in its first third of a second, so a quick pass-priority press can't answer a
  question you haven't read).
- **Sound:** the same sound asked for several times at once (drawing three cards, a board wipe) plays once.
- **Deck Builder** (lobby → *Deck Builder*, or ✎ next to a player's deck): builds Commander, Constructed, Brawl,
  Oathbreaker and Tiny Leaders decks in the same folders classic Forge uses.
  - Catalog of every card Forge knows, with instant filters (colors, types, mana value, format legality, rarity, set,
    commander colors, commanders only, AI-friendly, paper only) and Scryfall-style search:
    `t:dragon o:"draw a card" mv<=3 c:rg id:esper pow>=5 r:mythic s:mh3 f:commander is:partner -t:land` (press **?**).
  - Click adds a card, Ctrl+click adds four, Shift+click adds to the sideboard, right-click for commander/partner,
    oathbreaker/signature spell and printings. Enter adds the top search result; Ctrl+Z / Ctrl+Y undo and redo.
  - Deck grouped by type, mana value or color (list or card images), with problem cards marked; choose the printing
    (set and art) of any card.
  - Checks from both Nova (size, singleton, color identity, banned, commander and partner rules) and Forge's own
    rules engine; statistics (mana curve, color symbols vs. land sources, types, opening-hand and land-drop odds).
  - Import (Arena, MTGO, Moxfield/Archidekt, Forge `.dck`), export, basic-land helper, sample hand, deck notes,
    Forge precons as templates. Unsaved work is kept if you leave the builder.
- **Moxfield sync** (lobby → *Moxfield*, or *Moxfield…* in the deck pickers and the builder's *Open*): type your
  Moxfield user name once. Your Moxfield decks are listed next to Forge's copies (*New*, *Changed on Moxfield*,
  *Edited in Forge*, *Up to date*) with each deck's card changes. **Sync all** adds the new decks and updates the
  changed ones; with **Sync automatically** on, Nova does that by itself when it starts and when you come back to
  the menu (a badge on the button counts decks still waiting).
  - Moxfield is where decks are edited: a sync copies Moxfield's cards into Forge's deck folders (the format picks the
    folder; deck notes and Forge-only settings stay). A deck you changed in Forge is never overwritten without asking.
  - Every public deck is listed, including the ones Moxfield marks as not legal (a plain Moxfield search leaves those
    out). Unlisted decks are added once with *Add decks by link…*; private decks can't be read by other apps (set them
    to Unlisted). Only decks that changed on Moxfield are downloaded again.
  - A synced deck keeps its Moxfield link in Forge's *Source URL*, so renames on either side are followed; the Deck
    Builder shows *Moxfield ↗* for it. Double-faced cards come in under Forge's names ("Front // Back" names from
    Moxfield load as unsupported cards in Forge), and commanders Forge can't use are flagged.
- Phase stops: click a phase in the bar to toggle a stop. Right-click it to pass priority until that phase.

## Playing online with friends

The rules engine runs on the host's computer; everyone else only exchanges small state updates with it, so
big boards stay as fast online as they are locally. **Friends don't need Forge**: they open the invite link in
Chrome, Edge or Firefox and get the same client.

**Host:** Lobby → **Play online** → *Host a game* (your name, how many friends and AI players; the format, starting
life and your deck come from the lobby) → **Open a room**. The room shows the invite links to send:

| Link | Works when |
|---|---|
| **Local network** | friends are on your Wi-Fi / home network |
| **Internet** | your router forwards the port (TCP, 36743 by default, Forge's own network port) to your computer. Nova asks the router to do that itself (UPnP, like classic Forge's lobby) and removes the forward again when the room closes |
| **Internet (Cloudflare link)** | always, even behind a provider's shared address (CGNAT) where port forwarding is impossible. Needs Cloudflare's free `cloudflared` (`winget install Cloudflare.cloudflared`, or `cloudflared.exe` in `nova\tools`); then *Create an internet link* appears in the room |
| **VPN** (Tailscale, ZeroTier, Radmin VPN, Hamachi) | everyone is in the same VPN; its address is listed automatically |

If Windows asks whether Java may accept connections, allow it (at least for private networks).

**Friends:** open the link, enter a name, choose a deck, click **Ready**. Deck choices:
- *Import a deck…*: paste a list from Moxfield, Archidekt, MTG Arena or MTGO, or a Forge `.dck` file, or upload
  `.dck` files (Forge keeps them in `%APPDATA%\Forge\decks`). Imported decks are remembered in that browser. Forge's
  own importer reads them; a commander in the sideboard or listed alone is found, otherwise Nova asks which card leads.
- the host's decks (if the host allows it: *Let friends use my decks*), Forge's precons, or a random deck.
- A friend who has Forge Nova can instead use *Play online → Join a game* in their own lobby: they play with their own
  deck folders and card images (the invite link then only carries the game).

**In the room** the host sets format, life and games per match, turns seats into friend or AI seats (with deck and
AI profile), kicks, and starts once everyone is ready. Everyone chats; the chat stays available during the match
(side panel). Each player sees only what their player may see (their own hand, their own library searches...).

**During the match:** a friend whose connection drops gets the same seat back by reloading the page; the others see
*OFFLINE* on that player and "Waiting for …" with a timer. Right-click the player as host for **Let the AI play for …**
or **Remove … from the game**. *Concede* concedes only your own player; the host's *End the match* (and anyone's
*Back to the room* on the game-over screen) returns everyone to the room.

**Security:** friends reach a separate listener that only serves the game client, card images and their own seat
(no deck files, preferences or other API of the host); the invite code is required for everything and lasts until
Nova is closed. Guests' games don't count for the host's Forge achievements.

## Development

```
nova\tools\build.cmd                           # rebuild host + engine patches
python nova\bench\difftest.py                  # original vs patched: identical logs? how much faster?
python nova\bench\novadiff.py --deterministic   # seeded Commander games (your decks): identical logs + speed
python nova\bench\difftest.py --verify-caches  # recompute every cached answer and report mismatches
java -cp "nova\lib\nova-engine-patches.jar;forge-gui-desktop-...jar;nova\bench\classes" BigBoardBench 60 2 2
```

Engine patches are tied to one Forge build. After updating Forge, run `build.cmd` again: it recompiles against
the new jar, and the launcher skips stale patches automatically. `-Dnova.disableCaches=true` turns the caches off for A/B runs.
The client can also be opened in any WebGL2 browser at the URL printed in `nova\logs\host.log`.

### Developer launcher vs. the player launcher

`forge-nova-dev.cmd` (root of this checkout, ignored by git) runs the working copy as it is: it shows the branch and
the number of uncommitted files, offers `build.cmd` when `nova\host\src` or `nova\engine-patches\src` is newer than
its jar, and never downloads anything. `ForgeNova.exe` never writes into a git checkout: started from one it only
launches it, and otherwise it installs into its own folder (marked by `nova-install.json`).

### Releasing

```
winget install GitHub.cli    # once, then: gh auth login
```

1. Bump `nova\VERSION` (e.g. `0.2.0`), commit everything and `git push`. The script refuses uncommitted, untracked
   or unpushed work, so a release is always exactly a commit.
2. `powershell -ExecutionPolicy Bypass -File nova\tools\release.ps1`

It rebuilds the jars and `ForgeNova.exe`, uploads `ForgeNova-<version>.zip` (forge-nova.cmd + nova\client + nova\lib,
prebuilt, so players need no JDK), `ForgeNova.exe` and `nova-manifest.json` as release `v<version>` (marked latest),
and points the manifest at the current Temurin 21 JRE (Adoptium) and at the Forge build. The Forge build (jar + res,
~210 MB) is mirrored once per build as its own pre-release `forge-<version>-<build date>`, because Forge's snapshots
are replaced daily; changing Forge builds means re-verifying the engine patches first (difftest), then releasing.
Changing the launcher itself: bump `LauncherVersion` in `ForgeNova.cs`; installed launchers replace themselves.

`release.ps1 -LocalTest` builds the same files into `nova\dist` with a `file://` manifest and uploads nothing (it
keeps the current `nova\lib`); point a launcher at it with `set NOVA_MANIFEST_URL=file:///.../nova/dist/nova-manifest.json`
and run it from a folder containing an empty `nova-install.json`.

The launcher reads `https://github.com/gogokfen/Forge-Nova/releases/latest/download/nova-manifest.json`, so the
repository (or at least its releases) must be public for players to download.

Forge Nova is a derivative of Forge and, like Forge, is licensed under the GPL-3.0.
