// @ts-check
// The deck being edited: sections, copy rules, undo, statistics, checks, text import and export.

import { catalog, findCard, T, CMD, COLOR_BITS, isLegal, formatIndex } from './catalog.js';

/** One line of a deck: card name, printing (set + art index), quantity and (optionally) its image key. */
/** @typedef {{n:string, s:string, a:number, q:number, img?:string}} Entry */
/** @typedef {'commander'|'main'|'side'} Section */

/** Deck formats, matching the lobby's and Forge's DeckFormat (size counts the commander section). */
export const FORMATS = {
  commander: { t: 'Commander', cmdr: true, size: 100, side: 10, legal: 'Commander', flag: CMD.COMMANDER },
  constructed: { t: 'Constructed', cmdr: false, size: 60, side: 15, legal: '', flag: 0 },
  brawl: { t: 'Brawl', cmdr: true, size: 60, side: 15, legal: 'Brawl', flag: CMD.BRAWL },
  oathbreaker: { t: 'Oathbreaker', cmdr: true, size: 60, side: 10, legal: 'Oathbreaker', flag: CMD.OATHBREAKER },
  tinyLeaders: { t: 'Tiny Leaders', cmdr: true, size: 50, side: 10, legal: 'Tiny Leaders', flag: CMD.TINY },
};

const WORD_NUM = { one: 1, two: 2, three: 3, four: 4, five: 5, six: 6, seven: 7, eight: 8, nine: 9, ten: 10 };
const MAX_UNDO = 200;

export class DeckModel {
  constructor() {
    /** deck folder / format id */
    this.src = 'commander';
    this.name = '';
    this.comment = '';
    /** @type {{src:string, name:string}|null} the saved file this deck belongs to */
    this.origin = null;
    /** where the deck file came from (Forge's "Source URL": a synced deck's Moxfield link) */
    this.source = '';
    /** @type {Record<Section, Entry[]>} */
    this.sections = { commander: [], main: [], side: [] };
    this.dirty = false;
    /** @type {string[]} */ this.undoStack = [];
    /** @type {string[]} */ this.redoStack = [];
    /** @type {Set<() => void>} */ this.listeners = new Set();
  }

  get format() { return FORMATS[this.src] || FORMATS.constructed; }

  onChange(fn) { this.listeners.add(fn); return () => this.listeners.delete(fn); }
  emit() { this.listeners.forEach((fn) => { try { fn(); } catch (e) { console.error(e); } }); }

  snapshot() { return JSON.stringify({ src: this.src, sections: this.sections }); }
  restore(s) {
    const d = JSON.parse(s);
    this.src = d.src;
    this.sections = d.sections;
  }

  /** Applies an edit with undo support. */
  change(fn) {
    const before = this.snapshot();
    fn();
    if (this.snapshot() === before) return;
    this.undoStack.push(before);
    if (this.undoStack.length > MAX_UNDO) this.undoStack.shift();
    this.redoStack = [];
    this.dirty = true;
    this.emit();
  }

  undo() {
    const s = this.undoStack.pop();
    if (!s) return false;
    this.redoStack.push(this.snapshot());
    this.restore(s);
    this.dirty = true;
    this.emit();
    return true;
  }

  redo() {
    const s = this.redoStack.pop();
    if (!s) return false;
    this.undoStack.push(this.snapshot());
    this.restore(s);
    this.dirty = true;
    this.emit();
    return true;
  }

  /** Starts over: an empty deck (or loaded contents) that is not dirty. */
  reset(src, name = '', origin = null, sections = null, comment = '', source = '') {
    this.src = FORMATS[src] ? src : 'commander';
    this.name = name;
    this.comment = comment || '';
    this.origin = origin;
    this.source = origin ? source || '' : '';
    this.sections = sections || { commander: [], main: [], side: [] };
    this.undoStack = [];
    this.redoStack = [];
    this.dirty = false;
    this.emit();
  }

