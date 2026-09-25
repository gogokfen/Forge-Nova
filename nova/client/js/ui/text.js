// @ts-check
// DOM helpers and mana-symbol rendering for rules text.

export function esc(s) {
  return String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c] || c);
}

const HYBRID = { W: '#f8f2d6', U: '#a9d4f2', B: '#c7bdb8', R: '#f19c7e', G: '#9ed0a5', C: '#d7d2cf' };

/** One mana symbol as HTML ("W", "2", "U/P", "W/U", "T", "X"...). */
export function symbolHtml(sym) {
  const parts = sym.split('/');
  if (parts.length === 2 && HYBRID[parts[0]] && HYBRID[parts[1]]) {
    return `<span class="ms H" style="--h1:${HYBRID[parts[0]]};--h2:${HYBRID[parts[1]]}"></span>`;
  }
  if (parts.length === 2 && parts[1] === 'P') {
    return `<span class="ms ${esc(parts[0])}">Φ</span>`;
  }
  if (sym === 'T') return '<span class="ms T">↷</span>';
  if (sym === 'Q') return '<span class="ms T">↶</span>';
  if (sym === 'E') return '<span class="ms C">E</span>';
  const cls = /^[WUBRGC]$/.test(sym) ? sym : /^\d+$/.test(sym) ? 'N' : 'X';
  const label = /^[WUBRG]$/.test(sym) ? '' : sym;
  return `<span class="ms ${cls}">${esc(label)}</span>`;
}

/** Escapes text and replaces {..} tokens with mana symbols. */
export function manaHtml(text) {
  return esc(text).replace(/\{([^}]{1,6})\}/g, (_, s) => symbolHtml(s));
}

export function pipsHtml(colors) {
  if (!colors) return '<span class="pip C"></span>';
  return [...colors].map((c) => `<span class="pip ${c}"></span>`).join('');
}

/** Creates an element from an HTML string. */
export function el(html) {
  const t = document.createElement('template');
  t.innerHTML = html.trim();
  return /** @type {HTMLElement} */ (t.content.firstElementChild);
}

export function toast(msg, kind = '') {
  const box = document.getElementById('toasts');
  if (!box) return;
  const t = el(`<div class="toast ${kind}">${esc(msg)}</div>`);
  box.appendChild(t);
  setTimeout(() => t.remove(), kind === 'error' ? 7000 : 3500);
}

export function showTooltip(text, x, y) {
  const tip = /** @type {HTMLElement} */ (document.getElementById('tooltip'));
  if (!text) { tip.classList.add('hidden'); return; }
  tip.textContent = text;
  tip.classList.remove('hidden');
  const r = tip.getBoundingClientRect();
  tip.style.left = Math.min(window.innerWidth - r.width - 8, x + 14) + 'px';
  tip.style.top = Math.min(window.innerHeight - r.height - 8, y + 14) + 'px';
}
