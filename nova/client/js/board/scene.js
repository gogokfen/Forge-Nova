// @ts-check
// Sprites for every visible card: animation toward layout targets, GPU drawing, hit testing.

import { store, TF } from '../store.js';
import { KIND } from '../gl/renderer.js';

const C = {
  shadow: [0, 0, 0, 0.55],
  select: [0.31, 0.70, 1.0, 0.95],
  picked: [1.0, 0.83, 0.42, 1.0],
  weak: [0.27, 0.77, 0.42, 0.85],
  hover: [1, 1, 1, 0.35],
  attack: [0.94, 0.30, 0.25, 0.9],
  block: [0.45, 0.62, 1.0, 0.8],
  target: [1.0, 0.55, 0.2, 0.95],
  ptBg: [0.08, 0.09, 0.11, 0.92],
  white: [1, 1, 1, 1],
  dmg: [1, 0.45, 0.4, 1],
  boost: [0.55, 0.95, 0.6, 1],
  badge: [0.10, 0.12, 0.16, 0.94],
  gold: [0.88, 0.65, 0.29, 1],
  slot: [1, 1, 1, 0.10],
  slotText: [1, 1, 1, 0.22],
  cmdBg: [0.35, 0.22, 0.55, 0.95],
};

const COUNTER_LABEL = {
  P1P1: '+1/+1', M1M1: '-1/-1', LOYALTY: 'L', DEFENSE: 'D', CHARGE: 'CHG', LORE: 'LORE', TIME: 'TIME',
  POISON: 'PSN', SHIELD: 'SHLD', STUN: 'STUN', OIL: 'OIL', FINALITY: 'FIN', FLYING: 'FLY', DEATHTOUCH: 'DTH',
  LIFELINK: 'LFL', INDESTRUCTIBLE: 'IND', TRAMPLE: 'TRM', HEXPROOF: 'HEX', MENACE: 'MEN', REACH: 'RCH', VIGILANCE: 'VIG',
};

function lerp(a, b, t) { return a + (b - a) * t; }
function lerpAngle(a, b, t) {
  let d = b - a;
  while (d > Math.PI) d -= Math.PI * 2;
  while (d < -Math.PI) d += Math.PI * 2;
  return a + d * t;
}

export class Scene {
  /**
   * @param {import('../gl/renderer.js').Renderer} r
   * @param {import('../gl/textures.js').CardTextures} tex
   * @param {import('../gl/font.js').SDFFont} font
   */
  constructor(r, tex, font) {
    this.r = r;
    this.tex = tex;
    this.font = font;
    /** @type {Map<string, any>} */
    this.sprites = new Map();
    /** @type {any[]} */
    this.sorted = [];
    /** @type {any} */
    this.layout = null;
    /** @type {any} */
    this.hover = null;
    this.time = 0;
    this.animating = false;
    /** @type {Map<number, number>} card id -> shake start time */
    this.shakes = new Map();
    /** @type {Set<number>} */
    this.targeted = new Set();
  }

  /** Zone pile position for a player (for cards flying to library/graveyard/exile). */
  pileOf(pid, zone) {
    const reg = this.layout?.hud.regions.find((r) => r.pid === pid);
    return reg?.piles?.[zone] || null;
  }