  /** From the host's deck JSON ({sections:{main:[[name,set,art,count,img]], ...}}). */
  load(d) {
    const conv = (arr) => (arr || []).map(([n, s, a, q, img]) => ({ n, s, a, q, img }));
    const user = FORMATS[d.src];
    const src = user ? d.src : d.src === 'cmdPrecon' ? 'commander' : 'constructed';
    const name = String(d.name || '').split('/').pop() || '';
    this.reset(src, name, user ? { src: d.src, name: d.name } : null,
      { commander: conv(d.sections.commander), main: conv(d.sections.main), side: conv(d.sections.side) }, d.comment, d.source);
    if (!user) this.dirty = true; // a precon opened as a template still needs saving as your own deck
  }

  toApi() {
    const conv = (arr) => arr.filter((e) => e.q > 0).map((e) => [e.n, e.s || '', e.a || 0, e.q]);
    return { commander: conv(this.sections.commander), main: conv(this.sections.main), side: conv(this.sections.side) };
  }

  count(section) { return this.sections[section].reduce((a, e) => a + e.q, 0); }
  /** copies of a card name across the whole deck */
  copies(name) {
    let n = 0;
    for (const s of /** @type {Section[]} */ (['commander', 'main', 'side'])) for (const e of this.sections[s]) if (e.n === name) n += e.q;
    return n;
  }

  maxCopies(card) {
    if (!card) return Infinity;
    if (card.tf & T.BASIC) return Infinity;
    const o = card.o.toLowerCase();
    if (o.includes('a deck can have any number of cards named')) return Infinity;
    const m = /a deck can have up to (\w+) cards named/.exec(o);
    if (m) return WORD_NUM[m[1]] ?? (parseInt(m[1], 10) || 1);
    return this.format.cmdr ? 1 : 4;
  }

  /** Adds copies (limited by the copy rules unless `force`); returns how many were added. */
  add(section, card, qty = 1, printing = null, force = false) {
    const room = force ? qty : Math.max(0, Math.min(qty, this.maxCopies(card) - this.copies(card.n)));
    if (room <= 0) return 0;
    const s = printing?.set || card.s;
    const a = printing ? printing.art || 0 : 0;
    this.change(() => {
      const list = this.sections[section];
      const e = list.find((x) => x.n === card.n && (x.s || '') === (s || '') && (printing ? (x.a || 0) === a : true));
      if (e) e.q += room;
      else list.push({ n: card.n, s, a, q: room, img: printing?.img || card.img });
    });
    return room;
  }

  /** Removes up to `qty` copies of a name from a section (any printing, last added first). */
  remove(section, name, qty = 1) {
    this.change(() => {
      const list = this.sections[section];
      for (let i = list.length - 1; i >= 0 && qty > 0; i--) {
        if (list[i].n !== name) continue;
        const take = Math.min(qty, list[i].q);
        list[i].q -= take;
        qty -= take;
        if (list[i].q <= 0) list.splice(i, 1);
      }
    });
  }

  setQty(section, entry, q) {
    this.change(() => {
      const list = this.sections[section];
      const i = list.indexOf(entry);
      if (i < 0) return;
      if (q <= 0) list.splice(i, 1);
      else list[i].q = q;
    });
  }

  /** Moves all copies of an entry to another section. */
  move(from, entry, to) {
    this.change(() => {
      const i = this.sections[from].indexOf(entry);
      if (i < 0) return;
      this.sections[from].splice(i, 1);
      const same = this.sections[to].find((x) => x.n === entry.n && x.s === entry.s && (x.a || 0) === (entry.a || 0));
      if (same) same.q += entry.q;
      else this.sections[to].push(entry);
    });
  }

  setPrinting(entry, printing) {
    this.change(() => {
      entry.s = printing.set;
      entry.a = printing.art || 0;
      entry.img = printing.img;
    });
  }

