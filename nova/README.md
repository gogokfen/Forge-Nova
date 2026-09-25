# Forge Nova

A faster front end and engine tune-up for [Forge](https://github.com/Card-Forge/forge), built to stay smooth
when the battlefield is huge (Commander, token decks).

**Run it:** double-click `forge-nova.cmd` in the Forge folder. Java 17+ is required, as for Forge itself.
Classic Forge (`forge.cmd`) is unchanged and still works. Both share your decks, preferences and card-image cache.

## What changed and why

Forge has two parts: a rules engine (Java, ~150k lines plus ~34,000 card scripts) and a Swing desktop UI.
Profiling showed two separate bottlenecks:

| Problem | Cause | Fix |
|---|---|---|
| UI crawls with many cards | Swing draws every card on the CPU as its own widget (Forge even disables Direct3D) | New **WebGL2 renderer**: all cards live in one GPU texture array and the whole board is ~1 instanced draw call |
| AI turns take minutes on big boards | Rules queries rebuild ability lists on every call; combat AI loops scan the whole board for every attacker × blocker pair | **Engine patches**: epoch-keyed ability caches plus a static-ability index, verified against the original engine |

The engine stays in Java on purpose. The slowness came from the algorithms, not from Java, so porting it
would have kept the same problems and lost years of card support.

### Measured results (this PC)

| Scenario | Before | After |
|---|---|---|
| Drawing a 640-card board | Swing: CPU-bound | **0.8 ms/frame** (0.4 ms without relayout) |
| AI turn, 60 creatures per player | 182 s | **~36 s** (5×) |
| AI turn, 30 creatures per player | 11.8 s | **~5.5 s** (2.1×) |
| 24 AI-vs-AI Commander games (your decks) | 391 s | **298 s** (1.31×) |

Equivalence: 23/24 of those games produce byte-identical logs. The remaining one (Vazi vs Rocco, seed 303) also
gives different results between runs of the *unpatched* engine, because an AI tie-break depends on hash iteration order.
A 29-turn 4-player game run with `--verify-caches` reported zero mismatches.

## Layout

```
forge-nova.cmd          launcher (starts the engine host, opens an app window)
nova/
  host/src/             Java engine host: Forge's IGuiBase/IGuiGame implemented over WebSocket (Netty, already in Forge's jar)
    forge/nova/online/  online rooms: seats, invites, deck import, UPnP port mapping, Cloudflare link, card thumbnails
  client/               WebGL2 client, plain ES modules with no build step or npm packages
    js/gl/              instanced renderer, texture-array manager, SDF font, procedural card faces
    js/board/           layout (piles, rows, multiplayer strips), sprites/animation/picking, arrows
    js/ui/              lobby, online room, HUD, dialogs, zone viewers, options menu, audio
    js/builder/         deck builder: card catalog and search, deck model and checks, dialogs
  engine-patches/src/   optimised replacements for 57 Forge engine classes (put first on the classpath)
  bench/                difftest.py (behaviour-equivalence + speed), BigBoardBench.java
  tools/build.cmd       compiles the host + patches against the installed Forge jar
```

## Using it

- **Lobby:** pick a format (Commander, Constructed, Brawl, Oathbreaker, Tiny Leaders), the number of opponents (1–7)
  and everyone's starting life (the format's default unless you set one), then decks (yours, precons or generated)
  and AI profiles. "Watch AI vs AI" spectates. The setup is remembered.
- **Board:** identical permanents stack into piles with a ×N badge. **Shift+click** applies a click to the whole pile.
  Two layouts: **Rows** (opponents stacked above you; scroll over an opponent's strip to enlarge it) and **Table**
  (everyone seated around the table in turn order, 2×2 with four players).
- **Hand:** drag a card to rearrange your hand, or turn on *Auto-sort* in Options; its *Customize…* window sets the
  order: the card types from left to right (drag them, use the arrow keys, or ⇄ Flip) and the priority of the rules
  (card type, mana value, color, name), each one reversible or switchable off. A preview shows the result; dragging is
  off while auto-sort is on.
- **Options (Esc or ☰):** layout, side panel, fullscreen, performance stats, hand auto-sort, sound and music volume
  (applied live), concede, back to the main menu (ends the match right away). Esc also closes zone viewers you opened.
- **Keys:** Space/Enter = OK, Backspace = cancel/end turn, F2 = pass until end of turn, Ctrl+Z = undo, A = attack with all, Tab = side panel.
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
python nova\bench\difftest.py --verify-caches  # recompute every cached answer and report mismatches
java -cp "nova\lib\nova-engine-patches.jar;forge-gui-desktop-...jar;nova\bench\classes" BigBoardBench 60 2 2
```

Engine patches are tied to one Forge build. After updating Forge, run `build.cmd` again: it recompiles against
the new jar, and the launcher skips stale patches automatically. `-Dnova.disableCaches=true` turns the caches off for A/B runs.
The client can also be opened in any WebGL2 browser at the URL printed in `nova\logs\host.log`.

Forge Nova is a derivative of Forge and, like Forge, is licensed under the GPL-3.0.
