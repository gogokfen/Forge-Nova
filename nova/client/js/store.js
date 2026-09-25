// @ts-check
// Client-side mirror of the game, built from the host's diff messages.

import { on } from './net.js';

export const PHASES = [
  ['UNTAP', 'UT', 'Untap'], ['UPKEEP', 'UP', 'Upkeep'], ['DRAW', 'DR', 'Draw'], ['MAIN1', 'M1', 'Main 1'],
  ['COMBAT_BEGIN', 'BC', 'Beginning of combat'], ['COMBAT_DECLARE_ATTACKERS', 'DA', 'Declare attackers'],
  ['COMBAT_DECLARE_BLOCKERS', 'DB', 'Declare blockers'], ['COMBAT_FIRST_STRIKE_DAMAGE', 'FS', 'First strike damage'],
  ['COMBAT_DAMAGE', 'CD', 'Combat damage'], ['COMBAT_END', 'EC', 'End of combat'], ['MAIN2', 'M2', 'Main 2'],
  ['END_OF_TURN', 'ET', 'End step'], ['CLEANUP', 'CL', 'Cleanup'],
];
export const PHASE_INDEX = Object.fromEntries(PHASES.map((p, i) => [p[0], i]));

export const TF = { CREATURE: 1, LAND: 2, PW: 4, ARTIFACT: 8, ENCH: 16, INSTANT: 32, SORCERY: 64, BATTLE: 128, BASIC: 256, PLANE: 512 };

export const store = {
  /** @type {Map<number, any>} */ cards: new Map(),
  /** @type {Map<number, any>} */ players: new Map(),
  game: /** @type {any} */ ({}),
  stack: /** @type {any[]} */ ([]),
  combat: /** @type {any[]} */ ([]),
  log: /** @type {any[]} */ ([]),
  order: /** @type {number[]} */ ([]),
  local: /** @type {number[]} */ ([]),
  spectator: false,
  /** an online match (friends play along) */
  online: false,
  /** @type {Map<number, any>} online: connection status of the humans, by player id */
  peers: new Map(),
  prompt: /** @type {any} */ ({ msg: '', b1: { l: '', on: false }, b2: { l: '', on: false } }),
  sel: { ids: new Set(), min: 0, max: 0, hiC: new Set(), hiP: new Set(), weak: new Map() },
  yieldMarker: /** @type {any} */ (null),
  daytime: /** @type {string|null} */ (null),
  version: 0, // bumps on every change that affects the board
  inMatch: false,
  // derived
  /** @type {Map<number, number>} attacker -> defender id (player or card) */ attackTarget: new Map(),
  /** @type {Map<number, number>} blocker -> attacker */ blockerOf: new Map(),
  /** @type {Map<number, number[]>} card -> cards attached to it */ attachments: new Map(),
};

/** @type {Set<() => void>} */
const listeners = new Set();
export function subscribe(fn) { listeners.add(fn); return () => listeners.delete(fn); }
function changed(kind) {
  store.version++;
  listeners.forEach((fn) => { try { fn(kind); } catch (e) { console.error(e); } });
}

export function localPlayer() {
  return store.local.length ? store.players.get(store.local[0]) : null;
}
export function isLocal(pid) { return store.local.includes(pid); }

function rebuildDerived() {
  store.attackTarget.clear();
  store.blockerOf.clear();
  for (const c of store.combat) {
    store.attackTarget.set(c.a, c.dp ?? c.dc ?? -1);
    if (c.b) for (const b of c.b) store.blockerOf.set(b, c.a);
  }
  store.attachments.clear();
  for (const card of store.cards.values()) {
    if (card.at != null && card.z === 'Battlefield') {
      let list = store.attachments.get(card.at);
      if (!list) store.attachments.set(card.at, (list = []));
      list.push(card.id);
    }
  }
}

export function resetMatch() {
  store.cards.clear();
  store.players.clear();
  store.game = {};
  store.stack = [];
  store.combat = [];
  store.log = [];
  store.sel = { ids: new Set(), min: 0, max: 0, hiC: new Set(), hiP: new Set(), weak: new Map() };
  store.prompt = { msg: '', b1: { l: '', on: false }, b2: { l: '', on: false } };
  store.peers = new Map();
}

on('match', (m) => {
  if (store.game.id !== undefined && store.game.id !== m.gameId) resetMatch();
  store.order = m.order;
  store.local = m.local;
  store.spectator = !!m.spectator;
  store.online = !!m.online;
  store.inMatch = true;
  changed('match');
});

on('state', (m) => {
  if (m.full) {
    store.cards.clear();
    store.players.clear();
    store.log = [];
  }
  if (m.game) store.game = m.game;
  if (m.players) for (const p of m.players) store.players.set(p.id, p);
  if (m.cards) for (const c of m.cards) {
    const prev = store.cards.get(c.id);
    if (prev) c._prevZone = prev.z;
    store.cards.set(c.id, c);
  }
  if (m.gone) for (const id of m.gone) store.cards.delete(id);
  if (m.stack) store.stack = m.stack;
  if (m.combat) store.combat = m.combat;
  if (m.log) {
    store.log.push(...m.log);
    if (store.log.length > 1500) store.log.splice(0, store.log.length - 1200);
  }
  rebuildDerived();
  changed(m.log ? 'state+log' : 'state');
});

on('prompt', (m) => { store.prompt = m; changed('prompt'); });

on('sel', (m) => {
  store.sel = {
    ids: new Set(m.ids), min: m.min, max: m.max,
    hiC: new Set(m.hiC || []), hiP: new Set(m.hiP || []),
    weak: new Map(Object.entries(m.weak || {}).map(([k, v]) => [Number(k), v])),
  };
  changed('sel');
});

on('yield', (m) => { store.yieldMarker = m.marker || null; changed('yield'); });
on('peers', (m) => {
  store.peers = new Map((m.list || []).filter((p) => p.pid >= 0).map((p) => [p.pid, p]));
  changed('peers');
});
on('daytime', (m) => { store.daytime = m.v; changed('daytime'); });

/** Cards of a player in a zone, in zone order. */
export function zoneCards(p, zone) {
  const ids = p?.z?.[zone];
  if (!ids) return [];
  const out = [];
  for (const id of ids) {
    const c = store.cards.get(id);
    if (c) out.push(c);
  }
  return out;
}

export function zoneCount(p, zone) {
  const listed = p?.z?.[zone]?.length || 0;
  return listed + (p?.z?.[zone + '#'] || 0);
}

/** Human readable P/T string. */
export function ptOf(c) {
  if (c.pow === undefined) return '';
  return `${c.pow}/${c.tou}`;
}
