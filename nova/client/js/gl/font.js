// @ts-check
// Signed-distance-field font atlas, generated at startup from a system font.
// Distance transform: Felzenszwalb & Huttenlocher (same approach as Mapbox TinySDF).

import { KIND } from './renderer.js';

const INF = 1e20;
const FONT_SIZE = 44;
const BUFFER = 8;
const RADIUS = 10;
const CUTOFF = 0.25; // glyph edge sits at 1 - CUTOFF = 0.75 in the texture
const CHARS = (() => {
  let s = '';
  for (let c = 32; c < 127; c++) s += String.fromCharCode(c);
  return s + '×−∞•★→';
})();

function edt1d(grid, offset, stride, length, f, v, z) {
  v[0] = 0;
  z[0] = -INF;
  z[1] = INF;
  for (let q = 0; q < length; q++) f[q] = grid[offset + q * stride];
  for (let q = 1, k = 0, s = 0; q < length; q++) {
    do {
      const r = v[k];
      s = (f[q] - f[r] + q * q - r * r) / (q - r) / 2;
    } while (s <= z[k] && --k > -1);
    k++;
    v[k] = q;
    z[k] = s;
    z[k + 1] = INF;
  }
  for (let q = 0, k = 0; q < length; q++) {
    while (z[k + 1] < q) k++;
    const r = v[k];
    const qr = q - r;
    grid[offset + q * stride] = f[r] + qr * qr;
  }
}

function edt(data, x0, y0, width, height, gridSize, f, v, z) {
  for (let x = x0; x < x0 + width; x++) edt1d(data, y0 * gridSize + x, gridSize, height, f, v, z);
  for (let y = y0; y < y0 + height; y++) edt1d(data, y * gridSize + x0, 1, width, f, v, z);
}

