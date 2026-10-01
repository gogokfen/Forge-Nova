// @ts-check
// Computes where every visible card should be. Pure function of (store, viewport, ui state).

import { store, TF, zoneCards, zoneCount } from '../store.js';
import { sortHand } from './handsort.js';

export const CARD_RATIO = 0.7171; // width / height
const PANEL_W = 206;
const GAP = 0.06; // gap between items, in card heights

/**
 * @typedef {{key:string, cardId:number, x:number, y:number, w:number, h:number, rot:number, z:number,
 *   tex?:string, face?:any, pile?:number[], count?:number, zone?:string, owner?:number, interactive:boolean,
 *   role:string, alpha?:number, stackItem?:any, slotX?:number, tray?:boolean}} Target
 */

function isCreatureRow(c) { return (c.tf & TF.CREATURE) || (c.tf & TF.PW) || (c.tf & TF.BATTLE); }
function isLandRow(c) { return (c.tf & TF.LAND) && !(c.tf & TF.CREATURE); }

/**
 * Attached cards (equipment, auras...) sit side by side behind their host, each showing a strip this wide
 * (in card heights) to its right; with many of them the strips narrow so the group stays compact.
 */
export function attachStep(n) { return n <= 4 ? 0.2 : Math.max(0.1, 0.8 / n); }

export function textureKeyOf(c, ownerSleeve) {
  if (c.hid && !c.img) return 'sleeve:' + ownerSleeve;
  if (c.img && c.img !== 't:hidden') return c.img;
  if (c.img === 't:hidden') return 'sleeve:' + ownerSleeve;
  return 'proc:' + c.id;
}

function groupKey(c, sel) {
  if (c.at != null || c.atp != null) return null;
  if (store.attachments.has(c.id)) return null;
  if (c.atk || c.blk || c.cmd) return null;
  const cnt = c.cnt ? JSON.stringify(c.cnt) : '';
  return `${c.n}|${c.img}|${c.tap ? 1 : 0}|${c.sick ? 1 : 0}|${c.pow}|${c.tou}|${c.dmg || 0}|${cnt}|${c.tok ? 1 : 0}|${c.fd ? 1 : 0}|${c.ph ? 1 : 0}|${c.hid ? 1 : 0}|${sel.ids.has(c.id) ? 1 : 0}|${sel.hiC.has(c.id) ? 1 : 0}|${sel.weak.has(c.id) ? 1 : 0}|${c.ovl || ''}`;
}

/** Groups identical permanents into piles, preserving first-appearance order. */
function makeItems(cards, sel) {
  /** @type {Map<string, any>} */
  const byKey = new Map();
  const items = [];
  for (const c of cards) {
    const k = groupKey(c, sel);
    if (k !== null) {
      const it = byKey.get(k);
      if (it) { it.cards.push(c); continue; }
    }
    const it = { cards: [c], attach: store.attachments.get(c.id) || [] };
    items.push(it);
    if (k !== null) byKey.set(k, it);
  }
  for (const it of items) {
    const top = it.cards[0];
    const tapped = !!top.tap;
    const pileExtra = Math.min(it.cards.length - 1, 3) * 0.05;
    const attExtra = it.attach.length * attachStep(it.attach.length);
    it.fw = (tapped ? 1 : CARD_RATIO) + pileExtra + attExtra;
  }
  return items;
}

/** Splits a player's permanents into 1-3 rows depending on available height. */
function rowsFor(cards, nRows) {
  const creatures = [], others = [], lands = [];
  for (const c of cards) {
    if (isCreatureRow(c)) creatures.push(c);
    else if (isLandRow(c)) lands.push(c);
    else others.push(c);
  }
  lands.sort((a, b) => ((b.tf & TF.BASIC) - (a.tf & TF.BASIC)) || String(a.n).localeCompare(String(b.n)));
  if (nRows >= 3) return [creatures, others, lands];
  if (nRows === 2) return [creatures.concat(others.filter((c) => c.tf & TF.PW)), others.filter((c) => !(c.tf & TF.PW)).concat(lands)];
  return [creatures.concat(others, lands)];
}

