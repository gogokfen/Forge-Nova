// @ts-check
// Forge Nova client bootstrap: connection, screens, frame loop, input.

import { connect, on, send, api, guestRoom, isServedGuest, leaveRoom, CLOSE } from './net.js';
import { store, subscribe, resetMatch, localPlayer } from './store.js';
import { Renderer } from './gl/renderer.js';
import { CardTextures } from './gl/textures.js';
import { SDFFont } from './gl/font.js';
import { Scene } from './board/scene.js';
import { Arrows } from './board/fx.js';
import { computeLayout } from './board/layout.js';
import { Hud } from './ui/hud.js';
import { Lobby } from './ui/lobby.js';
import { openZone, closeAllZones } from './ui/zones.js';
import { applyAudioPrefs, loadGuestAudioPrefs } from './ui/audio.js';
import { toast } from './ui/text.js';
import { closeAllWindows } from './ui/dialogs.js';
import { installEscapeKey, closeOptions, openOptions } from './ui/options.js';
import { settings, onSetting } from './ui/settings.js';
import { Builder } from './builder/builder.js';
import { RoomScreen, JoinScreen, openOnlineDialog, resetChat } from './ui/online.js';

const $ = (id) => /** @type {HTMLElement} */ (document.getElementById(id));
const boot = $('boot'), bootMsg = $('boot-msg');
const lobbyEl = $('lobby'), matchEl = $('match'), builderEl = $('builder'), roomEl = $('room'), joinEl = $('join');
const lobby = new Lobby(lobbyEl, () => {}, (deck) => showScreen('builder', deck), (l) => openOnlineDialog(l));
const room = new RoomScreen(roomEl, { onBuilder: (deck) => showScreen('builder', deck), onOptions: () => openOptions() });
const joinScreen = new JoinScreen(joinEl);
/** @type {Builder|null} created when first opened */
let builder = null;
/** an online room is open (we host it or sit in it): the room screen replaces the lobby */
let inRoom = false;
/** we left a friend's room on purpose (their host closes our connection) */
let leftRoom = false;

/** @type {any} */
let game = null; // lazily created match view
let screen = 'boot';

function showBoot(msg) {
  bootMsg.textContent = msg;
  boot.classList.remove('hidden');
}

/** The home screen: the room while one is open, else the lobby. */
const home = () => (inRoom ? 'room' : 'lobby');

function showScreen(name, arg = null) {
  screen = name;
  boot.classList.add('hidden');
  // Discord's "now playing" follows the menu and the deck builder (matches report themselves on the host)
  if (!isServedGuest() && name !== 'match' && name !== 'join') api('discord/screen', { screen: name }).catch(() => {});
  if (name !== 'builder') builder?.hide();
  if (name !== 'room') room.hide();
  if (name !== 'join') joinScreen.hide();
  if (name === 'builder') {
    closeOptions();
    lobby.hide();
    matchEl.classList.add('hidden');
    if (!builder) builder = new Builder(builderEl, { onBack: () => showScreen(home()) });
    builder.show(arg);
    return;
  }
  if (name === 'room' || name === 'join') {
    // nothing from the match may linger (game-over screen, zone viewers, open questions)
    closeOptions();
    closeAllZones();
    closeAllWindows();
    document.querySelectorAll('.popmenu').forEach((m) => m.remove());
    matchEl.classList.add('hidden');
    lobby.hide();
    store.inMatch = false;
    if (name === 'room') {
      if (builder?.decksChanged) { builder.decksChanged = false; room.decks = null; room.localDecks = null; lobby.data = null; }
      room.show();
    }
    return; // the join screen is shown by its own message
  }
  if (name === 'lobby') {
    // decks saved in the builder show up in the lobby's pickers
    if (builder?.decksChanged) {
      builder.decksChanged = false;
      lobby.data = null;
    }
    // nothing from the match may linger (game-over screen, zone viewers, open questions)
    closeOptions();
    closeAllZones();
    closeAllWindows();
    document.querySelectorAll('.popmenu').forEach((m) => m.remove());
    matchEl.classList.add('hidden');
    store.inMatch = false;
    lobby.show();
  } else if (name === 'match') {
    lobby.hide();
    closeAllWindows(); // the room's own windows (deck picker...) close when the game begins
    matchEl.classList.remove('hidden');
    ensureGame();
    game.relayout();
  }
}

