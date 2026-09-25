// @ts-check
// Modal dialogs for the engine's blocking questions (choose, order, confirm, assign damage...).

import { on, send, imgUrl } from '../net.js';
import { store } from '../store.js';
import { esc, el, manaHtml } from './text.js';
import { faceCanvas } from '../gl/cardface.js';

/** @type {Map<number, {close: () => void}>} */
const open = new Map();
const root = () => /** @type {HTMLElement} */ (document.getElementById('dialogs'));

/** A card image element with procedural fallback. */
export function cardFaceEl(card, cls = 'face') {
  const d = el(`<div class="${cls}"></div>`);
  const hidden = !card || card.hid && !card.img;
  if (hidden) {
    const owner = card && store.players.get(card.c ?? card.o);
    d.style.backgroundImage = `url(/sleeve/${owner?.sl ?? 0}.png)`;
    return d;
  }
  if (card.img) {
    const url = imgUrl(card.img);
    const probe = new Image();
    probe.onload = () => { d.style.backgroundImage = `url("${url}")`; };
    probe.onerror = () => { if (!d.firstChild) d.appendChild(faceCanvas(card)); };
    probe.src = url;
    // show the procedural face until the real image is there
    d.appendChild(faceCanvas(card));
    probe.addEventListener('load', () => d.replaceChildren());
  } else {
    d.appendChild(faceCanvas(card));
  }
  return d;
}

function reply(id, v) {
  send({ t: 'reply', id, v });
  close(id);
}

function close(id) {
  const d = open.get(id);
  if (d) {
    d.close();
    open.delete(id);
  }
}

/** Windows that Esc may close, most recent last (the engine's questions are not among them). */
/** @type {{back: HTMLElement, onEsc: () => void}[]} */
const escStack = [];

/**
 * Generic modal shell. With `onEsc`, the Esc key calls it while this is the topmost such window.
 * @param {string} title
 * @param {{wide?: boolean, narrow?: boolean, peek?: boolean, onEsc?: () => void}} [opts]
 * @returns {{back:HTMLElement, modal:HTMLElement, body:HTMLElement, foot:HTMLElement, close:() => void}}
 */
export function modal(title, { wide = false, narrow = false, peek = true, onEsc } = {}) {
  const back = el(`<div class="modal-back"><div class="modal ${wide ? 'wide' : ''} ${narrow ? 'narrow' : ''}">
    <div class="mhead"><div class="title">${manaHtml(title || '')}</div>
      ${peek ? '<button class="btn ghost small" data-peek title="Hide this dialog to look at the board">Peek</button>' : ''}</div>
    <div class="mbody"></div><div class="mfoot"></div></div></div>`);
  root().appendChild(back);
  const m = /** @type {HTMLElement} */ (back.querySelector('.modal'));
  back.querySelector('[data-peek]')?.addEventListener('click', () => {
    back.classList.toggle('peek');
    const b = /** @type {HTMLElement} */ (back.querySelector('[data-peek]'));
    b.textContent = back.classList.contains('peek') ? 'Show' : 'Peek';
  });
  const entry = onEsc ? { back, onEsc } : null;
  if (entry) escStack.push(entry);
  return {
    back, modal: m,
    body: /** @type {HTMLElement} */ (m.querySelector('.mbody')),
    foot: /** @type {HTMLElement} */ (m.querySelector('.mfoot')),
    close: () => {
      back.remove();
      const i = entry ? escStack.indexOf(entry) : -1;
      if (i >= 0) escStack.splice(i, 1);
    },
  };
}

/** Esc: closes the topmost closable window; false when there is none. */
export function closeTopWindow() {
  while (escStack.length) {
    const top = /** @type {{back: HTMLElement, onEsc: () => void}} */ (escStack.pop());
    if (top.back.isConnected) {
      top.onEsc();
      return true;
    }
  }
  return false;
}

/** Leaving a match: drops every window, including ones that were waiting for an answer. */
export function closeAllWindows() {
  closeAllDialogs();
  escStack.length = 0;
  root().replaceChildren();
}

function button(label, cls, fn) {
  const b = el(`<button class="btn ${cls}">${esc(label)}</button>`);
  b.addEventListener('click', fn);
  return b;
}

// ---------------------------------------------------------------- choose

