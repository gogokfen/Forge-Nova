// @ts-check
// The deck builder's card catalog: every card Forge knows, searched and sorted in the browser.

import { api } from '../net.js';

/**
 * One card (one per card name; `s`/`img`/`r` describe the default printing).
 * @typedef {{n:string, mc:string, cmc:number, c:number, ci:number, t:string, tf:number, o:string, pt:string,
 *   loy:string, b:any[]|null, r:string, s:string, img:string, ss:number[], lg:number, cmd:number, ai:number,
 *   i:number, nc:number, lname:string, ltype:string, ltext:string, pow:number, tou:number, date:string}} Card
 */

/** Type flags, shared with the board (store.js TF) plus deck-building extras. */
export const T = { CREATURE: 1, LAND: 2, PW: 4, ARTIFACT: 8, ENCH: 16, INSTANT: 32, SORCERY: 64, BATTLE: 128, BASIC: 256, LEGENDARY: 1024, SNOW: 2048, KINDRED: 4096 };
/** Commander-eligibility flags from the host. */
export const CMD = { COMMANDER: 1, PARTNER: 2, BACKGROUND: 4, BRAWL: 8, OATHBREAKER: 16, SIGNATURE: 32, TINY: 64 };
export const COLOR_BITS = { W: 1, U: 2, B: 4, R: 8, G: 16 };
export const RARITY_ORDER = { L: 0, C: 1, U: 2, R: 3, M: 4, S: 5, '?': 6 };

export const catalog = {
  /** @type {Card[]} sorted by name */ cards: [],
  /** @type {Map<string, Card>} lower-case name (and face names) -> card */ byName: new Map(),
  /** @type {string[]} */ formats: [],
  /** @type {{code:string, name:string, date:string, type:string}[]} */ sets: [],
  /** @type {Map<string, number>} set code (upper case) -> index */ setIndex: new Map(),
  ready: false,
};

/** @type {Promise<typeof catalog>|null} */
let loading = null;

export function loadCatalog() {
  if (!loading) {
    loading = api('cards').then((data) => { build(data); return catalog; }).catch((e) => { loading = null; throw e; });
  }
  return loading;
}

function popcount(m) {
  let n = 0;
  for (let x = m; x; x >>= 1) n += x & 1;
  return n;
}

function num(s) {
  const v = parseFloat(String(s || '').replace('*', '0'));
  return Number.isFinite(v) ? v : -1;
}

function build(data) {
  catalog.formats = data.formats;
  catalog.sets = data.sets.map(([code, name, date, type]) => ({ code, name, date, type }));
  catalog.sets.forEach((s, i) => catalog.setIndex.set(String(s.code).toUpperCase(), i));
  const f = data.fields;
  const at = Object.fromEntries(f.map((k, i) => [k, i]));
  catalog.cards = data.cards.map((row, i) => {
    /** @type {any} */
    const c = {};
    for (const k of f) c[k] = row[at[k]];
    // Forge keeps line breaks in rules text as a literal "\n"
    c.o = String(c.o || '').replace(/\\n/g, '\n');
    if (c.b) c.b[2] = String(c.b[2] || '').replace(/\\n/g, '\n');
    c.i = i;
    c.nc = popcount(c.c);
    c.lname = c.n.toLowerCase();
    c.ltype = (c.t + (c.b ? ' // ' + c.b[1] : '')).toLowerCase();
    c.ltext = (c.o + (c.b ? '\n' + c.b[0] + '\n' + c.b[2] : '')).toLowerCase();
    const [p, t] = String(c.pt || '').split('/');
    c.pow = c.pt ? num(p) : -1;
    c.tou = c.pt ? num(t) : -1;
    c.date = catalog.sets[catalog.setIndex.get(String(c.s).toUpperCase()) ?? -1]?.date || '';
    // Alchemy rebalanced ("A-") cards, and cards printed only in online sets
    c.digital = c.n.startsWith('A-') || (c.ss.length > 0 && c.ss.every((si) => catalog.sets[si]?.type === 'ONLINE'));
    return /** @type {Card} */ (c);
  });
  for (const c of catalog.cards) {
    catalog.byName.set(c.lname, c);
    // "Fire // Ice" is also found as "Fire" and "Ice"; a transforming card by its back face's name
    if (c.n.includes(' // ')) for (const part of c.lname.split(' // ')) if (!catalog.byName.has(part)) catalog.byName.set(part, c);
    if (c.b && c.b[0] && !catalog.byName.has(c.b[0].toLowerCase())) catalog.byName.set(c.b[0].toLowerCase(), c);
  }
  catalog.ready = true;
}