  /**
   * Makes a card (one of) the commander(s). A second commander is kept when the two can be partners
   * (or a background); otherwise the old one returns to the main deck.
   */
  setCommander(card, role = 'commander') {
    this.change(() => {
      const cmd = this.sections.commander;
      for (const s of /** @type {Section[]} */ (['main', 'side'])) {
        const i = this.sections[s].findIndex((x) => x.n === card.n);
        if (i >= 0) this.sections[s].splice(i, 1);
      }
      if (cmd.some((x) => x.n === card.n)) return;
      const entry = { n: card.n, s: card.s, a: 0, q: 1, img: card.img };
      if (this.src === 'oathbreaker') {
        // one oathbreaker (planeswalker) and one signature spell
        const keep = cmd.filter((x) => {
          const c = findCard(x.n);
          const isSig = !!c && (c.cmd & CMD.SIGNATURE) !== 0 && (c.tf & T.PW) === 0;
          return role === 'signature' ? !isSig : isSig;
        });
        this.sections.commander = [...keep, entry];
        return;
      }
      const cur = cmd.map((x) => findCard(x.n));
      if (cmd.length === 1 && cur[0] && canPair(cur[0], card)) cmd.push(entry);
      else {
        for (const x of cmd) this.sections.main.push({ ...x });
        this.sections.commander = [entry];
      }
    });
  }

  /** The color identity allowed by the commander(s), or -1 without one. */
  identity() {
    if (!this.format.cmdr || !this.sections.commander.length) return -1;
    let id = 0;
    for (const e of this.sections.commander) id |= findCard(e.n)?.ci || 0;
    return id;
  }

  // ---- persistence of unsaved work (per browser profile)
  toDraft() {
    return JSON.stringify({ src: this.src, name: this.name, comment: this.comment, origin: this.origin, source: this.source, sections: this.sections, dirty: this.dirty });
  }

  fromDraft(json) {
    const d = JSON.parse(json);
    this.reset(d.src, d.name, d.origin, d.sections, d.comment, d.source);
    this.dirty = !!d.dirty;
  }
}