function showChoose(m) {
  const { id, items, min, max } = m;
  const reveal = min < 0;
  const single = max === 1 || reveal;
  const hasCards = items.some((it) => it.card);
  const selected = new Set(m.selected || []);
  const dlg = modal(m.title || (reveal ? 'Revealed' : 'Choose'), { wide: hasCards && items.length > 4 });
  open.set(id, dlg);
  const ok = button(reveal ? 'OK' : 'OK', 'primary', () => reply(id, [...selected].sort((a, b) => a - b)));
  const count = el('<span class="count"></span>');
  const refresh = () => {
    const n = selected.size;
    ok.disabled = !reveal && (n < Math.max(0, min) || (max > 0 && n > max));
    count.textContent = reveal ? '' : max > 1 || max < 0 ? `${n} selected${min > 0 ? ` (min ${min}${max > 0 ? `, max ${max}` : ''})` : max > 0 ? ` (max ${max})` : ''}` : '';
    dlg.body.querySelectorAll('[data-i]').forEach((node) => {
      node.classList.toggle('sel', selected.has(Number(/** @type {HTMLElement} */ (node).dataset.i)));
    });
  };
  const toggle = (i) => {
    if (reveal) return;
    if (single) {
      if (selected.has(i) && min === 0) selected.clear();
      else { selected.clear(); selected.add(i); }
    } else if (selected.has(i)) selected.delete(i);
    else if (max < 0 || selected.size < max) selected.add(i);
    refresh();
  };
  let search = null;
  if (items.length > 20) {
    search = /** @type {HTMLInputElement} */ (el('<input class="search" placeholder="Search…">'));
    dlg.body.appendChild(search);
  }
  const list = el(`<div class="${hasCards ? 'choice-cards' : 'choice-list'}"></div>`);
  items.forEach((it, i) => {
    let node;
    if (it.card) {
      node = el(`<div class="ccard" data-i="${i}"></div>`);
      node.appendChild(cardFaceEl(it.card));
      node.appendChild(el(`<div class="lbl" title="${esc(it.label)}">${esc(it.label)}</div>`));
      node.addEventListener('mouseenter', () => window.dispatchEvent(new CustomEvent('nova:detail', { detail: it.card })));
    } else {
      node = el(`<div class="choice" data-i="${i}">${manaHtml(it.label)}</div>`);
    }
    node.addEventListener('click', () => toggle(i));
    node.addEventListener('dblclick', () => {
      if (reveal) return;
      if (single) { selected.clear(); selected.add(i); refresh(); if (!ok.disabled) ok.click(); }
    });
    list.appendChild(node);
  });
  dlg.body.appendChild(list);
  if (search) {
    const s = /** @type {HTMLInputElement} */ (search);
    s.addEventListener('input', () => {
      const q = s.value.toLowerCase();
      list.querySelectorAll('[data-i]').forEach((n) => {
        const it = items[Number(/** @type {HTMLElement} */ (n).dataset.i)];
        /** @type {HTMLElement} */ (n).style.display = !q || it.label.toLowerCase().includes(q) ? '' : 'none';
      });
    });
    setTimeout(() => s.focus(), 30);
  }
  dlg.foot.append(count);
  if (min === 0 && !reveal) dlg.foot.append(button('Cancel', '', () => reply(id, [])));
  dlg.foot.append(ok);
  refresh();
}

// ---------------------------------------------------------------- order (dual list)