  setLayout(layout) {
    this.layout = layout;
    const seen = new Set();
    for (const t of layout.targets.values()) {
      seen.add(t.key);
      let s = this.sprites.get(t.key);
      if (!s) {
        s = { key: t.key, x: t.x, y: t.y, w: t.w, h: t.h, rot: t.rot, alpha: 0, t, dying: false };
        // where does a new sprite come from?
        const card = t.cardId >= 0 ? store.cards.get(t.cardId) : null;
        const from = card && card._prevZone === 'Library' ? this.pileOf(card.o, 'Library')
          : card && t.role === 'hand' ? this.pileOf(card.o, 'Library') : null;
        if (from) {
          s.x = from.x; s.y = from.y; s.w = from.w; s.h = from.h; s.alpha = 1;
        } else if (t.role === 'stackAbility' && t.cardId >= 0) {
          const src = this.sprites.get('c' + t.cardId);
          if (src) { s.x = src.x; s.y = src.y; s.w = src.w; s.h = src.h; s.rot = src.rot; }
          s.alpha = 0.2;
        } else {
          s.w = t.w * 0.82; s.h = t.h * 0.82;
        }
        this.sprites.set(t.key, s);
      }
      s.t = t;
      s.dying = false;
    }
    for (const s of this.sprites.values()) {
      if (seen.has(s.key) || s.dying) continue;
      s.dying = true;
      // fly to the zone the card went to, if we know it
      const id = s.t.cardId;
      const card = id >= 0 ? store.cards.get(id) : null;
      const dest = card ? this.pileOf(card.o, card.z) : null;
      if (dest && (card.z === 'Graveyard' || card.z === 'Exile' || card.z === 'Library')) {
        s.t = { ...s.t, x: dest.x, y: dest.y, w: dest.w, h: dest.h, rot: 0 };
      } else if (!card && s.t.role === 'hand') {
        s.t = { ...s.t, y: s.t.y - 60 };
      }
    }
    this.sorted = [...this.sprites.values()].sort((a, b) => a.t.z - b.t.z);
    this.animating = true;
    // arrows need the latest top-of-stack targets
    this.targeted.clear();
    const top = store.stack[0];
    if (top?.tc) for (const id of top.tc) this.targeted.add(id);
  }

  shake(cardId) {
    this.shakes.set(cardId, this.time);
    this.animating = true;
  }

  /** Advances animations; returns true while anything is still moving. */
  update(dt) {
    this.time += dt;
    const k = 1 - Math.exp(-dt * 14);
    const ka = 1 - Math.exp(-dt * 10);
    let moving = false;
    let removed = false;
    for (const s of this.sorted) {
      const t = s.t;
      const targetAlpha = s.dying ? 0 : (t.alpha ?? 1);
      s.x = lerp(s.x, t.x, k);
      s.y = lerp(s.y, t.y, k);
      s.w = lerp(s.w, t.w, k);
      s.h = lerp(s.h, t.h, k);
      s.rot = lerpAngle(s.rot, t.rot, k);
      s.alpha = lerp(s.alpha, targetAlpha, s.dying ? ka * 1.4 : ka);
      if (Math.abs(s.x - t.x) > 0.3 || Math.abs(s.y - t.y) > 0.3 || Math.abs(s.w - t.w) > 0.3 ||
          Math.abs(s.rot - t.rot) > 0.002 || Math.abs(s.alpha - targetAlpha) > 0.01) {
        moving = true;
      } else {
        s.x = t.x; s.y = t.y; s.w = t.w; s.h = t.h; s.rot = t.rot; s.alpha = targetAlpha;
      }
      if (s.dying && s.alpha < 0.02) {
        this.sprites.delete(s.key);
        removed = true;
      }
    }
    if (removed) this.sorted = this.sorted.filter((s) => this.sprites.has(s.key));
    if (this.shakes.size) moving = true;
    for (const [id, t0] of this.shakes) if (this.time - t0 > 0.45) this.shakes.delete(id);
    this.animating = moving;
    return moving || this.hasPulse();
  }

  hasPulse() {
    return store.sel.ids.size > 0 || this.targeted.size > 0;
  }

  // ----------------------------------------------------------------- drawing

  draw() {
    const r = this.r;
    const font = this.font;
    this.tex.beginFrame();
    const pulse = 0.72 + 0.28 * Math.sin(this.time * 4.2);
    const L = this.layout;
    if (L) this.drawDecor(L);
    const hov = this.hover;
    for (const s of this.sorted) {
      if (s === hov) continue;
      this.drawSprite(s, pulse, false);
    }
    if (hov && this.sprites.has(hov.key)) this.drawSprite(hov, pulse, true);
    void font;
  }