// --------------------------------------------------------------------------------- match view

function ensureGame() {
  if (game) return;
  const canvas = /** @type {HTMLCanvasElement} */ ($('gl'));
  const fxCanvas = /** @type {HTMLCanvasElement} */ ($('fx'));
  let renderer;
  try {
    renderer = new Renderer(canvas);
  } catch (e) {
    showBoot('This browser cannot run WebGL2: ' + /** @type {Error} */ (e).message);
    throw e;
  }
  const g = {
    renderer,
    dirty: true,
    layoutDirty: true,
    hoverHand: /** @type {number|null} */ (null),
    /** @type {{id:number, x:number, y:number, index:number}|null} a hand card being dragged */
    handDrag: null,
    focus: /** @type {number|null} */ (null),
    last: performance.now(),
    frames: 0,
    fpsT: performance.now(),
    fps: 0,
    relayout() { this.layoutDirty = true; this.dirty = true; },
  };
  const textures = new CardTextures(renderer.gl, () => { g.dirty = true; });
  const font = new SDFFont(renderer.gl);
  const scene = new Scene(renderer, textures, font);
  const hud = new Hud($('hud'));
  hud.setSideVisible(settings.side);
  hud.onPanelResize = () => g.relayout();
  onSetting((key, v) => {
    if (key === 'side') hud.setSideVisible(v);
    if (key === 'perf') hud.setPerfVisible(v);
    if (key === 'side' || key === 'layout' || key === 'handSort' || key === 'handSortRules') g.relayout();
    if (key === 'playable') {
      if (store.inMatch) send({ t: 'uiPrefs', playable: !!v });
      g.dirty = true;
    }
  });
  // the host marks the cards we can play only if we want that
  on('match', () => send({ t: 'uiPrefs', playable: !!settings.playable }));
  /** layout inputs that come from the UI state rather than the game */
  const uiState = () => ({ w: renderer.width, h: renderer.height, sideW: settings.side ? 320 : 0, hoverHand: g.hoverHand, focus: g.focus,
    mode: settings.layout, handSort: settings.handSort, handRules: settings.handSortRules, handDrag: g.handDrag, panelH: hud.panelHeights });
  const arrows = new Arrows(fxCanvas, scene, (pid) => hud.panelCenter(pid));
  Object.assign(g, { textures, font, scene, hud, arrows });
  game = g;

  /**
   * Diagnostics: renders `n` frames synchronously (layout + animation + GPU submit + gl.finish)
   * and reports per-stage timings. Works even when the window is hidden (no rAF needed).
   */
  window.__novaBench = (n = 60, withLayout = true) => {
    const gl = renderer.gl;
    if (renderer.resize()) arrows.resize(renderer.width, renderer.height, renderer.dpr);
    const L = [], D = [], G = [];
    for (let i = 0; i < n; i++) {
      const t0 = performance.now();
      if (withLayout) {
        scene.setLayout(computeLayout(uiState()));
      }
      const t1 = performance.now();
      scene.update(1 / 60);
      renderer.begin();
      scene.draw();
      renderer.draw({ textures, fontTex: font.tex });
      const t2 = performance.now();
      gl.finish();
      const t3 = performance.now();
      L.push(t1 - t0); D.push(t2 - t1); G.push(t3 - t2);
    }
    const avg = (a) => +(a.reduce((x, y) => x + y, 0) / a.length).toFixed(3);
    const p95 = (a) => +[...a].sort((x, y) => x - y)[Math.floor(a.length * 0.95)].toFixed(3);
    return {
      frames: n, sprites: scene.sprites.size, quads: renderer.count, cards: store.cards.size,
      layoutMs: avg(L), drawCpuMs: avg(D), gpuFinishMs: avg(G),
      totalMs: avg(L.map((x, i) => x + D[i] + G[i])), p95TotalMs: p95(L.map((x, i) => x + D[i] + G[i])),
      canvas: [renderer.canvas.width, renderer.canvas.height], textures: `${textures.entries.size}/${textures.capacity}`,
    };
  };

  subscribe((kind) => {
    if (kind === 'prompt') { g.dirty = true; return; }
    g.relayout();
  });

  // ---- input
  let downOn = null;
  const toCanvas = (e) => {
    const r = canvas.getBoundingClientRect();
    return { x: e.clientX - r.left, y: e.clientY - r.top };
  };
  const pickAt = (e) => {
    const p = toCanvas(e);
    return scene.pick(p.x, p.y);
  };

  // Dragging a hand card rearranges the hand (unless auto-sort is on).
  /** @type {{id:number, x:number, y:number, pointerId:number}|null} */
  let press = null;
  let justDragged = false;
  let sortHintShown = false;
  const myHandIds = () => localPlayer()?.z?.Hand || [];
  /** drop position: how many of the other hand cards are left of the pointer */
  const dropIndex = (x, id) => {
    let i = 0;
    for (const t of scene.layout?.targets.values() || []) if (t.role === 'hand' && t.cardId !== id && (t.slotX ?? t.x) < x) i++;
    return i;
  };
  const endDrag = () => {
    const d = g.handDrag;
    if (!d) return;
    g.handDrag = null;
    justDragged = true;
    setTimeout(() => { justDragged = false; }, 0); // swallows the click that ends the drag
    const ids = myHandIds();
    const from = ids.indexOf(d.id);
    if (from >= 0 && from !== d.index) {
      // show the new order right away; the engine confirms it with the next update
      ids.splice(from, 1);
      ids.splice(Math.min(d.index, ids.length), 0, d.id);
      send({ t: 'reorderHand', id: d.id, index: d.index });
    }
    canvas.style.cursor = 'default';
    g.relayout();
  };
  canvas.addEventListener('pointerup', () => { press = null; endDrag(); });
  canvas.addEventListener('pointercancel', () => { press = null; endDrag(); });

  canvas.addEventListener('pointermove', (e) => {
    if (press && !g.handDrag) {
      const p = toCanvas(e);
      if (Math.hypot(p.x - press.x, p.y - press.y) > 8) {
        if (settings.handSort) {
          if (!sortHintShown) toast('Your hand is auto-sorted. Turn that off in Options (Esc) to arrange it by dragging, or change the sort order there.');
          sortHintShown = true;
          press = null;
        } else {
          g.handDrag = { id: press.id, x: p.x, y: p.y, index: Math.max(0, myHandIds().indexOf(press.id)) };
          try { canvas.setPointerCapture(press.pointerId); } catch { /* pointer already released */ }
          scene.hover = null;
          g.hoverHand = null;
        }
      }
    }
    if (g.handDrag) {
      const p = toCanvas(e);
      const d = g.handDrag;
      d.x = p.x;
      d.y = p.y;
      d.index = dropIndex(p.x, d.id);
      const s = scene.spriteOfCard(d.id);
      if (s) { s.x = p.x; s.y = p.y; } // the card sticks to the pointer; the others animate
      canvas.style.cursor = 'grabbing';
      g.relayout();
      return;
    }
    const s = pickAt(e);
    if (s !== scene.hover) {
      scene.hover = s;
      g.dirty = true;
      // hand cards and my commanders in their tray rise when pointed at
      const handId = s && (s.t.role === 'hand' || s.t.tray) ? s.t.cardId : null;
      if (handId !== g.hoverHand) { g.hoverHand = handId; g.relayout(); }
      if (s) {
        const card = s.t.cardId >= 0 ? store.cards.get(s.t.cardId) : null;
        if (card) hud.setDetail(card, s.t.stackItem);
        else if (s.t.stackItem) hud.setDetail({ id: -2, n: 'Ability', tx: s.t.stackItem.txt }, s.t.stackItem);
      }
    }
    canvas.style.cursor = s && s.t.interactive ? 'pointer' : 'default';
  });
  canvas.addEventListener('pointerleave', () => {
    if (scene.hover) { scene.hover = null; g.dirty = true; }
    if (g.hoverHand !== null) { g.hoverHand = null; g.relayout(); }
  });
  canvas.addEventListener('pointerdown', (e) => {
    downOn = pickAt(e);
    press = e.button === 0 && downOn?.t.role === 'hand' && !store.spectator
      ? { id: downOn.t.cardId, ...toCanvas(e), pointerId: e.pointerId } : null;
  });
  canvas.addEventListener('click', (e) => {
    if (justDragged) return;
    const s = pickAt(e);
    if (!s || s !== downOn) return;
    const t = s.t;
    const id = t.cardId;
    if (t.role === 'library') { openZone(t.owner, 'Library'); return; }
    if (t.role === 'zoneTop') {
      const sel = store.sel;
      // a pick from the library (a search) is made in the viewer, not by clicking whatever is on top
      const pickInViewer = t.zone === 'Library' && sel.ids.has(id);
      if (id >= 0 && !pickInViewer && (sel.ids.has(id) || sel.weak.has(id))) send({ t: 'card', id, btn: 1, x: e.clientX, y: e.clientY });
      else openZone(t.owner, t.zone);
      return;
    }
    if (id < 0) return;
    if (e.shiftKey && t.pile && t.pile.length > 1) {
      send({ t: 'cards', ids: t.pile });
      return;
    }
    send({ t: 'card', id, btn: 1, x: e.clientX, y: e.clientY });
  });
  canvas.addEventListener('contextmenu', (e) => {
    e.preventDefault();
    const s = pickAt(e);
    if (!s) return;
    if (s.t.role === 'zoneTop' || s.t.role === 'library') { openZone(s.t.owner, s.t.zone || 'Library'); return; }
    if (s.t.cardId >= 0) send({ t: 'card', id: s.t.cardId, btn: 3, x: e.clientX, y: e.clientY });
  });
  // mouse wheel over a card scrolls its text in the side panel (when it doesn't fit there); elsewhere over an
  // opponent strip it enlarges that strip (multiplayer focus, Rows layout)
  canvas.addEventListener('wheel', (e) => {
    const s = pickAt(e);
    if (s && (s.t.cardId >= 0 || s.t.stackItem) && hud.scrollDetail(e)) return;
    if (!scene.layout || store.order.length < 3 || settings.layout !== 'rows') return;
    const reg = scene.layout.hud.regions.find((r) => e.clientY >= r.y && e.clientY < r.y + r.h && e.clientX < r.x + r.w);
    if (!reg || reg.pid === store.local[0]) return;
    g.focus = e.deltaY < 0 ? reg.pid : null;
    g.relayout();
  }, { passive: true });
  window.addEventListener('resize', () => g.relayout());

  on('cardRejected', (m) => scene.shake(m.id));

  // ---- frame loop
  const frame = (now) => {
    requestAnimationFrame(frame);
    if (screen !== 'match') return;
    const dt = Math.min(0.1, (now - g.last) / 1000);
    g.last = now;
    if (renderer.resize()) {
      arrows.resize(renderer.width, renderer.height, renderer.dpr);
      g.layoutDirty = true;
    }
    const t0 = performance.now();
    if (g.layoutDirty) {
      g.layoutDirty = false;
      const layout = computeLayout(uiState());
      scene.setLayout(layout);
      hud.update(layout);
      hud.refreshDetail();
      g.dirty = true;
    }
    const moving = scene.update(dt);
    if (g.dirty || moving) {
      g.dirty = false;
      renderer.begin();
      scene.draw();
      renderer.draw({ textures, fontTex: font.tex });
      const topStack = store.stack[0];
      const ts = topStack ? scene.sprites.get(topStack.src != null && store.cards.get(topStack.src)?.z === 'Stack' && !topStack.ab ? 'c' + topStack.src : 's' + topStack.id) : null;
      arrows.draw(ts ? { x: ts.x, y: ts.y } : null);
      g.frames++;
    }
    const cpu = performance.now() - t0;
    if (now - g.fpsT > 500) {
      g.fps = Math.round((g.frames * 1000) / (now - g.fpsT));
      g.frames = 0;
      g.fpsT = now;
      hud.setPerf(`${g.fps} fps · ${scene.sprites.size} sprites · ${renderer.count} quads · frame ${cpu.toFixed(2)} ms · textures ${textures.entries.size}/${textures.capacity} (${textures.stats.loaded} loaded)`);
    }
  };
  requestAnimationFrame(frame);
}