export class SDFFont {
  /** @param {WebGL2RenderingContext} gl */
  constructor(gl, family = '"Segoe UI", system-ui, Arial, sans-serif', weight = 700) {
    const size = FONT_SIZE + BUFFER * 4;
    const canvas = document.createElement('canvas');
    canvas.width = canvas.height = size;
    const ctx = /** @type {CanvasRenderingContext2D} */ (canvas.getContext('2d', { willReadFrequently: true }));
    ctx.font = `${weight} ${FONT_SIZE}px ${family}`;
    ctx.textBaseline = 'alphabetic';
    ctx.textAlign = 'left';
    ctx.fillStyle = 'black';

    const gridOuter = new Float64Array(size * size);
    const gridInner = new Float64Array(size * size);
    const f = new Float64Array(size), z = new Float64Array(size + 1), v = new Uint16Array(size);

    /** @type {Map<string, any>} */
    this.glyphs = new Map();
    const bitmaps = [];
    // measure line metrics once
    const mRef = ctx.measureText('Hg');
    this.ascent = mRef.actualBoundingBoxAscent;
    this.descent = mRef.actualBoundingBoxDescent;
    const capM = ctx.measureText('H');
    this.capHeight = capM.actualBoundingBoxAscent;

    for (const ch of CHARS) {
      const m = ctx.measureText(ch);
      const top = Math.ceil(m.actualBoundingBoxAscent);
      const left = Math.ceil(m.actualBoundingBoxLeft);
      const gw = Math.max(0, Math.min(size - BUFFER * 2, Math.ceil(m.actualBoundingBoxRight + m.actualBoundingBoxLeft)));
      const gh = Math.max(0, Math.min(size - BUFFER * 2, top + Math.ceil(m.actualBoundingBoxDescent)));
      const w = gw + 2 * BUFFER, h = gh + 2 * BUFFER;
      const len = w * h;
      const data = new Uint8Array(len);
      if (gw > 0 && gh > 0) {
        ctx.clearRect(0, 0, size, size);
        ctx.fillText(ch, BUFFER + left, BUFFER + top);
        const img = ctx.getImageData(BUFFER, BUFFER, gw, gh).data;
        gridOuter.fill(INF, 0, len);
        gridInner.fill(0, 0, len);
        for (let y = 0; y < gh; y++) {
          for (let x = 0; x < gw; x++) {
            const a = img[4 * (y * gw + x) + 3] / 255;
            if (a === 0) continue;
            const j = (y + BUFFER) * w + x + BUFFER;
            if (a === 1) {
              gridOuter[j] = 0;
              gridInner[j] = INF;
            } else {
              const d = 0.5 - a;
              gridOuter[j] = d > 0 ? d * d : 0;
              gridInner[j] = d < 0 ? d * d : 0;
            }
          }
        }
        edt(gridOuter, 0, 0, w, h, w, f, v, z);
        edt(gridInner, BUFFER, BUFFER, gw, gh, w, f, v, z);
        for (let i = 0; i < len; i++) {
          const d = Math.sqrt(gridOuter[i]) - Math.sqrt(gridInner[i]);
          data[i] = Math.max(0, Math.min(255, Math.round(255 - 255 * (d / RADIUS + CUTOFF))));
        }
      }
      bitmaps.push({ ch, w, h, data, top, left, adv: m.width });
    }

    // shelf-pack into one atlas
    const atlasW = 1024;
    let x = 0, y = 0, shelf = 0;
    for (const b of bitmaps) {
      if (x + b.w > atlasW) { x = 0; y += shelf + 1; shelf = 0; }
      b.x = x; b.y = y;
      x += b.w + 1;
      shelf = Math.max(shelf, b.h);
    }
    const atlasH = y + shelf + 1;
    const atlas = new Uint8Array(atlasW * atlasH);
    for (const b of bitmaps) {
      for (let r = 0; r < b.h; r++) atlas.set(b.data.subarray(r * b.w, (r + 1) * b.w), (b.y + r) * atlasW + b.x);
      this.glyphs.set(b.ch, {
        w: b.w, h: b.h, top: b.top, left: b.left, adv: b.adv,
        u0: b.x / atlasW, v0: b.y / atlasH, u1: (b.x + b.w) / atlasW, v1: (b.y + b.h) / atlasH,
      });
    }
    const tex = gl.createTexture();
    gl.bindTexture(gl.TEXTURE_2D, tex);
    gl.pixelStorei(gl.UNPACK_ALIGNMENT, 1);
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.R8, atlasW, atlasH, 0, gl.RED, gl.UNSIGNED_BYTE, atlas);
    gl.pixelStorei(gl.UNPACK_ALIGNMENT, 4);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    this.tex = tex;
    this.fallback = this.glyphs.get('?');
  }

  /** Width in css px of `text` at font size `size`. */
  measure(text, size) {
    const s = size / FONT_SIZE;
    let w = 0;
    for (const ch of text) w += (this.glyphs.get(ch) || this.fallback).adv;
    return w * s;
  }

  /**
   * Emits glyph quads. (x, y) is the text anchor in a local frame whose origin (ox, oy) and
   * rotation `rot` are given, so text can follow rotated (tapped) cards.
   * The anchor is the horizontal alignment point on the vertical middle of capital letters.
   * @param {import('./renderer.js').Renderer} r
   * @param {number[]} color rgba
   * @param {number[]|null} outline [threshold(0..0.75), r, g, b] - threshold ~0.45 gives a thick outline
   */
  draw(r, text, x, y, size, color, align = 'center', ox = 0, oy = 0, rot = 0, outline = null) {
    const s = size / FONT_SIZE;
    const width = this.measure(text, size);
    let pen = align === 'center' ? x - width / 2 : align === 'right' ? x - width : x;
    const baseline = y + (this.capHeight * s) / 2;
    const c = Math.cos(rot), sn = Math.sin(rot);
    const o0 = outline ? outline[0] : 0, o1 = outline ? outline[1] : 0, o2 = outline ? outline[2] : 0, o3 = outline ? outline[3] : 0;
    for (const ch of text) {
      const g = this.glyphs.get(ch) || this.fallback;
      if (g.w > BUFFER * 2) {
        const gx = pen + (-g.left - BUFFER) * s;
        const gy = baseline - (g.top + BUFFER) * s;
        const w = g.w * s, h = g.h * s;
        const lx = gx + w / 2, ly = gy + h / 2;
        r.quad(KIND.GLYPH, ox + lx * c - ly * sn, oy + lx * sn + ly * c, w, h, rot, 0, 0,
          g.u0, g.v0, g.u1, g.v1, color[0], color[1], color[2], color[3], o0, o1, o2, o3);
      }
      pen += g.adv * s;
    }
    return width;
  }
}