  drawDecor(L) {
    const r = this.r, font = this.font;
    const active = store.game.ap;
    for (const reg of L.hud.regions) {
      const isActive = reg.pid === active;
      r.rect(reg.x + reg.w / 2, reg.y + reg.h / 2, reg.w - 8, reg.h - 8, 0, 14,
        isActive ? [0.88, 0.65, 0.29, 0.045] : [1, 1, 1, 0.018]);
      if (isActive) r.ring(reg.x + reg.w / 2, reg.y + reg.h / 2, reg.w - 8, reg.h - 8, 0, 14, 1, [0.88, 0.65, 0.29, 0.22]);
    }
    for (const d of L.decor) {
      if (d.type === 'slot') {
        r.ring(d.x, d.y, d.w, d.h, 0, d.w * 0.06, 1.2, C.slot);
        font.draw(r, d.label, 0, 0, Math.max(9, d.h * 0.13), C.slotText, 'center', d.x, d.y, 0);
      }
    }
  }

  drawSprite(s, pulse, hovered) {
    const r = this.r, font = this.font, t = s.t;
    if (s.alpha < 0.01) return;
    const card = t.cardId >= 0 ? store.cards.get(t.cardId) : null;
    let x = s.x, y = s.y;
    const w = s.w, h = s.h, rot = s.rot, a = s.alpha;
    if (card && this.shakes.has(card.id)) {
      const e = this.time - /** @type {number} */ (this.shakes.get(card.id));
      x += Math.sin(e * 60) * 5 * (1 - e / 0.45);
    }
    const radius = w * 0.047;
    const sel = store.sel;
    const id = t.cardId;

    // shadow
    if (t.role !== 'pileMember' || w > 60) {
      r.quad(KIND.GLOW, x + 2, y + 4, w + 16, h + 16, rot, 0, radius + 4, 0, 0, 1, 1, 0, 0, 0, 0.45 * a, 8, 0, 0, 0);
    }
    // highlights
    if (t.interactive && id >= 0) {
      if (sel.hiC.has(id)) {
        r.glow(x, y, w, h, rot, radius, 12, [C.picked[0], C.picked[1], C.picked[2], 0.9 * a]);
      } else if (sel.ids.has(id)) {
        r.glow(x, y, w, h, rot, radius, 11, [C.select[0], C.select[1], C.select[2], pulse * a]);
      } else if (sel.weak.has(id)) {
        r.glow(x, y, w, h, rot, radius, 8, [C.weak[0], C.weak[1], C.weak[2], 0.75 * a]);
      }
      if (this.targeted.has(id)) {
        r.glow(x, y, w, h, rot, radius, 10, [C.target[0], C.target[1], C.target[2], pulse * a]);
      }
      if (card?.atk) r.glow(x, y, w, h, rot, radius, 7, [C.attack[0], C.attack[1], C.attack[2], 0.8 * a]);
      else if (card?.blk) r.glow(x, y, w, h, rot, radius, 7, [C.block[0], C.block[1], C.block[2], 0.75 * a]);
    }
    if (hovered) r.glow(x, y, w, h, rot, radius, 6, [1, 1, 1, 0.45 * a]);

    // card face
    const layer = this.tex.layerFor(t.tex || 'proc:' + t.key, t.face);
    const gray = card?.ph ? 0.85 : 0;
    const bright = hovered ? 0.08 : 0;
    const tint = card?.ph ? 0.55 : 1;
    r.quad(KIND.CARD, x, y, w, h, rot, layer, radius, 0, 0, 1, 1, tint, tint, tint, a, gray, bright, 1, 0);

    if (t.role === 'pileMember' || w < 34) return;
    this.drawOverlays(x, y, w, h, rot, a, card, t);
  }