// --------------------------------------------------------------------------------- connection

/** Tells a friend why they are no longer in the room; `retry` offers to join again. */
function showLeft(msg, retry = true) {
  inRoom = false;
  room.reset();
  resetChat();
  resetMatch();
  if (!isServedGuest()) {
    // our own Nova joined the room: back to its lobby
    leaveRoom(true);
    showScreen('lobby');
    toast(msg, 'error');
    return;
  }
  closeAllWindows();
  closeOptions();
  matchEl.classList.add('hidden');
  joinScreen.hide();
  showBoot(msg);
  boot.querySelector('.boot-bar')?.classList.add('hidden');
  const card = boot.querySelector('.boot-card');
  card?.querySelector('.boot-acts')?.remove();
  if (retry && card) {
    const acts = document.createElement('div');
    acts.className = 'boot-acts';
    acts.innerHTML = '<button class="btn primary">Join again</button>';
    acts.querySelector('button')?.addEventListener('click', () => location.reload());
    card.appendChild(acts);
  }
}

on('_close', (m) => {
  const r = guestRoom();
  if (!r) {
    if (m.code === CLOSE.REPLACED) { showBoot('This game is open in another window.'); return; }
    if (screen === 'match' || screen === 'lobby' || screen === 'builder' || screen === 'room') showBoot('Reconnecting to the Forge engine…');
    return;
  }
  // playing in a friend's room
  if (leftRoom) return;
  switch (m.code) {
    case CLOSE.REPLACED: showLeft('This game is open in another tab or window.'); return;
    case CLOSE.ROOM_CLOSED: showLeft('The host closed the room.'); return;
    case CLOSE.KICKED: showLeft('The host removed you from the room.'); return;
    case CLOSE.VERSION: showLeft('Your Forge Nova is a different version than your friend\'s. Open the invite link in a web browser (Chrome, Edge or Firefox) instead.', false); return;
    case CLOSE.FLOOD: showLeft('Disconnected: too many messages.'); return;
    case CLOSE.REJECTED: return; // a join error explains it
    default:
      if (screen !== 'boot' || inRoom) showBoot(`Connection to ${room.state?.hostName ? room.state.hostName + '\'s' : 'the'} game lost. Reconnecting…`);
  }
});

