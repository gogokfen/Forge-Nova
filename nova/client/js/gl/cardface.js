// @ts-check
// Procedural card faces (Canvas2D), used while images load or when no image exists.

import { shortNum } from '../store.js';

const FRAME = {
  W: ['#f4efdc', '#d9d1b3'], U: ['#5fa2de', '#2c5f96'], B: ['#6c6266', '#2c2629'], R: ['#e58064', '#a8412a'],
  G: ['#6dbb82', '#2f6d41'], M: ['#e7cf86', '#b28a38'], C: ['#b9c0c6', '#7d858c'], L: ['#b39f7c', '#6d5c40'],
};
const MANA_BG = { W: '#f8f2d6', U: '#a9d4f2', B: '#c7bdb8', R: '#f19c7e', G: '#9ed0a5', C: '#d7d2cf' };

function frameFor(card) {
  const col = card.col || '';
  if (card.tf & 2 && !(card.tf & 1)) return FRAME.L;
  if (col.length > 1) return FRAME.M;
  if (col.length === 1) return FRAME[col];
  return FRAME.C;
}

/** Parses "{2}{W}{U/P}" into symbol strings. */
export function manaSymbols(cost) {
  const out = [];
  if (!cost) return out;
  const re = /\{([^}]+)\}/g;
  let m;
  while ((m = re.exec(cost))) out.push(m[1]);
  return out;
}

function drawSymbol(ctx, sym, x, y, d) {
  const parts = sym.split('/');
  ctx.save();
  ctx.beginPath();
  ctx.arc(x, y, d / 2, 0, Math.PI * 2);
  if (parts.length === 2 && MANA_BG[parts[0]] && MANA_BG[parts[1]]) {
    ctx.clip();
    ctx.fillStyle = MANA_BG[parts[0]];
    ctx.fillRect(x - d / 2, y - d / 2, d, d);
    ctx.beginPath();
    ctx.moveTo(x + d / 2, y - d / 2);
    ctx.lineTo(x + d / 2, y + d / 2);
    ctx.lineTo(x - d / 2, y + d / 2);
    ctx.closePath();
    ctx.fillStyle = MANA_BG[parts[1]];
    ctx.fill();
  } else {
    ctx.fillStyle = MANA_BG[parts[0]] || '#cac5c0';
    ctx.fill();
  }
  ctx.restore();
  ctx.fillStyle = '#161412';
  ctx.font = `800 ${Math.round(d * 0.62)}px "Segoe UI", Arial, sans-serif`;
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  const label = parts.length === 2 ? (MANA_BG[parts[0]] && MANA_BG[parts[1]] ? '' : parts[0]) : sym === 'T' ? '⟳' : sym;
  ctx.fillText(label.length > 2 ? label.slice(0, 2) : label, x, y + d * 0.04);
}

function wrap(ctx, text, maxW) {
  const lines = [];
  for (const para of text.split('\n')) {
    const words = para.split(/\s+/);
    let line = '';
    for (const w of words) {
      const test = line ? line + ' ' + w : w;
      if (ctx.measureText(test).width > maxW && line) {
        lines.push(line);
        line = w;
      } else line = test;
    }
    lines.push(line);
  }
  return lines;
}

/**
 * Draws a card face into a 2D context at (0,0,w,h).
 * @param {CanvasRenderingContext2D} ctx
 * @param {any} card  {n, mc, ty, tx, pow, tou, loy, def, col, tf}
 */