/** Finds a card by (loosely written) name. */
export function findCard(name) {
  const k = String(name || '').trim().toLowerCase().replace(/\s*\/\/?\s*/g, ' // ').replace(/[’`]/g, "'");
  return catalog.byName.get(k) || catalog.byName.get(k.replace(/ \/\/ .*$/, '')) || null;
}

export function formatIndex(name) {
  const k = String(name || '').toLowerCase().replace(/[\s_-]/g, '');
  const alias = { edh: 'commander', cmdr: 'commander', tiny: 'tinyleaders', tl: 'tinyleaders' };
  const want = alias[k] || k;
  return catalog.formats.findIndex((f) => f.toLowerCase().replace(/\s/g, '') === want);
}

export const isLegal = (c, fmt) => fmt < 0 || ((c.lg >> fmt) & 1) === 1;

// ------------------------------------------------------------------ search syntax (a Scryfall-like subset)

const COLOR_WORDS = {
  white: 'w', blue: 'u', black: 'b', red: 'r', green: 'g', colorless: 'c', multicolor: 'm',
  azorius: 'wu', dimir: 'ub', rakdos: 'br', gruul: 'rg', selesnya: 'gw', orzhov: 'wb', izzet: 'ur', golgari: 'bg', boros: 'rw', simic: 'gu',
  esper: 'wub', grixis: 'ubr', jund: 'brg', naya: 'rgw', bant: 'gwu', abzan: 'wbg', jeskai: 'urw', sultai: 'bgu', mardu: 'rwb', temur: 'gur',
  wubrg: 'wubrg', rainbow: 'wubrg',
};

function parseColors(v) {
  const s = COLOR_WORDS[v] || v;
  let mask = 0, colorless = false, multi = false;
  for (const ch of s) {
    if (ch === 'c') colorless = true;
    else if (ch === 'm') multi = true;
    else if (COLOR_BITS[ch.toUpperCase()]) mask |= COLOR_BITS[ch.toUpperCase()];
  }
  return { mask, colorless, multi };
}

function colorTest(field, op, v, identity) {
  const { mask, colorless, multi } = parseColors(v);
  if (multi) return (c) => popcount(c[field]) >= 2;
  if (colorless && !mask) return (c) => c[field] === 0;
  // Scryfall: c: = "at least these colors", id: = "fits a deck of these colors"
  const eff = op === ':' ? (identity ? '<=' : '>=') : op;
  switch (eff) {
    case '=': return (c) => c[field] === mask;
    case '!=': return (c) => c[field] !== mask;
    case '<=': return (c) => (c[field] & ~mask) === 0;
    case '<': return (c) => (c[field] & ~mask) === 0 && c[field] !== mask;
    case '>': return (c) => (c[field] & mask) === mask && c[field] !== mask;
    default: return (c) => (c[field] & mask) === mask;
  }
}

function numTest(get, op, v) {
  const n = num(v);
  switch (op) {
    case '<': return (c) => get(c) >= 0 && get(c) < n;
    case '<=': return (c) => get(c) >= 0 && get(c) <= n;
    case '>': return (c) => get(c) > n;
    case '>=': return (c) => get(c) >= n;
    case '!=': return (c) => get(c) !== n;
    default: return (c) => get(c) === n;
  }
}

function rarityTest(op, v) {
  const r = { c: 'C', common: 'C', u: 'U', uncommon: 'U', r: 'R', rare: 'R', m: 'M', mythic: 'M', s: 'S', special: 'S', l: 'L', land: 'L' }[v] || v.toUpperCase();
  const want = RARITY_ORDER[r] ?? -1;
  const get = (c) => RARITY_ORDER[c.r] ?? 6;
  switch (op) {
    case '<': return (c) => get(c) < want;
    case '<=': return (c) => get(c) <= want;
    case '>': return (c) => get(c) > want && get(c) <= 4;
    case '>=': return (c) => get(c) >= want && get(c) <= 4;
    case '!=': return (c) => c.r !== r;
    default: return (c) => c.r === r;
  }
}

const IS = {
  commander: (c) => (c.cmd & CMD.COMMANDER) !== 0,
  partner: (c) => (c.cmd & CMD.PARTNER) !== 0,
  background: (c) => (c.cmd & CMD.BACKGROUND) !== 0,
  oathbreaker: (c) => (c.cmd & CMD.OATHBREAKER) !== 0,
  signature: (c) => (c.cmd & CMD.SIGNATURE) !== 0,
  legendary: (c) => (c.tf & T.LEGENDARY) !== 0,
  basic: (c) => (c.tf & T.BASIC) !== 0,
  snow: (c) => (c.tf & T.SNOW) !== 0,
  kindred: (c) => (c.tf & T.KINDRED) !== 0,
  tribal: (c) => (c.tf & T.KINDRED) !== 0,
  permanent: (c) => (c.tf & (T.CREATURE | T.LAND | T.PW | T.ARTIFACT | T.ENCH | T.BATTLE)) !== 0,
  spell: (c) => (c.tf & T.LAND) === 0,
  multicolor: (c) => c.nc >= 2,
  multi: (c) => c.nc >= 2,
  mono: (c) => c.nc === 1,
  colorless: (c) => c.c === 0,
  dfc: (c) => !!c.b,
  mdfc: (c) => !!c.b,
  transform: (c) => !!c.b,
  split: (c) => c.n.includes(' // '),
  vanilla: (c) => !c.o.trim(),
  aibad: (c) => (c.ai & 1) !== 0,
  aiok: (c) => (c.ai & 1) === 0,
  digital: (c) => c.digital,
  alchemy: (c) => c.digital,
  paper: (c) => !c.digital,
};

/** Splits a query into terms: [-]key(op)value or plain words; "quoted phrases" keep spaces. */
export function tokenize(q) {
  const out = [];
  const re = /(-)?(?:([a-z]+)(:|!=|<=|>=|=|<|>))?("([^"]*)"?|[^\s]+)/gi;
  let m;
  while ((m = re.exec(q))) {
    if (!m[0].trim()) continue;
    out.push({ neg: !!m[1], key: (m[2] || '').toLowerCase(), op: m[3] || '', val: (m[5] !== undefined ? m[5] : m[4]).toLowerCase() });
  }
  return out;
}

/**
 * Compiles a search query into a predicate.
 * @param {string} q
 * @param {'nametype'|'all'|'name'} searchIn what plain words are matched against
 */
export function compileQuery(q, searchIn = 'nametype') {
  const tests = [];
  for (const t of tokenize(q)) {
    const v = t.val;
    let test;
    switch (t.key) {
      case '':
        test = searchIn === 'all' ? (c) => c.lname.includes(v) || c.ltype.includes(v) || c.ltext.includes(v)
          : searchIn === 'name' ? (c) => c.lname.includes(v)
            : (c) => c.lname.includes(v) || c.ltype.includes(v);
        break;
      case 't': case 'type': test = (c) => c.ltype.includes(v); break;
      case 'o': case 'oracle': case 'text': case 'kw': case 'keyword': test = (c) => c.ltext.includes(v); break;
      case 'n': case 'name': test = (c) => c.lname.includes(v); break;
      case 'c': case 'color': case 'colors': test = colorTest('c', t.op, v, false); break;
      case 'id': case 'ci': case 'identity': case 'commander': test = colorTest('ci', t.op, v, true); break;
      case 'mv': case 'cmc': case 'manavalue': test = numTest((c) => c.cmc, t.op, v); break;
      case 'pow': case 'power': test = numTest((c) => c.pow, t.op, v); break;
      case 'tou': case 'toughness': test = numTest((c) => c.tou, t.op, v); break;
      case 'loy': case 'loyalty': test = numTest((c) => num(c.loy), t.op, v); break;
      case 'r': case 'rarity': test = rarityTest(t.op, v); break;
      case 's': case 'set': case 'e': case 'edition': {
        const si = catalog.setIndex.get(v.toUpperCase());
        test = si === undefined ? () => false : (c) => c.ss.includes(si);
        break;
      }
      case 'f': case 'format': case 'legal': {
        const fi = formatIndex(v);
        test = fi < 0 ? () => false : (c) => isLegal(c, fi);
        break;
      }
      case 'banned': {
        const fi = formatIndex(v);
        test = fi < 0 ? () => false : (c) => !isLegal(c, fi);
        break;
      }
      case 'is': case 'has': test = IS[v] || (() => false); break;
      default: {
        const whole = `${t.key}${t.op}${v}`;
        test = (c) => c.lname.includes(whole);
      }
    }
    tests.push(t.neg ? (c) => !test(c) : test);
  }
  return tests.length ? (c) => tests.every((fn) => fn(c)) : null;
}

/**
 * Button filters of the catalog panel.
 * @typedef {{q:string, searchIn:'nametype'|'all'|'name', colors:string[], colorMode:'any'|'exact'|'atmost', multi:boolean,
 *   types:string[], mv:number[], format:number, rarity:string[], set:string, identity:number, commanders:number, hideAiBad:boolean,
 *   paperOnly?:boolean}} Filter
 */

const TYPE_FLAG = { creature: T.CREATURE, planeswalker: T.PW, instant: T.INSTANT, sorcery: T.SORCERY, artifact: T.ARTIFACT, enchantment: T.ENCH, land: T.LAND, battle: T.BATTLE };

/** @param {Filter} f */
export function filterCards(f) {
  const tests = [];
  const query = compileQuery(f.q || '', f.searchIn);
  if (query) tests.push(query);
  if (f.colors.length || f.multi) {
    let mask = 0;
    for (const ch of f.colors) if (COLOR_BITS[ch]) mask |= COLOR_BITS[ch];
    const colorless = f.colors.includes('C');
    if (f.colors.length) {
      if (f.colorMode === 'exact') tests.push((c) => (colorless && !mask ? c.c === 0 : c.c === mask));
      else if (f.colorMode === 'atmost') tests.push((c) => (c.c & ~mask) === 0 && (colorless || c.c !== 0 || !mask));
      else tests.push((c) => (c.c & mask) !== 0 || (colorless && c.c === 0));
    }
    if (f.multi) tests.push((c) => c.nc >= 2);
  }
  if (f.types.length) {
    let tf = 0;
    for (const t of f.types) tf |= TYPE_FLAG[t] || 0;
    tests.push((c) => (c.tf & tf) !== 0);
  }
  if (f.mv.length) {
    const set = new Set(f.mv);
    tests.push((c) => set.has(Math.min(7, Math.floor(c.cmc))));
  }
  if (f.format >= 0) tests.push((c) => isLegal(c, f.format));
  if (f.rarity.length) {
    const set = new Set(f.rarity);
    tests.push((c) => set.has(c.r));
  }
  if (f.set) {
    const si = catalog.setIndex.get(f.set.toUpperCase());
    tests.push(si === undefined ? () => false : (c) => c.ss.includes(si));
  }
  if (f.identity >= 0) tests.push((c) => (c.ci & ~f.identity) === 0);
  if (f.commanders) tests.push((c) => (c.cmd & f.commanders) !== 0);
  if (f.hideAiBad) tests.push((c) => (c.ai & 1) === 0);
  if (f.paperOnly) tests.push((c) => !c.digital);
  if (!tests.length) return catalog.cards;
  return catalog.cards.filter((c) => tests.every((fn) => fn(c)));
}

// ------------------------------------------------------------------ sorting

/** W, U, B, R, G, multicolor, colorless — like Forge's lists. */
function colorKey(c) {
  if (c.nc === 0) return 7;
  if (c.nc > 1) return 6;
  return [1, 2, 4, 8, 16].indexOf(c.c) + 1;
}

export function typeKey(c) {
  const tf = c.tf;
  if (tf & T.CREATURE) return 0;
  if (tf & T.PW) return 1;
  if (tf & T.BATTLE) return 2;
  if (tf & T.INSTANT) return 3;
  if (tf & T.SORCERY) return 4;
  if (tf & T.ARTIFACT) return 5;
  if (tf & T.ENCH) return 6;
  if (tf & T.LAND) return 8;
  return 7;
}

export const SORTS = {
  name: { label: 'Name', key: null },
  mv: { label: 'Mana value', key: (c) => c.cmc },
  color: { label: 'Color', key: colorKey },
  type: { label: 'Type', key: typeKey },
  rarity: { label: 'Rarity', key: (c) => RARITY_ORDER[c.r] ?? 6 },
  newest: { label: 'Release date', key: (c) => c.date },
  power: { label: 'Power', key: (c) => c.pow },
  toughness: { label: 'Toughness', key: (c) => c.tou },
};

/**
 * For plain-word searches in name order: the exact name first, then names starting with the
 * search, then names containing it, then the rest (matches by type or text).
 */
export function rankByName(list, q) {
  const phrase = String(q || '').trim().toLowerCase();
  if (!phrase || /[:=<>"]|(^|\s)-/.test(phrase)) return list;
  const rank = (c) => (c.lname === phrase ? 0 : c.lname.startsWith(phrase) ? 1 : c.lname.includes(phrase) ? 2 : 3);
  return list.map((c) => [rank(c), c]).sort((a, b) => a[0] - b[0] || a[1].i - b[1].i).map((x) => x[1]);
}

/** Sorts a result list; ties fall back to name order (the catalog's own order). */
export function sortCards(list, sort, desc) {
  const key = SORTS[sort]?.key;
  const out = list.slice();
  if (key) {
    out.sort((a, b) => {
      const ka = key(a), kb = key(b);
      const d = ka < kb ? -1 : ka > kb ? 1 : 0;
      return (desc ? -d : d) || a.i - b.i;
    });
  } else if (desc) {
    out.reverse();
  }
  return out;
}