on('_localClose', () => {
  // our own Nova went away while we play in a friend's room: the game goes on, images may not load
  toast('Lost the connection to your own Forge Nova (card images and decks). Reconnecting…', 'error');
});

on('hello', (m) => {
  if (m.volSounds !== undefined) applyAudioPrefs({ sounds: m.sounds, music: m.music, volSounds: m.volSounds, volMusic: m.volMusic });
  if (m.error) { showBoot('The Forge engine failed to start: ' + m.error); return; }
  if (!m.ready) { showBoot('Loading 34,000 cards into the rules engine…'); return; }
  inRoom = !!m.room;
  if (m.inMatch) showScreen('match');
  else if (m.room) { if (screen !== 'builder') showScreen('room'); else boot.classList.add('hidden'); }
  else if (screen === 'lobby' || screen === 'builder') boot.classList.add('hidden'); // reconnected in place
  else {
    resetMatch();
    showScreen('lobby');
  }
});

on('localHello', (m) => {
  // our own Nova while we sit in a friend's room: only its audio settings matter
  if (m.volSounds !== undefined) applyAudioPrefs({ sounds: m.sounds, music: m.music, volSounds: m.volSounds, volMusic: m.volMusic });
});

let lastGameId = -1;
on('match', (m) => {
  if (screen !== 'match') showScreen('match');
  if (game && m.gameId !== lastGameId) game.hud.reset();
  lastGameId = m.gameId;
});