export function drawCardFace(ctx, card, w, h) {
  const [c1, c2] = frameFor(card);
  const s = w / 256;
  ctx.save();
  // outer black border
  ctx.fillStyle = '#0d0d0f';
  ctx.fillRect(0, 0, w, h);
  // frame gradient
  const g = ctx.createLinearGradient(0, 0, w, h);
  g.addColorStop(0, c1);
  g.addColorStop(1, c2);
  ctx.fillStyle = g;
  const bw = 9 * s;
  ctx.fillRect(bw, bw, w - 2 * bw, h - 2 * bw);

  // name bar
  const pad = 14 * s;
  const barH = 26 * s;
  ctx.fillStyle = 'rgba(245,240,228,0.92)';
  ctx.fillRect(pad, pad, w - 2 * pad, barH);
  const syms = manaSymbols(card.mc);
  const sd = 17 * s;
  let right = w - pad - 4 * s;
  for (let i = syms.length - 1; i >= 0; i--) {
    drawSymbol(ctx, syms[i], right - sd / 2, pad + barH / 2, sd);
    right -= sd + 1.5 * s;
  }
  ctx.fillStyle = '#141210';
  let fs = 15 * s;
  ctx.font = `700 ${fs}px "Segoe UI", Arial, sans-serif`;
  const nameMax = right - pad - 8 * s;
  const name = card.n || '';
  while (ctx.measureText(name).width > nameMax && fs > 8 * s) {
    fs -= 0.5 * s;
    ctx.font = `700 ${fs}px "Segoe UI", Arial, sans-serif`;
  }
  ctx.textAlign = 'left';
  ctx.textBaseline = 'middle';
  ctx.fillText(name, pad + 5 * s, pad + barH / 2 + 1 * s);

  // art box
  const artTop = pad + barH + 5 * s;
  const artH = h * 0.40;
  const ag = ctx.createLinearGradient(0, artTop, 0, artTop + artH);
  ag.addColorStop(0, 'rgba(0,0,0,0.20)');
  ag.addColorStop(1, 'rgba(0,0,0,0.45)');
  ctx.fillStyle = ag;
  ctx.fillRect(pad + 2 * s, artTop, w - 2 * pad - 4 * s, artH);
  // big initials as "art"
  ctx.fillStyle = 'rgba(255,255,255,0.20)';
  ctx.font = `800 ${60 * s}px "Segoe UI", Arial, sans-serif`;
  ctx.textAlign = 'center';
  const initials = name.split(/[\s,-]+/).filter(Boolean).slice(0, 2).map((x) => x[0]).join('').toUpperCase();
  ctx.fillText(initials, w / 2, artTop + artH / 2);

  // type line
  const tyTop = artTop + artH + 5 * s;
  ctx.fillStyle = 'rgba(245,240,228,0.92)';
  ctx.fillRect(pad, tyTop, w - 2 * pad, 22 * s);
  ctx.fillStyle = '#141210';
  let tfs = 12 * s;
  ctx.font = `600 ${tfs}px "Segoe UI", Arial, sans-serif`;
  const ty = card.ty || '';
  while (ctx.measureText(ty).width > w - 2 * pad - 10 * s && tfs > 7 * s) {
    tfs -= 0.5 * s;
    ctx.font = `600 ${tfs}px "Segoe UI", Arial, sans-serif`;
  }
  ctx.textAlign = 'left';
  ctx.fillText(ty, pad + 5 * s, tyTop + 11.5 * s);

  // text box
  const txTop = tyTop + 27 * s;
  const txBottom = h - pad - 4 * s;
  ctx.fillStyle = 'rgba(248,245,236,0.93)';
  ctx.fillRect(pad + 2 * s, txTop, w - 2 * pad - 4 * s, txBottom - txTop);
  const text = (card.tx || '').replace(/\\n/g, '\n');
  if (text) {
    let size = 12 * s;
    let lines;
    const maxW = w - 2 * pad - 16 * s;
    for (;;) {
      ctx.font = `${size}px "Segoe UI", Arial, sans-serif`;
      lines = wrap(ctx, text, maxW);
      if (lines.length * size * 1.18 <= txBottom - txTop - 10 * s || size <= 6.5 * s) break;
      size -= 0.5 * s;
    }
    ctx.fillStyle = '#1b1916';
    ctx.textBaseline = 'top';
    let y = txTop + 5 * s;
    for (const ln of lines) {
      ctx.fillText(ln, pad + 8 * s, y);
      y += size * 1.18;
      if (y > txBottom - size) break;
    }
  }

  // P/T or loyalty
  const stat = card.pow !== undefined ? `${shortNum(card.pow)}/${shortNum(card.tou)}` : card.loy ? card.loy : card.def ? card.def : '';
  if (stat) {
    const bwid = 50 * s, bh = 24 * s;
    const bx = w - pad - bwid + 4 * s, by = h - pad - bh + 2 * s;
    ctx.fillStyle = 'rgba(245,240,228,0.97)';
    ctx.strokeStyle = c2;
    ctx.lineWidth = 2 * s;
    ctx.beginPath();
    ctx.roundRect(bx, by, bwid, bh, 6 * s);
    ctx.fill();
    ctx.stroke();
    ctx.fillStyle = '#121110';
    ctx.font = `800 ${15 * s}px "Segoe UI", Arial, sans-serif`;
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText(stat, bx + bwid / 2, by + bh / 2 + 1 * s);
  }
  ctx.restore();
}

/** Generic card back (used when no sleeve image is available). */
export function drawCardBack(ctx, w, h) {
  ctx.fillStyle = '#0d0d0f';
  ctx.fillRect(0, 0, w, h);
  const g = ctx.createRadialGradient(w / 2, h / 2, 10, w / 2, h / 2, h * 0.7);
  g.addColorStop(0, '#6b4a2a');
  g.addColorStop(1, '#2a1a10');
  ctx.fillStyle = g;
  ctx.fillRect(w * 0.035, w * 0.035, w * 0.93, h - w * 0.07);
  ctx.strokeStyle = 'rgba(224,166,75,0.7)';
  ctx.lineWidth = w * 0.02;
  ctx.beginPath();
  ctx.ellipse(w / 2, h / 2, w * 0.3, h * 0.36, 0, 0, Math.PI * 2);
  ctx.stroke();
  ctx.fillStyle = 'rgba(224,166,75,0.85)';
  ctx.font = `800 ${w * 0.16}px "Segoe UI", Arial, sans-serif`;
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillText('F', w / 2, h / 2);
}

/** Returns a <canvas> element with a rendered face, for the DOM (dialogs / detail panel). */
export function faceCanvas(card, w = 244, h = 340) {
  const cv = document.createElement('canvas');
  const dpr = Math.min(window.devicePixelRatio || 1, 2);
  cv.width = Math.round(w * dpr);
  cv.height = Math.round(h * dpr);
  const ctx = /** @type {CanvasRenderingContext2D} */ (cv.getContext('2d'));
  if (card && !card.hid) drawCardFace(ctx, card, cv.width, cv.height);
  else drawCardBack(ctx, cv.width, cv.height);
  return cv;
}