function showOrder(m) {
  const { id, items, remMin, remMax, srcCount } = m;
  let src = items.slice(0, srcCount).map((_, i) => i);
  let dst = items.slice(srcCount).map((_, i) => i + srcCount);
  const dlg = modal(m.title || 'Order', { wide: true });
  open.set(id, dlg);
  const wrap = el(`<div class="dual"><div class="col"><h4>Available</h4><div class="list" data-src></div></div>
     <div class="col"><h4>${esc(m.top || 'Selected (in order)')}</h4><div class="list" data-dst></div></div></div>`);
  dlg.body.appendChild(wrap);
  let remember = false;
  const ok = button('OK', 'primary', () => reply(id, { order: dst, remember }));
  const auto = button('Move all', 'small', () => { dst = dst.concat(src); src = []; render(); });
  const row = (i, inDst, pos) => {
    const it = items[i];
    const r = el(`<div class="orow">${inDst ? `<span class="ix">${pos + 1}</span>` : ''}<div class="thumb"></div><span class="sp">${manaHtml(it.label)}</span></div>`);
    if (it.card) {
      const th = /** @type {HTMLElement} */ (r.querySelector('.thumb'));
      if (it.card.img) th.style.backgroundImage = `url("${imgUrl(it.card.img)}")`;
      else th.replaceWith(Object.assign(cardFaceEl(it.card, 'thumb'), {}));
      r.addEventListener('mouseenter', () => window.dispatchEvent(new CustomEvent('nova:detail', { detail: it.card })));
    } else {
      r.querySelector('.thumb')?.remove();
    }
    if (inDst) {
      const up = el('<button class="btn ghost small mv" title="Move up">▲</button>');
      const dn = el('<button class="btn ghost small mv" title="Move down">▼</button>');
      up.addEventListener('click', (e) => { e.stopPropagation(); if (pos > 0) { [dst[pos - 1], dst[pos]] = [dst[pos], dst[pos - 1]]; render(); } });
      dn.addEventListener('click', (e) => { e.stopPropagation(); if (pos < dst.length - 1) { [dst[pos + 1], dst[pos]] = [dst[pos], dst[pos + 1]]; render(); } });
      r.append(up, dn);
      r.addEventListener('click', () => { dst.splice(pos, 1); src.push(i); render(); });
    } else {
      r.addEventListener('click', () => {
        const canAdd = remMax < 0 || src.length > remMin;
        if (!canAdd) return;
        src = src.filter((x) => x !== i);
        dst.push(i);
        render();
      });
    }
    return r;
  };
  const render = () => {
    const s = /** @type {HTMLElement} */ (wrap.querySelector('[data-src]'));
    const d = /** @type {HTMLElement} */ (wrap.querySelector('[data-dst]'));
    s.replaceChildren(...src.map((i) => row(i, false, 0)));
    d.replaceChildren(...dst.map((i, p) => row(i, true, p)));
    const n = src.length;
    ok.disabled = !(remMax < 0 || (n >= remMin && n <= remMax));
    auto.style.display = remMax === 0 && src.length ? '' : 'none';
  };
  if (m.remember) {
    const cb = el('<label class="count"><input type="checkbox"> Remember this order</label>');
    cb.querySelector('input')?.addEventListener('change', (e) => { remember = /** @type {HTMLInputElement} */ (e.target).checked; });
    dlg.foot.append(cb);
  } else dlg.foot.append(el('<span class="count"></span>'));
  dlg.foot.append(auto, ok);
  render();
}

// ---------------------------------------------------------------- option / confirm

function showOption(m) {
  const { id } = m;
  const dlg = modal(m.title || '', { narrow: !m.card });
  open.set(id, dlg);
  if (m.card) {
    const ref = el('<div class="ref-card ccard"></div>');
    ref.appendChild(cardFaceEl(m.card));
    dlg.body.appendChild(ref);
  }
  dlg.body.appendChild(el(`<div style="white-space:pre-wrap;user-select:text">${manaHtml(m.message || '')}</div>`));
  (m.options || []).forEach((o, i) => {
    const b = button(o, i === m.def ? 'primary' : '', () => reply(id, i));
    dlg.foot.append(b);
    if (i === m.def) setTimeout(() => b.focus(), 30);
  });
}

function showMessage(m) {
  const { id } = m;
  const dlg = modal(m.title || (m.error ? 'Error' : 'Forge'), { narrow: true, peek: false });
  open.set(id, dlg);
  if (m.error) dlg.modal.style.borderColor = 'rgba(239,91,91,.6)';
  dlg.body.appendChild(el(`<div style="white-space:pre-wrap;user-select:text">${manaHtml(m.message || '')}</div>`));
  const ok = button('OK', 'primary', () => reply(id, true));
  dlg.foot.append(ok);
  setTimeout(() => ok.focus(), 30);
}

function showInput(m) {
  const { id } = m;
  const dlg = modal(m.title || 'Input', { narrow: true });
  open.set(id, dlg);
  dlg.body.appendChild(el(`<div style="margin-bottom:10px;white-space:pre-wrap">${manaHtml(m.message || '')}</div>`));
  /** @type {HTMLInputElement|HTMLSelectElement} */
  let input;
  if (m.options && m.options.length) {
    input = /** @type {HTMLSelectElement} */ (el(`<select class="text-input">${m.options.map((o) => `<option ${o === m.initial ? 'selected' : ''}>${esc(o)}</option>`).join('')}</select>`));
  } else {
    input = /** @type {HTMLInputElement} */ (el(`<input class="text-input" ${m.numeric ? 'type="number"' : ''} value="${esc(m.initial || '')}">`));
  }
  dlg.body.appendChild(input);
  const ok = button('OK', 'primary', () => {
    const v = input.value;
    if (m.numeric && !/^-?\d+$/.test(v.trim())) { input.focus(); return; }
    reply(id, v);
  });
  input.addEventListener('keydown', (e) => { if (/** @type {KeyboardEvent} */ (e).key === 'Enter') ok.click(); });
  dlg.foot.append(button('Cancel', '', () => reply(id, null)), ok);
  setTimeout(() => input.focus(), 30);
}

