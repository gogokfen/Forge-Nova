// @ts-check
// Arrows for combat and targeting, drawn on a 2D overlay canvas.

import { store } from '../store.js';

export class Arrows {
  /**
   * @param {HTMLCanvasElement} canvas
   * @param {import('./scene.js').Scene} scene
   * @param {(pid:number) => {x:number,y:number}|null} panelCenter
   */
  constructor(canvas, scene, panelCenter) {
    this.canvas = canvas;
    this.ctx = /** @type {CanvasRenderingContext2D} */ (canvas.getContext('2d'));
    this.scene = scene;
    this.panelCenter = panelCenter;
    this.dpr = 1;
    this.hadArrows = false;
  }

  resize(w, h, dpr) {
    this.dpr = dpr;
    this.canvas.width = Math.round(w * dpr);
    this.canvas.height = Math.round(h * dpr);
  }

  pos(kind, id) {
    if (kind === 'p') return this.panelCenter(id);
    const s = this.scene.spriteOfCard(id);
    if (!s || s.alpha < 0.3) return null;
    return { x: s.x, y: s.y };
  }

  arrow(a, b, color, width = 4, dashed = false) {
    const ctx = this.ctx;
    const dx = b.x - a.x, dy = b.y - a.y;
    const len = Math.hypot(dx, dy);
    if (len < 10) return;
    // bend perpendicular to the line for a pleasant arc
    const nx = -dy / len, ny = dx / len;
    const bend = Math.min(80, len * 0.18);
    const cx = (a.x + b.x) / 2 + nx * bend, cy = (a.y + b.y) / 2 + ny * bend;
    const end = { x: b.x - (b.x - cx) / Math.hypot(b.x - cx, b.y - cy) * 14, y: b.y - (b.y - cy) / Math.hypot(b.x - cx, b.y - cy) * 14 };
    ctx.save();
    ctx.lineCap = 'round';
    ctx.strokeStyle = color;
    ctx.fillStyle = color;
    ctx.shadowColor = color;
    ctx.shadowBlur = 12;
    ctx.lineWidth = width;
    if (dashed) ctx.setLineDash([10, 8]);
    ctx.beginPath();
    ctx.moveTo(a.x, a.y);
    ctx.quadraticCurveTo(cx, cy, end.x, end.y);
    ctx.stroke();
    ctx.setLineDash([]);
    // arrow head
    const ang = Math.atan2(b.y - cy, b.x - cx);
    const hs = 10 + width * 2;
    ctx.beginPath();
    ctx.moveTo(b.x, b.y);
    ctx.lineTo(b.x - hs * Math.cos(ang - 0.45), b.y - hs * Math.sin(ang - 0.45));
    ctx.lineTo(b.x - hs * Math.cos(ang + 0.45), b.y - hs * Math.sin(ang + 0.45));
    ctx.closePath();
    ctx.fill();
    ctx.restore();
  }

  /** @param {{x:number,y:number}|null} stackAnchor position of the top stack item */
  draw(stackAnchor) {
    const ctx = this.ctx;
    const needed = store.combat.length > 0 || (store.stack[0] && (store.stack[0].tc || store.stack[0].tp));
    if (!needed && !this.hadArrows) return;
    ctx.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
    ctx.clearRect(0, 0, this.canvas.width, this.canvas.height);
    this.hadArrows = !!needed;
    if (!needed) return;
    for (const c of store.combat) {
      const a = this.pos('c', c.a);
      if (!a) continue;
      const d = c.dp != null ? this.pos('p', c.dp) : c.dc != null ? this.pos('c', c.dc) : null;
      if (d && !(c.b && c.b.length)) this.arrow(a, d, 'rgba(240,80,64,0.85)', 4);
      if (c.b) for (const b of c.b) {
        const bp = this.pos('c', b);
        if (bp) this.arrow(bp, a, 'rgba(110,160,255,0.9)', 4);
      }
    }
    const top = store.stack[0];
    if (top && stackAnchor) {
      for (const id of top.tc || []) {
        const t = this.pos('c', id);
        if (t) this.arrow(stackAnchor, t, 'rgba(255,150,60,0.9)', 3, true);
      }
      for (const id of top.tp || []) {
        const t = this.pos('p', id);
        if (t) this.arrow(stackAnchor, t, 'rgba(255,150,60,0.9)', 3, true);
      }
    }
  }
}