  drawOverlays(x, y, w, h, rot, a, card, t) {
    const r = this.r, font = this.font;
    const c = Math.cos(rot), s = Math.sin(rot);
    // local (card space) -> world
    const wx = (lx, ly) => x + lx * c - ly * s;
    const wy = (lx, ly) => y + lx * s + ly * c;
    const fs = Math.max(9, h * 0.105);

    // zone pile count
    if ((t.role === 'library' || t.role === 'zoneTop') && t.count) {
      const txt = String(t.count);
      const bw = font.measure(txt, fs) + fs * 0.9;
      r.rect(wx(0, h * 0.5 - fs * 0.2), wy(0, h * 0.5 - fs * 0.2), bw, fs * 1.35, rot, fs * 0.6, [0.05, 0.06, 0.08, 0.92 * a]);
      font.draw(r, txt, 0, 0, fs, [1, 1, 1, a], 'center', wx(0, h * 0.5 - fs * 0.2), wy(0, h * 0.5 - fs * 0.2), rot);
      return;
    }
    if (!card || card.hid && !card.fd) return;
    if (t.role === 'hand' || t.role === 'stack' || t.role === 'stackAbility') return;

    // pile count badge (top-right)
    if (t.count && t.role === 'battlefield') {
      const txt = '×' + t.count;
      const bw = font.measure(txt, fs * 1.05) + fs * 0.9;
      const bx = w / 2 - bw / 2 + fs * 0.25, by = -h / 2 + fs * 0.35;
      r.rect(wx(bx, by), wy(bx, by), bw, fs * 1.45, rot, fs * 0.7, [C.gold[0], C.gold[1], C.gold[2], 0.97 * a]);
      font.draw(r, txt, 0, 0, fs * 1.05, [0.1, 0.07, 0.02, a], 'center', wx(bx, by), wy(bx, by), rot);
    }

    // P/T, loyalty, defense (bottom-right)
    let stat = null, statColor = C.white;
    const cnt = card.cnt || {};
    if (card.pow !== undefined && (card.tf & TF.CREATURE)) {
      const tough = card.tou - (card.dmg || 0);
      stat = `${card.pow}/${tough}`;
      if (card.dmg) statColor = C.dmg;
    } else if (card.tf & TF.PW) {
      stat = String(cnt.LOYALTY ?? card.loy ?? '');
    } else if (card.tf & TF.BATTLE) {
      stat = String(cnt.DEFENSE ?? card.def ?? '');
    }
    if (stat) {
      const sfs = fs * 1.12;
      const bw = Math.max(font.measure(stat, sfs) + sfs * 0.8, sfs * 1.9);
      const bh = sfs * 1.4;
      const bx = w / 2 - bw / 2 - w * 0.035, by = h / 2 - bh / 2 - h * 0.03;
      const pw = card.tf & TF.PW;
      r.rect(wx(bx, by), wy(bx, by), bw, bh, rot, pw ? bh * 0.5 : bh * 0.28, pw ? [0.12, 0.10, 0.18, 0.95 * a] : [C.ptBg[0], C.ptBg[1], C.ptBg[2], C.ptBg[3] * a]);
      if (pw) r.ring(wx(bx, by), wy(bx, by), bw, bh, rot, bh * 0.5, 1.2, [0.7, 0.6, 1, 0.8 * a]);
      font.draw(r, stat, 0, 0, sfs, [statColor[0], statColor[1], statColor[2], a], 'center', wx(bx, by), wy(bx, by), rot);
    }

    // counters (top-left), excluding the ones shown as stats
    let cy = -h / 2 + fs * 0.95;
    for (const [name, n] of Object.entries(cnt)) {
      if (name === 'LOYALTY' && (card.tf & TF.PW)) continue;
      if (name === 'DEFENSE' && (card.tf & TF.BATTLE)) continue;
      const label = (COUNTER_LABEL[name] || name.slice(0, 4)) + (n > 1 || name === 'P1P1' || name === 'M1M1' ? ' ×' + n : '');
      const cfs = fs * 0.88;
      const bw = font.measure(label, cfs) + cfs * 0.9;
      const bx = -w / 2 + bw / 2 + w * 0.04;
      const col = name === 'P1P1' ? [0.16, 0.42, 0.24, 0.95 * a] : name === 'M1M1' ? [0.5, 0.15, 0.15, 0.95 * a] : [C.badge[0], C.badge[1], C.badge[2], C.badge[3] * a];
      r.rect(wx(bx, cy), wy(bx, cy), bw, cfs * 1.4, rot, cfs * 0.5, col);
      font.draw(r, label, 0, 0, cfs, [1, 1, 1, a], 'center', wx(bx, cy), wy(bx, cy), rot);
      cy += cfs * 1.6;
      if (cy > h * 0.2) break;
    }

    // small markers along the top edge
    let mx = w / 2 - fs * 0.9;
    const markY = t.count ? -h / 2 + fs * 2.1 : -h / 2 + fs * 0.9;
    if (card.sick && (card.tf & TF.CREATURE) && t.role === 'battlefield') {
      r.circle(wx(mx, markY), wy(mx, markY), fs * 1.35, [0.1, 0.12, 0.18, 0.9 * a]);
      font.draw(r, 'z', 0, 0, fs * 0.9, [0.7, 0.8, 1, a], 'center', wx(mx, markY), wy(mx, markY), rot);
      mx -= fs * 1.5;
    }
    if (card.cmd && t.role !== 'command') {
      r.circle(wx(mx, markY), wy(mx, markY), fs * 1.35, [C.cmdBg[0], C.cmdBg[1], C.cmdBg[2], C.cmdBg[3] * a]);
      font.draw(r, '★', 0, 0, fs * 0.95, [1, 0.9, 0.6, a], 'center', wx(mx, markY), wy(mx, markY), rot);
    }
    if (t.role === 'command') {
      const label = card.cmd ? 'COMMANDER' : 'COMMAND';
      const cfs = fs * 0.78;
      const bw = font.measure(label, cfs) + cfs;
      r.rect(wx(0, h / 2 - cfs * 0.2), wy(0, h / 2 - cfs * 0.2), bw, cfs * 1.4, rot, cfs * 0.5, [C.cmdBg[0], C.cmdBg[1], C.cmdBg[2], 0.95 * a]);
      font.draw(r, label, 0, 0, cfs, [1, 1, 1, a], 'center', wx(0, h / 2 - cfs * 0.2), wy(0, h / 2 - cfs * 0.2), rot);
    }
    const note = card.ovl || card.chT || '';
    if (note) {
      const cfs = fs * 0.8;
      const txt = note.length > 18 ? note.slice(0, 17) + '…' : note;
      const bw = font.measure(txt, cfs) + cfs;
      r.rect(wx(0, h * 0.12), wy(0, h * 0.12), bw, cfs * 1.4, rot, cfs * 0.4, [0.05, 0.06, 0.08, 0.85 * a]);
      font.draw(r, txt, 0, 0, cfs, [1, 0.9, 0.7, a], 'center', wx(0, h * 0.12), wy(0, h * 0.12), rot);
    }
  }

  // ----------------------------------------------------------------- picking

  /** Topmost interactive sprite under (px, py). */
  pick(px, py) {
    const hov = this.hover;
    if (hov && this.sprites.has(hov.key) && this.inside(hov, px, py)) return hov;
    for (let i = this.sorted.length - 1; i >= 0; i--) {
      const s = this.sorted[i];
      if (!s.t.interactive || s.dying || s.alpha < 0.5) continue;
      if (this.inside(s, px, py)) return s;
    }
    return null;
  }

  inside(s, px, py) {
    const dx = px - s.x, dy = py - s.y;
    const c = Math.cos(-s.rot), sn = Math.sin(-s.rot);
    const lx = dx * c - dy * sn, ly = dx * sn + dy * c;
    return Math.abs(lx) <= s.w / 2 && Math.abs(ly) <= s.h / 2;
  }

  spriteOfCard(id) {
    return this.sprites.get('c' + id) || null;
  }
}