/**
 * 'rows': every opponent gets a full-width strip above you (Forge's multiplayer layout);
 * a focused opponent's strip is enlarged.
 */
function rowRegions(me, others, playW, fieldH, focus) {
  const regions = [];
  if (others.length === 0) {
    regions.push({ pid: me, x: 0, y: 0, w: playW, h: fieldH, top: false });
  } else if (others.length === 1) {
    const oh = Math.round(fieldH * 0.48);
    regions.push({ pid: others[0], x: 0, y: 0, w: playW, h: oh, top: true });
    regions.push({ pid: me, x: 0, y: oh, w: playW, h: fieldH - oh, top: false });
  } else {
    const myH = Math.round(fieldH * (others.length >= 4 ? 0.36 : 0.40));
    const topH = fieldH - myH;
    const weights = others.map((id) => (focus === id ? 2.2 : 1));
    const sum = weights.reduce((a, b) => a + b, 0);
    let acc = 0;
    others.forEach((pid, i) => {
      const y0 = Math.round((topH * acc) / sum);
      acc += weights[i];
      const y1 = i === others.length - 1 ? topH : Math.round((topH * acc) / sum);
      regions.push({ pid, x: 0, y: y0, w: playW, h: y1 - y0, top: true });
    });
    regions.push({ pid: me, x: 0, y: topH, w: playW, h: myH, top: false });
  }
  return regions;
}

/**
 * 'table': players sit around a table, two rows of seats (2×2 with four players). Seats follow
 * turn order clockwise: from you (bottom left) up to the top-left seat, along the top row,
 * then back along the bottom row from the right.
 */
function tableRegions(me, others, playW, fieldH) {
  const n = others.length + 1;
  if (n < 2) return rowRegions(me, others, playW, fieldH, null);
  const topCount = n - Math.floor(n / 2);
  const top = others.slice(0, topCount);
  const bottom = [me, ...others.slice(topCount).reverse()];
  const topH = Math.round(fieldH / 2);
  const regions = [];
  const place = (pids, y, h, isTop) => pids.forEach((pid, i) => {
    const x0 = Math.round((playW * i) / pids.length), x1 = Math.round((playW * (i + 1)) / pids.length);
    regions.push({ pid, x: x0, y, w: x1 - x0, h, top: isTop });
  });
  place(top, 0, topH, true);
  place(bottom, topH, fieldH - topH, false);
  return regions;
}

/**
 * @param {{w:number,h:number, sideW:number, hoverHand:number|null, focus:number|null, mode?:string,
 *   handSort?:boolean, handRules?:any, handDrag?:{id:number, x:number, y:number, index:number}|null,
 *   panelH?:Map<number, number>}} ui panelH: the player panels' measured heights (they grow with mana, counters...)
 */