/** Two commanders that may lead together: partners, or a creature that chooses a background plus one. */
export function canPair(a, b) {
  if (!a || !b || a.n === b.n) return false;
  if ((a.cmd & CMD.PARTNER) && (b.cmd & CMD.PARTNER)) {
    const pa = /partner with ([^(\n]+)/i.exec(a.o), pb = /partner with ([^(\n]+)/i.exec(b.o);
    if (pa || pb) return !!pa && !!pb && pa[1].trim().toLowerCase().startsWith(b.lname) && pb[1].trim().toLowerCase().startsWith(a.lname);
    return true;
  }
  const choosesBg = (c) => /choose a background/i.test(c.o);
  return ((a.cmd & CMD.BACKGROUND) !== 0 && choosesBg(b)) || ((b.cmd & CMD.BACKGROUND) !== 0 && choosesBg(a))
    || (/doctor's companion/i.test(a.o) && /\bdoctor\b/i.test(b.t)) || (/doctor's companion/i.test(b.o) && /\bdoctor\b/i.test(a.t));
}

// ------------------------------------------------------------------ checks

/**
 * The deck's rule problems, worst first, plus per-card notes for the deck list.
 * Forge's own verdict (from the host) is shown next to these.
 */
export function checkDeck(deck) {
  const F = deck.format;
  /** @type {{level:'error'|'warn', text:string}[]} */
  const issues = [];
  /** @type {Map<string, string>} card name -> problem */
  const bad = new Map();
  const main = deck.count('main'), side = deck.count('side'), cmdCount = deck.count('commander');
  const all = [...deck.sections.commander, ...deck.sections.main, ...deck.sections.side];
  const unknown = all.filter((e) => !findCard(e.n)).map((e) => e.n);
  if (unknown.length) {
    issues.push({ level: 'error', text: `Unknown cards: ${[...new Set(unknown)].join(', ')}` });
    for (const n of unknown) bad.set(n, 'Not in Forge\'s card database');
  }
  if (F.cmdr) {
    const cmdrs = deck.sections.commander.map((e) => findCard(e.n)).filter(Boolean);
    if (deck.src === 'oathbreaker') {
      if (!cmdrs.some((c) => c.cmd & CMD.OATHBREAKER)) issues.push({ level: 'error', text: 'Choose an oathbreaker (a planeswalker).' });
      if (!cmdrs.some((c) => (c.cmd & CMD.SIGNATURE) && !(c.tf & T.PW))) issues.push({ level: 'error', text: 'Choose a signature spell (an instant or sorcery).' });
    } else {
      if (!cmdrs.length) issues.push({ level: 'error', text: 'Choose a commander.' });
      for (const c of cmdrs) {
        if (!(c.cmd & F.flag)) {
          issues.push({ level: 'error', text: `${c.n} can't be a ${F.t} commander.` });
          bad.set(c.n, 'Not a legal commander');
        }
      }
      if (cmdrs.length === 2 && !canPair(cmdrs[0], cmdrs[1])) issues.push({ level: 'error', text: `${cmdrs[0].n} and ${cmdrs[1].n} can't lead together (no partner/background pairing).` });
      if (cmdrs.length > 2) issues.push({ level: 'error', text: 'Too many commanders (at most two).' });
    }
    const total = main + cmdCount;
    if (total !== F.size) issues.push({ level: 'error', text: `${total} cards — a ${F.t} deck has exactly ${F.size}, commander included.` });
    const id = deck.identity();
    if (id >= 0) {
      const off = [];
      for (const e of [...deck.sections.main, ...deck.sections.side]) {
        const c = findCard(e.n);
        if (c && (c.ci & ~id) !== 0) { off.push(c.n); bad.set(c.n, 'Outside your commander\'s colors'); }
      }
      if (off.length) issues.push({ level: 'error', text: `Outside the commander's color identity: ${[...new Set(off)].join(', ')}` });
    }
  } else if (main < F.size) {
    issues.push({ level: 'error', text: `The main deck has ${main} cards — at least ${F.size}.` });
  }
  if (side > F.side) issues.push({ level: 'error', text: `The sideboard has ${side} cards — at most ${F.side}.` });
  // copy limits
  const seen = new Set();
  for (const e of all) {
    if (seen.has(e.n)) continue;
    seen.add(e.n);
    const c = findCard(e.n);
    const n = deck.copies(e.n), max = deck.maxCopies(c);
    if (c && n > max) {
      issues.push({ level: 'error', text: `${n} copies of ${e.n} — at most ${max}.` });
      bad.set(e.n, `At most ${max} ${max === 1 ? 'copy' : 'copies'}`);
    }
  }
  // banned / not legal in the format
  if (F.legal) {
    const fi = formatIndex(F.legal);
    const illegal = [];
    for (const e of all) {
      const c = findCard(e.n);
      if (c && fi >= 0 && !isLegal(c, fi)) { illegal.push(c.n); if (!bad.has(c.n)) bad.set(c.n, `Not legal in ${F.t}`); }
    }
    if (illegal.length) issues.push({ level: 'error', text: `Not legal in ${F.t}: ${[...new Set(illegal)].join(', ')}` });
  }
  const aiBad = [...new Set(all.map((e) => findCard(e.n)).filter((c) => c && (c.ai & 1)).map((c) => c.n))];
  if (aiBad.length) issues.push({ level: 'warn', text: `The AI plays these poorly (fine for your own deck): ${aiBad.join(', ')}` });
  return { issues, bad };
}

// ------------------------------------------------------------------ statistics

const LN_FACT = [0];
function lnFact(n) {
  for (let i = LN_FACT.length; i <= n; i++) LN_FACT[i] = LN_FACT[i - 1] + Math.log(i);
  return LN_FACT[n];
}
const lnChoose = (n, k) => lnFact(n) - lnFact(k) - lnFact(n - k);

/** Probability of exactly k successes when drawing n from N cards containing K successes. */
export function hyper(N, K, n, k) {
  if (k < 0 || k > n || k > K || n - k > N - K || n > N) return 0;
  return Math.exp(lnChoose(K, k) + lnChoose(N - K, n - k) - lnChoose(N, n));
}

export function atLeast(N, K, n, k) {
  let p = 0;
  for (let i = k; i <= Math.min(n, K); i++) p += hyper(N, K, n, i);
  return Math.min(1, p);
}

export function pipsOf(mc) {
  const out = { W: 0, U: 0, B: 0, R: 0, G: 0 };
  for (const m of String(mc || '').matchAll(/\{([^}]+)\}/g)) for (const ch of m[1]) if (ch in out) out[ch]++;
  return out;
}

/** Colors a land can produce (a heuristic over its types and rules text). */
export function landColors(c) {
  let m = 0;
  const t = c.t;
  if (/\bPlains\b/.test(t)) m |= 1;
  if (/\bIsland\b/.test(t)) m |= 2;
  if (/\bSwamp\b/.test(t)) m |= 4;
  if (/\bMountain\b/.test(t)) m |= 8;
  if (/\bForest\b/.test(t)) m |= 16;
  const o = c.o;
  if (/any color|mana of any type|any one color|any combination of colors/i.test(o)) m |= 31;
  for (const a of o.matchAll(/add[^.]*\./gi)) for (const s of a[0].matchAll(/\{([WUBRG])\}/g)) m |= COLOR_BITS[s[1]];
  const search = /search your library for[^.]*\./i.exec(o);
  if (search) {
    const s = search[0];
    if (/basic land card/i.test(s) && !/(Plains|Island|Swamp|Mountain|Forest) card/.test(s)) m |= 31;
    if (/Plains/.test(s)) m |= 1;
    if (/Island/.test(s)) m |= 2;
    if (/Swamp/.test(s)) m |= 4;
    if (/Mountain/.test(s)) m |= 8;
    if (/Forest/.test(s)) m |= 16;
  }
  return m;
}

export const TYPE_GROUPS = [
  ['Creatures', T.CREATURE], ['Planeswalkers', T.PW], ['Battles', T.BATTLE], ['Instants', T.INSTANT], ['Sorceries', T.SORCERY],
  ['Artifacts', T.ARTIFACT], ['Enchantments', T.ENCH], ['Lands', T.LAND],
];

/** Group name of a card for the deck list (the first matching type, lands last). */
export function groupOf(c) {
  if (!c) return 'Other';
  if (c.tf & T.LAND && !(c.tf & T.CREATURE)) return 'Lands';
  for (const [g, f] of TYPE_GROUPS) if (c.tf & f) return g;
  return 'Other';
}

/** Numbers for the statistics tab; `section` is the part of the deck that is played (main). */
export function deckStats(deck) {
  const rows = deck.sections.main.map((e) => ({ e, c: findCard(e.n) })).filter((x) => x.c);
  const total = deck.count('main');
  let lands = 0, mvSum = 0, spells = 0;
  const curve = Array.from({ length: 8 }, () => ({ creatures: 0, other: 0 }));
  const types = {};
  const pips = { W: 0, U: 0, B: 0, R: 0, G: 0 };
  const sources = { W: 0, U: 0, B: 0, R: 0, G: 0 };
  for (const { e, c } of rows) {
    const g = groupOf(c);
    types[g] = (types[g] || 0) + e.q;
    if (c.tf & T.LAND && !(c.tf & T.CREATURE)) {
      lands += e.q;
      const m = landColors(c);
      for (const [k, bit] of Object.entries(COLOR_BITS)) if (m & bit) sources[k] += e.q;
      continue;
    }
    spells += e.q;
    mvSum += c.cmc * e.q;
    const slot = curve[Math.min(7, Math.floor(c.cmc))];
    if (c.tf & T.CREATURE) slot.creatures += e.q; else slot.other += e.q;
    const p = pipsOf(c.mc);
    for (const k of Object.keys(pips)) pips[k] += p[k] * e.q;
  }
  // library the draws come from: the main deck (the commander starts in the command zone)
  const N = total;
  const opening = Array.from({ length: 8 }, (_, k) => hyper(N, lands, Math.min(7, N), k));
  const drops = [1, 2, 3, 4, 5, 6].map((turn) => ({
    turn,
    play: atLeast(N, lands, Math.min(N, 6 + turn), turn),
    draw: atLeast(N, lands, Math.min(N, 7 + turn), turn),
  }));
  return { total, lands, spells, avgMv: spells ? mvSum / spells : 0, curve, types, pips, sources, opening, drops };
}

// ------------------------------------------------------------------ text import / export

function sectionOf(h) {
  const k = h.trim().toLowerCase().replace(/[:\s]+$/, '').replace(/\s*\(\d+\)$/, '');
  if (/^(commanders?|command zone|oathbreaker|signature spell)$/.test(k)) return 'commander';
  if (/^(main|maindeck|main deck|deck|mainboard|library)$/.test(k)) return 'main';
  if (/^(sideboard|side|sb|companion)$/.test(k)) return 'side';
  if (/^(maybeboard|maybe|considering|tokens?|attractions|stickers)$/.test(k)) return 'skip';
  return null;
}

/**
 * Reads a pasted decklist: "4 Lightning Bolt", "4x Lightning Bolt", "1 Sol Ring (C21) 263" (Arena),
 * "SB: 2 Duress" (MTGO), Forge .dck files ("1 Name|SET|1" under [Main]/[Commander]), section headers,
 * and Archidekt/Moxfield "*CMDR*" markers.
 */
export function parseDeckText(text) {
  /** @type {{section:Section, card:any, qty:number, set:string, art:number}[]} */
  const entries = [];
  const unknown = [];
  let section = 'main';
  let name = '';
  for (const raw of String(text || '').split(/\r?\n/)) {
    let line = raw.trim();
    if (!line) continue;
    const bracket = /^\[(.+)\]$/.exec(line);
    if (bracket) {
      section = bracket[1].toLowerCase() === 'metadata' ? 'meta' : sectionOf(bracket[1]) || 'skip';
      continue;
    }
    if (section === 'meta') {
      const m = /^name\s*=\s*(.+)$/i.exec(line);
      if (m) name = m[1].trim();
      continue;
    }
    if (/^(\/\/|#)/.test(line)) {
      const s = sectionOf(line.replace(/^(\/\/|#)\s*/, ''));
      if (s) section = s;
      continue;
    }
    if (!/^\d/.test(line)) {
      const s = sectionOf(line);
      if (s) { section = s; continue; }
      if (/^name\s*[:=]/i.test(line)) { name = line.replace(/^name\s*[:=]\s*/i, ''); continue; }
    }
    if (section === 'skip') continue;
    let target = section;
    if (/^sb:\s*/i.test(line)) { target = 'side'; line = line.replace(/^sb:\s*/i, ''); }
    let qty = 1;
    const qm = /^(\d+)\s*[xX]?\s+(.+)$/.exec(line);
    if (qm) { qty = parseInt(qm[1], 10); line = qm[2]; }
    if (/\*cmdr\*/i.test(line)) target = 'commander';
    line = line.replace(/\s*\*[^*]*\*/g, '').replace(/\s+#\S+/g, '').trim(); // markers and tags
    let set = '', art = 0;
    let m = /^(.+?)\|([A-Za-z0-9_]+)(?:\|(\d+))?/.exec(line);
    if (m) { line = m[1]; set = m[2]; art = m[3] ? parseInt(m[3], 10) : 0; }
    m = /^(.+?)\s+\(([A-Za-z0-9]{2,6})\)(?:\s+[\w-]+)?\s*$/.exec(line);
    if (m) { line = m[1]; set = m[2]; }
    m = /^(.+?)\s+\[([A-Za-z0-9]{2,6})\]\s*$/.exec(line);
    if (m) { line = m[1]; set = m[2]; }
    const card = findCard(line);
    if (!card) { unknown.push(raw.trim()); continue; }
    const si = set ? catalog.setIndex.get(set.toUpperCase()) : undefined;
    entries.push({ section: /** @type {Section} */ (target), card, qty, set: si !== undefined && card.ss.includes(si) ? catalog.sets[si].code : '', art: si !== undefined ? art : 0 });
  }
  return { entries, unknown, name };
}

/** @param {'plain'|'sets'|'forge'} style */
export function exportText(deck, style) {
  const fmt = (e) => (style === 'forge' ? `${e.q} ${e.n}|${e.s}${e.a ? '|' + e.a : ''}` : style === 'sets' && e.s ? `${e.q} ${e.n} (${e.s})` : `${e.q} ${e.n}`);
  const { commander, main, side } = deck.sections;
  const lines = [];
  if (style === 'forge') {
    lines.push('[metadata]', `Name=${deck.name}`);
    if (commander.length) lines.push('[Commander]', ...commander.map(fmt));
    lines.push('[Main]', ...main.map(fmt));
    if (side.length) lines.push('[Sideboard]', ...side.map(fmt));
  } else {
    if (commander.length) lines.push('Commander', ...commander.map(fmt), '');
    lines.push('Deck', ...main.map(fmt));
    if (side.length) lines.push('', 'Sideboard', ...side.map(fmt));
  }
  return lines.join('\n');
}
