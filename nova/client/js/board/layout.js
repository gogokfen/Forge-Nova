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
 *   role:string, alpha?:number, stackItem?:any, slotX?:number}} Target
 */

function isCreatureRow(c) { return (c.tf & TF.CREATURE) || (c.tf & TF.PW) || (c.tf & TF.BATTLE); }
function isLandRow(c) { return (c.tf & TF.LAND) && !(c.tf & TF.CREATURE); }

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
    const attExtra = it.attach.length * 0.13;
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
 *   handSort?:boolean, handRules?:any, handDrag?:{id:number, x:number, y:number, index:number}|null}} ui
 */
export function computeLayout(ui) {
  const vw = ui.w, vh = ui.h;
  /** @type {Map<string, Target>} */
  const targets = new Map();
  const decor = [];
  const hud = { regions: /** @type {any[]} */ ([]), phase: { x: 0, y: 0 }, prompt: { x: 0, y: 0, w: 300 }, stack: /** @type {any[]} */ ([]), hand: { x: 0, y: 0, w: 0, h: 0 } };
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
    const panelH = 104;
    const panel = { x: R.x + pad, y: R.top ? R.y + pad : R.y + R.h - panelH - pad };
    const reg = { ...R, panel, piles: /** @type {any} */ ({}) };
    hud.regions.push(reg);

    const innerTop = R.y + (R.top ? 6 : 16); // leave room for the phase bar on my side
    const innerH = R.h - (R.top ? 12 : 22);
    const nRows = innerH >= 300 ? 3 : innerH >= 190 ? 2 : 1;
    const maxH = Math.min(vh * (others.length > 1 ? 0.2 : 0.23), 210);
    let h = Math.min(maxH, (innerH / nRows) * 0.9);

    // ---- zone piles on the right (library, graveyard, exile)
    const pileH = Math.max(46, Math.min(h * 0.8, nRows === 1 ? innerH * 0.8 : innerH / 3 - 8));
    const pileW = pileH * CARD_RATIO;
    const pileSlots = ['Library', 'Graveyard', 'Exile'];
    const pilesAreaW = nRows === 1 ? pileSlots.length * (pileW + 8) + 8 : pileW + 20;
    pileSlots.forEach((zone, i) => {
      let px, py;
      if (nRows === 1) {
        px = R.x + R.w - pilesAreaW + 8 + i * (pileW + 8) + pileW / 2;
        py = innerTop + innerH / 2;
      } else {
        px = R.x + R.w - pilesAreaW / 2;
        const slotH = innerH / 3;
        const idx = R.top ? i : 2 - i; // library farthest from the center
        py = innerTop + slotH * idx + slotH / 2;
      }
      reg.piles[zone] = { x: px, y: py, w: pileW, h: pileH, count: zoneCount(p, zone) };
      decor.push({ type: 'slot', x: px, y: py, w: pileW, h: pileH, label: zone === 'Library' ? 'LIB' : zone === 'Graveyard' ? 'GY' : 'EX' });
      const n = zoneCount(p, zone);
      if (zone === 'Library') {
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
    const fieldX = R.x + PANEL_W + pad * 2;
    const fieldW = Math.max(80, R.w - PANEL_W - pad * 3 - pilesAreaW);
    const bf = zoneCards(p, 'Battlefield').filter((c) => c.at == null || !store.cards.has(c.at));
    const cmd = zoneCards(p, 'Command');
    const rows = rowsFor(bf, nRows).map((cs) => makeItems(cs, sel));
    // command zone objects lead the row farthest from the table center
    if (cmd.length) {
      const cmdItems = cmd.map((c) => ({ cards: [c], attach: [], fw: CARD_RATIO * 0.85, cmd: true }));
      rows[rows.length - 1] = cmdItems.concat(rows[rows.length - 1]);
    }
    // uniform card height for the region: fit the widest row (down to a floor, then overlap)
    for (const items of rows) {
      if (!items.length) continue;
      const units = items.reduce((a, it) => a + it.fw, 0) + GAP * (items.length - 1);
      h = Math.min(h, fieldW / units);
    }
    const minH = nRows === 1 ? Math.min(innerH * 0.8, 74) : 70;
    h = Math.max(h, Math.min(minH, (innerH / nRows) * 0.9));
    const w = h * CARD_RATIO;
    const rowH = innerH / nRows;

    rows.forEach((items, ri) => {
      if (!items.length) return;
      // row 0 (creatures) is nearest the table center
      const slot = R.top ? nRows - 1 - ri : ri;
      const cy = innerTop + rowH * slot + rowH / 2;
      // natural width; if it doesn't fit, advances shrink so the last item ends at the edge (overlap)
      const gapPx = GAP * h;
      const foot = items.map((it) => it.fw * h);
      const natural = foot.reduce((a, b) => a + b, 0) + gapPx * (items.length - 1);
      let k = 1;
      let x = fieldX + (fieldW - natural) / 2;
      if (natural > fieldW) {
        const lead = natural - foot[foot.length - 1];
        k = lead > 0 ? (fieldW - foot[foot.length - 1]) / lead : 1;
        x = fieldX;
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
        // attachments peek out behind the host
        it.attach.forEach((aid, ai) => {
          const a = store.cards.get(aid);
          if (!a) return;
          const off = (it.attach.length - ai) * 0.13 * h;
          targets.set('c' + aid, { key: 'c' + aid, cardId: aid, x: cx + off, y: cyy + forward * -off * 0.9, w, h, rot: a.tap ? Math.PI / 2 : 0,
            z: z++, tex: textureKeyOf(a, sleeve), face: a, interactive: true, role: 'attached', owner: R.pid, zone: 'Battlefield' });
        });
        // pile members below the top card
        const n = it.cards.length;
        for (let k = n - 1; k >= 1; k--) {
          const m = it.cards[k];
          const d = Math.min(k, 3) * 0.05 * h;
          targets.set('c' + m.id, { key: 'c' + m.id, cardId: m.id, x: cx + d, y: cyy - d * 0.6, w, h, rot, z: z++,
            tex, face: m, interactive: false, role: 'pileMember', owner: R.pid, zone: 'Battlefield' });
        }
        targets.set('c' + top.id, { key: 'c' + top.id, cardId: top.id, x: cx, y: cyy, w: it.cmd ? w * 0.85 : w, h: it.cmd ? h * 0.85 : h, rot, z: z++,
          tex, face: top, interactive: true, role: it.cmd ? 'command' : 'battlefield', owner: R.pid, zone: it.cmd ? 'Command' : 'Battlefield',
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
    const areaX = 12, areaW = playW - promptW - 36;
    hud.hand = { x: areaX, y: vh - handH, w: areaW, h: handH };
    const n = hand.length;
    const step = n > 1 ? Math.min(hw * 0.94, (areaW - hw) / (n - 1)) : 0;
    const total = hw + step * Math.max(0, n - 1);
    const startX = areaX + (areaW - total) / 2 + hw / 2;
    const mid = (n - 1) / 2;
    const sleeve = p?.sl ?? 0;
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