export function computeLayout(ui) {
  const vw = ui.w, vh = ui.h;
  /** @type {Map<string, Target>} */
  const targets = new Map();
  const decor = [];
  const hud = { regions: /** @type {any[]} */ ([]), phase: { x: 0, y: 0 }, prompt: { x: 0, y: 0, w: 300 }, stack: /** @type {any[]} */ ([]),
    hand: { x: 0, y: 0, w: 0, h: 0 }, fieldH: 0 };
  const order = store.order.filter((id) => store.players.has(id));
  if (!order.length) return { targets, decor, hud };

  const playW = vw - ui.sideW;
  const me = store.local.length ? store.local[0] : order[0];
  const hasHand = !store.spectator && store.local.length > 0;
  const handH = hasHand ? Math.max(120, Math.min(230, vh * 0.21)) : 0;
  const barH = hasHand ? 0 : 62; // spectators get a slim control bar instead of a hand
  const fieldH = vh - handH - barH;
  const others = order.filter((id) => id !== me);
  const sel = store.sel;
  let z = 0;
  /** my commanders in the command zone (shown in a tray left of my hand) */
  let myTray = /** @type {any[]} */ ([]);
  hud.fieldH = fieldH;

  // ---- regions
  const regions = ui.mode === 'table' ? tableRegions(me, others, playW, fieldH) : rowRegions(me, others, playW, fieldH, ui.focus);

  // the phase bar sits where my area meets the rest of the table
  const myRegion = regions.find((r) => r.pid === me) || regions[regions.length - 1];
  hud.phase = { x: playW / 2, y: myRegion.y };

  for (const R of regions) {
    const p = store.players.get(R.pid);
    if (!p) continue;
    const sleeve = p.sl ?? 0;
    const pad = 8;
    // the player panel sits in the corner of the region farthest from the table center: at the top of a
    // strip above me, at the bottom of mine (there it grows upward, never into the hand)
    const panelH = ui.panelH?.get(R.pid) || 104;
    const panelTop = R.top ? R.y + pad : R.y + R.h - pad - panelH;
    const panel = { x: R.x + pad, y: panelTop, h: panelH, anchor: R.top ? 'top' : 'bottom', edge: R.top ? R.y + pad : R.y + R.h - pad };
    const reg = { ...R, panel, piles: /** @type {any} */ ({}) };
    hud.regions.push(reg);

    const innerTop = R.y + (R.top ? 6 : 16); // leave room for the phase bar on my side
    const innerH = R.h - (R.top ? 12 : 22);
    const nRows = innerH >= 300 ? 3 : innerH >= 190 ? 2 : 1;
    const maxH = Math.min(vh * (others.length > 1 ? 0.2 : 0.23), 210);
    let h = Math.min(maxH, (innerH / nRows) * 0.9);

    // ---- the command zone: my commanders wait in a tray beside my hand; everything else there
    // (opponents' commanders, emblems, effects) is a pile next to the library
    const command = zoneCards(p, 'Command');
    const tray = R.pid === me && hasHand ? command.filter((c) => c.cmd) : [];
    if (tray.length) myTray = tray;
    const cmdPile = tray.length ? command.filter((c) => !c.cmd) : command;
    const showCmd = cmdPile.length > 0 || (!!store.game.cmdr && !(R.pid === me && hasHand));

    // ---- zone piles on the right (library, graveyard, exile, command zone): a row in a short strip, else a
    // column, or two columns when four piles in one would get too small
    const pileSlots = showCmd ? ['Library', 'Graveyard', 'Exile', 'Command'] : ['Library', 'Graveyard', 'Exile'];
    const nSlots = pileSlots.length;
    const cols = nRows > 1 && nSlots === 4 && innerH / 4 - 8 < 64 ? 2 : 1;
    const perCol = Math.ceil(nSlots / cols);
    const pileH = Math.max(46, Math.min(h * 0.8, nRows === 1 ? innerH * 0.8 : innerH / perCol - 8));
    const pileW = pileH * CARD_RATIO;
    const pilesAreaW = nRows === 1 ? nSlots * (pileW + 8) + 8 : cols * (pileW + 8) + 12;
    pileSlots.forEach((zone, i) => {
      let px, py;
      if (nRows === 1) {
        px = R.x + R.w - pilesAreaW + 8 + i * (pileW + 8) + pileW / 2;
        py = innerTop + innerH / 2;
      } else {
        const col = i % cols, row = Math.floor(i / cols);
        px = R.x + R.w - pilesAreaW + 10 + col * (pileW + 8) + pileW / 2;
        const slotH = innerH / perCol;
        const idx = R.top ? row : perCol - 1 - row; // library farthest from the center
        py = innerTop + slotH * idx + slotH / 2;
      }
      const n = zone === 'Command' ? cmdPile.length : zoneCount(p, zone);
      reg.piles[zone] = { x: px, y: py, w: pileW, h: pileH, count: n };
      decor.push({ type: 'slot', x: px, y: py, w: pileW, h: pileH, cmd: zone === 'Command',
        label: zone === 'Library' ? 'LIB' : zone === 'Graveyard' ? 'GY' : zone === 'Exile' ? 'EX' : 'CMD' });
      if (zone === 'Command') {
        if (n) {
          // a commander shows on top, else the newest emblem or effect
          const top = cmdPile.find((c) => c.cmd) || cmdPile[n - 1];
          targets.set('c' + top.id, { key: 'c' + top.id, cardId: top.id, x: px, y: py, w: pileW, h: pileH, rot: 0, z: z++,
            tex: textureKeyOf(top, sleeve), face: top, interactive: true, role: 'zoneTop', zone, owner: R.pid, count: n });
        }
      } else if (zone === 'Library') {
        if (n > 0) {
          targets.set('lib' + R.pid, { key: 'lib' + R.pid, cardId: -1, x: px, y: py, w: pileW, h: pileH, rot: 0, z: z++,
            tex: 'sleeve:' + sleeve, interactive: true, role: 'library', owner: R.pid, count: n });
          if (p.libTop != null) {
            const top = store.cards.get(p.libTop);
            if (top) targets.set('c' + top.id, { key: 'c' + top.id, cardId: top.id, x: px, y: py, w: pileW, h: pileH, rot: 0, z: z++,
              tex: textureKeyOf(top, sleeve), face: top, interactive: true, role: 'zoneTop', zone, owner: R.pid, count: n });
          }
        }
      } else {
        const zc = zoneCards(p, zone);
        if (zc.length) {
          const top = zc[zc.length - 1];
          targets.set('c' + top.id, { key: 'c' + top.id, cardId: top.id, x: px, y: py, w: pileW, h: pileH, rot: 0, z: z++,
            tex: textureKeyOf(top, sleeve), face: top, interactive: true, role: 'zoneTop', zone, owner: R.pid, count: zc.length });
        }
      }
    });

    // ---- battlefield rows
    // A row level with the player panel starts right of it; a row clear of it (above or below) may also use
    // the space over the panel, so a crowded row gets more room. No card ever goes under the panel.
    const endX = R.x + R.w - pad - pilesAreaW;
    const narrowX = R.x + PANEL_W + pad * 2;
    const narrowW = Math.max(80, endX - narrowX);
    const fullX = R.x + pad;
    const fullW = Math.max(narrowW, endX - fullX);
    const bf = zoneCards(p, 'Battlefield').filter((c) => c.at == null || !store.cards.has(c.at));
    const rowH = innerH / nRows;
    const rowInfo = rowsFor(bf, nRows).map((cs, ri) => {
      const items = makeItems(cs, sel);
      // row 0 (creatures) is nearest the table center
      const slot = R.top ? nRows - 1 - ri : ri;
      const cy = innerTop + rowH * slot + rowH / 2;
      // the tallest cards that stay clear of the panel (6 px apart; pile members sit up to 0.09 h higher)
      const clearH = R.top ? (cy - 6 - (panelTop + panelH)) / 0.59 : 2 * (panelTop - 6 - cy);
      const units = items.reduce((a, it) => a + it.fw, 0) + GAP * Math.max(0, items.length - 1);
      return { items, cy, clearH, units };
    });
    // uniform card height for the region: fit the widest row (down to a floor, then overlap). Which rows may
    // use the full width depends on the height, so: fit with the rows clear even at full size, then let the
    // rows that are clear at that height use the full width too, as long as they stay clear.
    const fit = (hh, fullFrom) => {
      let out = hh, cap = Infinity;
      for (const r of rowInfo) {
        if (!r.items.length) continue;
        const full = r.clearH >= fullFrom;
        out = Math.min(out, (full ? fullW : narrowW) / r.units);
        if (full) cap = Math.min(cap, r.clearH);
      }
      return Math.min(out, cap);
    };
    const h1 = fit(h, h);
    h = Math.max(h1, fit(h, h1));
    const minH = nRows === 1 ? Math.min(innerH * 0.8, 74) : 70;
    h = Math.max(h, Math.min(minH, rowH * 0.9));
    const w = h * CARD_RATIO;

    rowInfo.forEach(({ items, cy, clearH }) => {
      if (!items.length) return;
      const full = clearH >= h;
      // natural width. A row that fits beside the panel stays centered there; a longer one that is clear of the
      // panel grows to the left over it; if it still doesn't fit, advances shrink so it ends at the edge (overlap)
      const gapPx = GAP * h;
      const foot = items.map((it) => it.fw * h);
      const natural = foot.reduce((a, b) => a + b, 0) + gapPx * (items.length - 1);
      const startX = full ? fullX : narrowX, roomW = full ? fullW : narrowW;
      let k = 1;
      let x = natural <= narrowW ? narrowX + (narrowW - natural) / 2 : Math.max(startX, endX - natural);
      if (natural > roomW) {
        const lead = natural - foot[foot.length - 1];
        k = lead > 0 ? (roomW - foot[foot.length - 1]) / lead : 1;
        x = startX;
      }
      items.forEach((it, idx) => {
        const top = it.cards[0];
        const tapped = !!top.tap;
        const cardFoot = tapped ? h : w;
        // the card sits at the left of its footprint; pile/attachment offsets extend to the right
        const cx = x + cardFoot / 2;
        let cyy = cy;
        const forward = R.top ? 1 : -1; // toward the table center
        if (top.atk) cyy += forward * h * 0.16;
        else if (top.blk) cyy += forward * h * 0.08;
        const rot = tapped ? Math.PI / 2 : 0;
        const tex = textureKeyOf(top, sleeve);
        // attached cards stand side by side behind the host, each peeking out a strip further right
        // (the first attached nearest); they stay within the item's footprint, so nothing else is covered
        const na = it.attach.length;
        const step = attachStep(na) * h;
        for (let ai = na - 1; ai >= 0; ai--) {
          const aid = it.attach[ai];
          const a = store.cards.get(aid);
          if (!a) continue;
          const aw = a.tap ? h : w;
          const right = x + cardFoot + (ai + 1) * step;
          targets.set('c' + aid, { key: 'c' + aid, cardId: aid, x: right - aw / 2, y: cyy, w, h, rot: a.tap ? Math.PI / 2 : 0,
            z: z++, tex: textureKeyOf(a, store.players.get(a.o)?.sl ?? sleeve), face: a, interactive: true, role: 'attached', owner: R.pid, zone: 'Battlefield' });
        }
        // pile members below the top card
        const n = it.cards.length;
        for (let k = n - 1; k >= 1; k--) {
          const m = it.cards[k];
          const d = Math.min(k, 3) * 0.05 * h;
          targets.set('c' + m.id, { key: 'c' + m.id, cardId: m.id, x: cx + d, y: cyy - d * 0.6, w, h, rot, z: z++,
            tex, face: m, interactive: false, role: 'pileMember', owner: R.pid, zone: 'Battlefield' });
        }
        targets.set('c' + top.id, { key: 'c' + top.id, cardId: top.id, x: cx, y: cyy, w, h, rot, z: z++,
          tex, face: top, interactive: true, role: 'battlefield', owner: R.pid, zone: 'Battlefield',
          pile: n > 1 ? it.cards.map((c) => c.id) : undefined, count: n > 1 ? n : undefined });
        x += (foot[idx] + gapPx) * k;
      });
    });
  }

  // ---- my hand
  if (hasHand) {
    const p = store.players.get(me);
    let hand = zoneCards(p, 'Hand');
    if (ui.handSort) hand = sortHand(hand, ui.handRules);
    // while a card is dragged, the others make room where it would be dropped
    const drag = ui.handDrag || null;
    const dragged = drag ? hand.find((c) => c.id === drag.id) : null;
    if (dragged && drag) {
      hand = hand.filter((c) => c !== dragged);
      hand.splice(Math.max(0, Math.min(drag.index, hand.length)), 0, dragged);
    }
    const hh = handH * 0.98;
    const hw = hh * CARD_RATIO;
    const promptW = 300;
    let areaX = 12, areaW = playW - promptW - 36;
    const sleeve = p?.sl ?? 0;
    // my commanders in the command zone: a tray at the left of the hand, set apart from it
    if (myTray.length) {
      const cw = hw * 0.94, ch = hh * 0.94, gap = 10, padX = 12;
      const trayW = myTray.length * cw + (myTray.length - 1) * gap + padX * 2;
      decor.push({ type: 'cmdTray', x: areaX + trayW / 2, y: vh - handH / 2 + 4, w: trayW, h: handH - 4 });
      myTray.forEach((c, i) => {
        const hov = !ui.handDrag && ui.hoverHand === c.id;
        // (a raised card grows; it moves right a little so it stays on the screen)
        const x = areaX + padX + cw / 2 + i * (cw + gap) + (hov ? cw * 0.17 : 0);
        const y = hov ? vh - hh * 0.62 : vh - handH / 2 + 10;
        const scale = hov ? 1.32 : 1;
        targets.set('c' + c.id, { key: 'c' + c.id, cardId: c.id, x, y, w: cw * scale, h: ch * scale, rot: 0, z: hov ? 100000 : 49000 + i,
          tex: textureKeyOf(c, sleeve), face: c, interactive: true, role: 'command', tray: true, owner: me, zone: 'Command' });
      });
      areaX += trayW + 22;
      areaW -= trayW + 22;
    }
    hud.hand = { x: areaX, y: vh - handH, w: areaW, h: handH };
    const n = hand.length;
    const step = n > 1 ? Math.min(hw * 0.94, (areaW - hw) / (n - 1)) : 0;
    const total = hw + step * Math.max(0, n - 1);
    const startX = areaX + (areaW - total) / 2 + hw / 2;
    const mid = (n - 1) / 2;
    hand.forEach((c, i) => {
      const hov = !drag && ui.hoverHand === c.id;
      const off = i - mid;
      const arc = n > 1 ? Math.abs(off) / Math.max(1, mid) : 0;
      let x = startX + i * step;
      let y = vh - handH / 2 + 10 + arc * arc * 14;
      let rot = n > 1 ? off * Math.min(0.03, 0.18 / n) : 0;
      let scale = 1;
      let z = 50000 + i;
      if (hov) {
        y = vh - hh * 0.62;
        rot = 0;
        scale = 1.32;
        z = 100000;
      }
      if (c === dragged && drag) {
        x = drag.x;
        y = drag.y;
        rot = 0;
        scale = 1.12;
        z = 200000;
      }
      targets.set('c' + c.id, { key: 'c' + c.id, cardId: c.id, x, y, w: hw * scale, h: hh * scale, rot, z,
        tex: textureKeyOf(c, sleeve), face: c, interactive: true, role: 'hand', owner: me, zone: 'Hand', slotX: startX + i * step });
    });
    // a fixed box with the buttons at its bottom, so they don't move with the length of the message
    hud.prompt = { x: playW - promptW - 14, y: vh - handH + 8, w: promptW, h: handH - 16 };
  } else {
    const w = Math.min(640, playW - 40);
    hud.prompt = { x: (playW - w) / 2, y: vh - barH + 6, w, bar: true };
  }

  // ---- stack (top first), shown as a column over the right side of the table
  if (store.stack.length) {
    const sh = Math.min(170, fieldH * 0.24);
    const sw = sh * CARD_RATIO;
    const colX = playW - sw / 2 - 30 - (ui.sideW ? 0 : 0);
    const stepY = Math.min(sh * 0.34, (fieldH * 0.72 - sh) / Math.max(1, store.stack.length - 1));
    const y0 = fieldH * 0.14 + sh / 2;
    store.stack.forEach((si, i) => {
      const y = y0 + i * stepY;
      const src = si.src != null ? store.cards.get(si.src) : null;
      const zz = 90000 - i;
      if (src && src.z === 'Stack' && !si.ab) {
        const owner = store.players.get(src.o);
        targets.set('c' + src.id, { key: 'c' + src.id, cardId: src.id, x: colX, y, w: sw, h: sh, rot: 0, z: zz,
          tex: textureKeyOf(src, owner?.sl ?? 0), face: src, interactive: true, role: 'stack', zone: 'Stack', stackItem: si });
      } else {
        const owner = src ? store.players.get(src.o) : null;
        targets.set('s' + si.id, { key: 's' + si.id, cardId: src ? src.id : -1, x: colX, y, w: sw * 0.9, h: sh * 0.9, rot: 0, z: zz,
          tex: src ? textureKeyOf(src, owner?.sl ?? 0) : 'proc:s' + si.id, face: src || { n: 'Ability', tx: si.txt }, interactive: true,
          role: 'stackAbility', stackItem: si });
      }
      hud.stack.push({ si, x: colX - sw / 2 - 8, y, h: sh, top: i === 0 });
    });
  }
  return { targets, decor, hud };
}