// ---------------------------------------------------------------- damage / amounts

function stepper(value, onChange) {
  const box = el(`<div class="stepper"><button class="btn small">−</button><span class="v">${value}</span><button class="btn small">+</button></div>`);
  const [minus, plus] = box.querySelectorAll('button');
  minus.addEventListener('click', () => onChange(-1));
  plus.addEventListener('click', () => onChange(1));
  return box;
}

function showAssignDamage(m) {
  const { id, damage, blockers } = m;
  const dlg = modal(`Assign ${damage} combat damage from ${m.card?.n || 'attacker'}`, { wide: true });
  open.set(id, dlg);
  const amounts = blockers.map(() => 0);
  let toDef = 0;
  const hasDef = !!m.defender;
  // default: lethal in order, remainder to the defender (trample) or the last blocker
  let left = damage;
  blockers.forEach((b, i) => { const v = Math.min(left, Math.max(0, b.lethal)); amounts[i] = v; left -= v; });
  if (left > 0) { if (hasDef) toDef = left; else amounts[amounts.length - 1] += left; }
  const note = el('<div class="count" style="margin-bottom:10px"></div>');
  dlg.body.appendChild(note);
  const rows = el('<div></div>');
  dlg.body.appendChild(rows);
  const ok = button('OK', 'primary', () => reply(id, { blockers: amounts, defender: toDef }));
  const validate = () => {
    const sum = amounts.reduce((a, b) => a + b, 0) + toDef;
    let msg = '';
    if (sum !== damage) msg = `Assign exactly ${damage} (currently ${sum}).`;
    else if (!m.override) {
      for (let i = 0; i < blockers.length - 1; i++) {
        if (amounts[i] < blockers[i].lethal && amounts.slice(i + 1).some((x) => x > 0)) { msg = 'Each blocker must be assigned lethal damage before the next one.'; break; }
      }
      if (!msg && toDef > 0 && blockers.some((b, i) => amounts[i] < b.lethal)) msg = 'All blockers need lethal damage before trampling over.';
    }
    note.textContent = msg || `All ${damage} damage assigned.`;
    note.style.color = msg ? 'var(--red)' : 'var(--green)';
    ok.disabled = !!msg;
  };
  const render = () => {
    rows.replaceChildren();
    blockers.forEach((b, i) => {
      const r = el(`<div class="amount-row"><div class="thumb"></div><div><b>${esc(b.label)}</b><div class="count">Lethal: ${b.lethal}</div></div></div>`);
      const th = /** @type {HTMLElement} */ (r.querySelector('.thumb'));
      th.replaceWith(cardFaceEl(b.card, 'thumb'));
      r.appendChild(stepper(amounts[i], (d) => { amounts[i] = Math.max(0, amounts[i] + d); render(); }));
      rows.appendChild(r);
    });
    if (hasDef) {
      const r = el(`<div class="amount-row"><div class="thumb" style="display:grid;place-items:center;font-size:26px">⚔</div><div><b>${esc(m.defender.label)}</b><div class="count">Trample / excess damage</div></div></div>`);
      r.appendChild(stepper(toDef, (d) => { toDef = Math.max(0, toDef + d); render(); }));
      rows.appendChild(r);
    }
    validate();
  };
  if (m.maySkip) dlg.foot.append(button('Decide later', '', () => reply(id, { skip: true })));
  dlg.foot.append(ok);
  render();
}

function showAssignAmount(m) {
  const { id, amount, targets } = m;
  const dlg = modal(`Distribute ${amount}${m.unit ? ' ' + m.unit : ''}${m.card ? ' — ' + m.card.n : ''}`, { wide: targets.length > 3 });
  open.set(id, dlg);
  const vals = targets.map(() => (m.atLeastOne ? 1 : 0));
  let left = amount - vals.reduce((a, b) => a + b, 0);
  for (let i = 0; left > 0 && targets.length; i = (i + 1) % targets.length) {
    if (vals[i] < targets[i].max) { vals[i]++; left--; } else if (targets.every((t, j) => vals[j] >= t.max)) break;
  }
  const note = el('<div class="count" style="margin-bottom:10px"></div>');
  dlg.body.appendChild(note);
  const rows = el('<div></div>');
  dlg.body.appendChild(rows);
  const ok = button('OK', 'primary', () => reply(id, vals));
  const render = () => {
    rows.replaceChildren();
    targets.forEach((t, i) => {
      const r = el(`<div class="amount-row"><div class="thumb"></div><div><b>${esc(t.label)}</b><div class="count">max ${t.max}</div></div></div>`);
      if (t.card) /** @type {HTMLElement} */ (r.querySelector('.thumb')).replaceWith(cardFaceEl(t.card, 'thumb'));
      r.appendChild(stepper(vals[i], (d) => { vals[i] = Math.max(m.atLeastOne ? 1 : 0, Math.min(t.max, vals[i] + d)); render(); }));
      rows.appendChild(r);
    });
    const sum = vals.reduce((a, b) => a + b, 0);
    ok.disabled = sum !== amount;
    note.textContent = sum === amount ? 'Ready.' : `Assigned ${sum} of ${amount}.`;
    note.style.color = sum === amount ? 'var(--green)' : 'var(--muted)';
  };
  dlg.foot.append(ok);
  render();
}