on('lobby', () => {
  resetMatch();
  showScreen(home());
});

// ---- online rooms

on('room', (m) => {
  const first = !inRoom;
  inRoom = true;
  room.setState(m);
  // the match is over (or we just arrived): everyone is back in the room
  if (m.state === 'lobby' && screen !== 'builder' && screen !== 'room') {
    if (screen === 'match') resetMatch();
    showScreen('room');
  } else if (first && screen === 'boot' && m.state === 'lobby') {
    showScreen('room');
  }
});

on('roomInfo', (m) => {
  // a friend's room we haven't taken a seat in yet
  if (inRoom) return;
  showScreen('join');
  joinScreen.show(m);
});

on('joinError', (m) => {
  if (screen === 'join') joinScreen.error(m.msg);
  else showLeft(m.msg);
});

on('roomClosed', (m) => {
  if (m.host) {
    // we closed our own room
    inRoom = false;
    room.reset();
    resetChat();
    resetMatch();
    showScreen('lobby');
    return;
  }
  showLeft(m.reason || 'The host closed the room.');
});

window.addEventListener('nova:joining', (e) => {
  leftRoom = false;
  const inv = /** @type {CustomEvent} */ (e).detail;
  showBoot(`Connecting to ${inv.base.replace(/^https?:\/\//, '')}…`);
});

