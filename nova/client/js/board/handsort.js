// @ts-check
// Hand auto-sort: rules in priority order (card type in a chosen left-to-right order, mana value, color, name).
// The first rule decides; each later rule only orders cards the earlier ones consider equal.

import { TF } from '../store.js';

/**
 * Card types in the default left-to-right order. A card with several types counts as the first one
 * that matches in this list, so an artifact creature is a creature and Dryad Arbor is a land.
 */
export const HAND_TYPES = [
  { k: 'land', label: 'Lands', one: 'Land', flag: TF.LAND },
  { k: 'creature', label: 'Creatures', one: 'Creature', flag: TF.CREATURE },
  { k: 'planeswalker', label: 'Planes­walkers', one: 'Planeswalker', flag: TF.PW },
  { k: 'battle', label: 'Battles', one: 'Battle', flag: TF.BATTLE },
  { k: 'artifact', label: 'Artifacts', one: 'Artifact', flag: TF.ARTIFACT },
  { k: 'enchantment', label: 'Enchant­ments', one: 'Enchantment', flag: TF.ENCH },
  { k: 'instant', label: 'Instants', one: 'Instant', flag: TF.INSTANT },
  { k: 'sorcery', label: 'Sorceries', one: 'Sorcery', flag: TF.SORCERY },
];

/** The sort rules; `dirs` names both directions (the card type rule follows the type order instead). */
export const HAND_RULES = {
  type: { label: 'Card type', dirs: null },
  mv: { label: 'Mana value', dirs: ['Low → high', 'High → low'] },
  color: { label: 'Color', dirs: ['W U B R G · multi · colorless', 'Colorless · multi · G R B U W'] },
  name: { label: 'Name', dirs: ['A → Z', 'Z → A'] },
};

function defaults() {
  return {
    types: HAND_TYPES.map((t) => t.k),
    rules: [{ k: 'type', on: true, desc: false }, { k: 'mv', on: true, desc: false }, { k: 'color', on: false, desc: false }, { k: 'name', on: true, desc: false }],
  };
}

/** A complete rule set from saved settings: null means the default; unknown parts are dropped and missing ones added. */
export function handSortRules(saved) {
  const def = defaults();
  const types = (Array.isArray(saved?.types) ? saved.types : []).filter((k, i, a) => def.types.includes(k) && a.indexOf(k) === i);
  for (const k of def.types) if (!types.includes(k)) types.push(k);
  const known = Object.keys(HAND_RULES);
  const rules = (Array.isArray(saved?.rules) ? saved.rules : [])
    .filter((r, i, a) => known.includes(r?.k) && a.findIndex((x) => x?.k === r.k) === i)
    .map((r) => ({ k: r.k, on: !!r.on, desc: !!r.desc }));
  for (const r of def.rules) if (!rules.some((x) => x.k === r.k)) rules.push(r);
  return { types, rules };
}

/** The type group a card is sorted with, or undefined for hidden and unusual cards (they go last). */
export function handTypeOf(c) {
  const tf = c.tf || 0;
  return HAND_TYPES.find((t) => tf & t.flag);
}

const COLOR_RANK = { W: 0, U: 1, B: 2, R: 3, G: 4 };
function colorRank(c) {
  const col = c.col || '';
  return col.length > 1 ? 5 : COLOR_RANK[col] ?? 6;
}

function compile(saved) {
  const { types, rules } = handSortRules(saved);
  const typeRank = (c) => { const i = types.indexOf(handTypeOf(c)?.k || ''); return i < 0 ? types.length : i; };
  /** @type {((a:any, b:any) => number)[]} */
  const parts = rules.filter((r) => r.on).map((r) => {
    const s = r.desc ? -1 : 1;
    if (r.k === 'type') return (a, b) => typeRank(a) - typeRank(b);
    if (r.k === 'mv') return (a, b) => s * ((a.cmc || 0) - (b.cmc || 0));
    if (r.k === 'color') return (a, b) => s * (colorRank(a) - colorRank(b));
    return (a, b) => s * String(a.n || '').localeCompare(String(b.n || ''));
  });
  return (a, b) => {
    for (const f of parts) {
      const d = f(a, b);
      if (d) return d;
    }
    return 0;
  };
}

let compiled = { saved: /** @type {any} */ (undefined), cmp: compile(null) };

/** The hand in auto-sort order; cards the rules consider equal keep their current order. */
export function sortHand(cards, saved) {
  if (compiled.saved !== saved) compiled = { saved, cmp: compile(saved) };
  return cards.slice().sort(compiled.cmp);
}