// ---------------------------------------------------------------- arrange (scry-like) & sideboard

function showArrange(m) {
  const { id, items } = m;
  let order = items.map((_, i) => i);
  const dlg = modal(m.title || 'Arrange cards (top first)', { wide: true });
  open.set(id, dlg);
  const list = el('<div class="list" style="display:grid;gap:6px"></div>');
  dlg.body.appendChild(list);
  const render = () => {
    list.replaceChildren(...order.map((i, pos) => {
      const it = items[i];
      const r = el(`<div class="orow"><span class="ix">${pos + 1}</span><div class="thumb"></div><span class="sp">${esc(it.label)}</span></div>`);
      /** @type {HTMLElement} */ (r.querySelector('.thumb')).replaceWith(cardFaceEl(it.card, 'thumb'));
      r.addEventListener('mouseenter', () => window.dispatchEvent(new CustomEvent('nova:detail', { detail: it.card })));
      if (it.movable) {
        const up = el('<button class="btn ghost small mv">▲</button>');
        const dn = el('<button class="btn ghost small mv">▼</button>');
        up.addEventListener('click', () => { if (pos > 0) { [order[pos - 1], order[pos]] = [order[pos], order[pos - 1]]; render(); } });
        dn.addEventListener('click', () => { if (pos < order.length - 1) { [order[pos + 1], order[pos]] = [order[pos], order[pos + 1]]; render(); } });
        r.append(up, dn);
      }
      return r;
    }));
  };
  dlg.foot.append(button('OK', 'primary', () => reply(id, order)));
  render();
}

function showSideboard(m) {
  const { id, items, sbCount } = m;
  let side = items.slice(0, sbCount).map((_, i) => i);
  let main = items.slice(sbCount).map((_, i) => i + sbCount);
  const dlg = modal(m.message || 'Sideboarding', { wide: true });
  open.set(id, dlg);
  const wrap = el(`<div class="dual"><div class="col"><h4>Sideboard</h4><div class="list" data-a></div></div><div class="col"><h4>Main deck</h4><div class="list" data-b></div></div></div>`);
  dlg.body.appendChild(wrap);
  const render = () => {
    const mk = (i, fromSide) => {
      const r = el(`<div class="orow"><span class="sp">${esc(items[i].label)}</span></div>`);
      r.addEventListener('click', () => {
        if (fromSide) { side = side.filter((x) => x !== i); main.push(i); } else { main = main.filter((x) => x !== i); side.push(i); }
        render();
      });
      return r;
    };
    /** @type {HTMLElement} */ (wrap.querySelector('[data-a]')).replaceChildren(...side.map((i) => mk(i, true)));
    /** @type {HTMLElement} */ (wrap.querySelector('[data-b]')).replaceChildren(...main.map((i) => mk(i, false)));
  };
  dlg.foot.append(button('Keep deck', '', () => reply(id, null)), button('OK', 'primary', () => reply(id, main)));
  render();
}

// ---------------------------------------------------------------- wiring

on('dialog', (m) => {
  if (open.has(m.id)) return; // re-sent after reconnect
  switch (m.kind) {
    case 'choose': showChoose(m); break;
    case 'order': showOrder(m); break;
    case 'option': showOption(m); break;
    case 'message': showMessage(m); break;
    case 'input': showInput(m); break;
    case 'assignDamage': showAssignDamage(m); break;
    case 'assignAmount': showAssignAmount(m); break;
    case 'arrange': showArrange(m); break;
    case 'sideboard': showSideboard(m); break;
    default:
      console.warn('unknown dialog', m);
      send({ t: 'reply', id: m.id, v: null });
  }
});
on('dialogClose', (m) => close(m.id));

export function anyDialogOpen() {
  return open.size > 0;
}

export function closeAllDialogs() {
  for (const id of [...open.keys()]) close(id);
}