window.addEventListener('nova:leftRoom', () => {
  leftRoom = true;
  if (isServedGuest()) showLeft('You left the room.');
  else { inRoom = false; room.reset(); resetChat(); resetMatch(); showScreen('lobby'); }
});

on('_roomLeft', () => {
  inRoom = false;
  room.reset();
  resetChat();
  resetMatch();
  if (screen !== 'lobby') showScreen('lobby');
});

// a Moxfield sync wrote deck files: lists show them, an unchanged copy open in the deck builder is reloaded
window.addEventListener('nova:decks-changed', (e) => {
  lobby.data = null;
  room.decks = null;
  room.localDecks = null;
  if (screen === 'lobby') lobby.show();
  else if (screen === 'room') room.show();
  builder?.onDecksChanged(/** @type {CustomEvent} */ (e).detail || []);
});

window.addEventListener('error', (e) => toast('Client error: ' + e.message, 'error'));
window.addEventListener('unhandledrejection', (e) => console.warn(e.reason));

installEscapeKey();
if (isServedGuest()) {
  loadGuestAudioPrefs();
  document.title = 'Forge Nova — online game';
  showBoot('Connecting to the game…');
} else {
  showBoot('Connecting to the Forge engine…');
}

// Online, the others wait for you: a background tab says so in its title until you look at it.
const baseTitle = document.title;
let pendingDialogs = 0;
const markTitle = () => {
  const pr = store.prompt;
  const wanted = store.inMatch && store.online && (pr?.b1?.on || pr?.b2?.on || pendingDialogs > 0);
  document.title = wanted && document.hidden ? '▶ Your move — Forge Nova' : baseTitle;
};
subscribe((kind) => { if (kind === 'prompt') markTitle(); });
on('dialog', () => { pendingDialogs++; markTitle(); });
on('dialogClose', () => { pendingDialogs = Math.max(0, pendingDialogs - 1); markTitle(); });
on('match', () => { pendingDialogs = 0; });
document.addEventListener('visibilitychange', () => { if (!document.hidden) document.title = baseTitle; });

connect();
