// @ts-check
// All card art lives in ONE texture array (one layer per distinct image), so the whole board
// can be drawn with a single draw call no matter how many different cards are visible.
//
// Mipmaps are built per layer from pre-scaled ImageBitmaps (resized off the main thread), so
// streaming in new art never regenerates the whole array.

import { drawCardFace, drawCardBack } from './cardface.js';
import { thumbUrl } from '../net.js';

export const TEX_W = 256;
export const TEX_H = 357;
const LAYER_BACK = 0;
const LAYER_BLANK = 1;
const MAX_LOADS = 6;
const PROC_PER_FRAME = 6;
const UPLOADS_PER_FRAME = 8;
const PROC_DELAY_MS = 500; // draw a procedural face only if the real image is this late (or missing)

function mipSizes() {
  const sizes = [];
  let w = TEX_W, h = TEX_H;
  for (;;) {
    sizes.push([w, h]);
    if (w === 1 && h === 1) break;
    w = Math.max(1, w >> 1);
    h = Math.max(1, h >> 1);
  }
  return sizes;
}
const MIPS = mipSizes();

export class CardTextures {
  /**
   * @param {WebGL2RenderingContext} gl
   * @param {() => void} onUpdate called when new art arrived (request a redraw)
   */
  constructor(gl, onUpdate) {
    this.gl = gl;
    this.onUpdate = onUpdate;
    const maxLayers = gl.getParameter(gl.MAX_ARRAY_TEXTURE_LAYERS);
    /** @type {WebGLTexture|null} */
    this.tex = null;
    this.capacity = 0;
    for (const want of [224, 128, 64]) {
      const cap = Math.min(want, maxLayers);
      const tex = gl.createTexture();
      gl.bindTexture(gl.TEXTURE_2D_ARRAY, tex);
      gl.texStorage3D(gl.TEXTURE_2D_ARRAY, MIPS.length, gl.RGBA8, TEX_W, TEX_H, cap);
      if (gl.getError() === gl.NO_ERROR) {
        this.tex = tex;
        this.capacity = cap;
        break;
      }
      gl.deleteTexture(tex);
    }
    if (!this.tex) throw new Error('Could not allocate card texture array');
    gl.texParameteri(gl.TEXTURE_2D_ARRAY, gl.TEXTURE_MIN_FILTER, gl.LINEAR_MIPMAP_LINEAR);
    gl.texParameteri(gl.TEXTURE_2D_ARRAY, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
    gl.texParameteri(gl.TEXTURE_2D_ARRAY, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
    gl.texParameteri(gl.TEXTURE_2D_ARRAY, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
    const aniso = gl.getExtension('EXT_texture_filter_anisotropic');
    if (aniso) gl.texParameterf(gl.TEXTURE_2D_ARRAY, aniso.TEXTURE_MAX_ANISOTROPY_EXT, 4);

    /** @type {Map<string, any>} */
    this.entries = new Map();
    this.free = [];
    for (let i = this.capacity - 1; i >= 2; i--) this.free.push(i);
    this.frame = 0;
    this.procBudget = PROC_PER_FRAME;
    this.uploadBudget = UPLOADS_PER_FRAME;
    /** @type {{key:string, e:any}[]} */
    this.queue = [];
    /** @type {{e:any, levels:ImageBitmap[]}[]} */
    this.ready = []; // decoded images waiting for upload
    this.loading = 0;
    this.stats = { loaded: 0, failed: 0, evicted: 0, proc: 0 };

    // scratch canvases, one per mip level, for procedural faces
    this.scratch = MIPS.map(([w, h]) => {
      const c = document.createElement('canvas');
      c.width = w;
      c.height = h;
      return { c, ctx: /** @type {CanvasRenderingContext2D} */ (c.getContext('2d')) };
    });
    this._drawInto(LAYER_BACK, (ctx) => drawCardBack(ctx, TEX_W, TEX_H));
    this._drawInto(LAYER_BLANK, (ctx) => {
      ctx.fillStyle = '#16191f';
      ctx.fillRect(0, 0, TEX_W, TEX_H);
      ctx.fillStyle = '#20252e';
      ctx.fillRect(9, 9, TEX_W - 18, TEX_H - 18);
    });
  }

  get backLayer() { return LAYER_BACK; }

  beginFrame() {
    this.frame++;
    this.procBudget = PROC_PER_FRAME;
    this.uploadBudget = UPLOADS_PER_FRAME;
  }

  /** Draws into level 0 with `paint`, derives the mip chain on the CPU and uploads everything. */
  _drawInto(layer, paint) {
    const s0 = this.scratch[0];
    s0.ctx.clearRect(0, 0, TEX_W, TEX_H);
    paint(s0.ctx);
    const gl = this.gl;
    gl.bindTexture(gl.TEXTURE_2D_ARRAY, this.tex);
    gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, false);
    gl.texSubImage3D(gl.TEXTURE_2D_ARRAY, 0, 0, 0, layer, TEX_W, TEX_H, 1, gl.RGBA, gl.UNSIGNED_BYTE, s0.c);
    for (let i = 1; i < MIPS.length; i++) {
      const [w, h] = MIPS[i];
      const s = this.scratch[i];
      s.ctx.clearRect(0, 0, w, h);
      s.ctx.imageSmoothingQuality = 'high';
      s.ctx.drawImage(this.scratch[i - 1].c, 0, 0, w, h);
      gl.texSubImage3D(gl.TEXTURE_2D_ARRAY, i, 0, 0, layer, w, h, 1, gl.RGBA, gl.UNSIGNED_BYTE, s.c);
    }
  }

  _uploadLevels(layer, levels) {
    const gl = this.gl;
    gl.bindTexture(gl.TEXTURE_2D_ARRAY, this.tex);
    gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, false);
    for (let i = 0; i < levels.length; i++) {
      const [w, h] = MIPS[i];
      gl.texSubImage3D(gl.TEXTURE_2D_ARRAY, i, 0, 0, layer, w, h, 1, gl.RGBA, gl.UNSIGNED_BYTE, levels[i]);
    }
  }

  /** Uploads images decoded since the last frame (budgeted); called before drawing. */
  flush() {
    while (this.ready.length && this.uploadBudget > 0) {
      const { e, levels } = /** @type {{e:any, levels:ImageBitmap[]}} */ (this.ready.shift());
      if (this.entries.get(e.key) === e) {
        this._uploadLevels(e.layer, levels);
        e.state = 'ready';
        this.stats.loaded++;
      }
      levels.forEach((b) => b.close());
      this.uploadBudget--;
    }
    if (this.ready.length) this.onUpdate(); // more to upload next frame
  }

  _alloc() {
    const f = this.free.pop();
    if (f !== undefined) return f;
    // evict the least recently drawn image that isn't on screen now
    let victim = null;
    for (const e of this.entries.values()) {
      if (e.lastUse >= this.frame - 1 || e.state === 'loading') continue;
      if (!victim || e.lastUse < victim.lastUse) victim = e;
    }
    if (!victim) return -1;
    this.entries.delete(victim.key);
    this.stats.evicted++;
    return victim.layer;
  }

  _drawProc(e) {
    const face = e.face;
    this._drawInto(e.layer, (ctx) => {
      if (face && !face.hid) drawCardFace(ctx, face, TEX_W, TEX_H);
      else drawCardBack(ctx, TEX_W, TEX_H);
    });
    e.hasProc = true;
    this.procBudget--;
    this.stats.proc++;
  }

  /**
   * Texture layer to draw for `key` this frame.
   * @param {string} key image key ("c:..."/"t:..."), "sleeve:N", or "proc:ID" for art-less faces
   * @param {any} face card data for the procedural fallback
   */
  layerFor(key, face) {
    let e = this.entries.get(key);
    if (!e) {
      const layer = this._alloc();
      if (layer < 0) return LAYER_BLANK;
      const url = key.startsWith('proc:') ? null : key.startsWith('sleeve:') ? `/sleeve/${key.slice(7)}.png` : thumbUrl(key);
      e = { key, layer, state: url ? 'queued' : 'proc', lastUse: this.frame, face, url, born: performance.now(), hasProc: false };
      this.entries.set(key, e);
      if (url) this._enqueue(e);
    }
    e.lastUse = this.frame;
    if (e.state === 'ready') return e.layer;
    if (e.hasProc) return e.layer;
    // Not ready yet: procedural face if the art is missing or late, otherwise a blank placeholder.
    const late = e.state === 'failed' || e.state === 'proc' || performance.now() - e.born > PROC_DELAY_MS;
    if (late && !key.startsWith('sleeve:') && this.procBudget > 0) {
      e.face = face || e.face;
      this._drawProc(e);
      return e.layer;
    }
    if (key.startsWith('sleeve:')) return LAYER_BACK;
    if (!late) this.onUpdate(); // re-check once the delay has passed
    return LAYER_BLANK;
  }

  _enqueue(e) {
    this.queue.push({ key: e.key, e });
    this._pump();
  }

  _pump() {
    while (this.loading < MAX_LOADS && this.queue.length) {
      const { key, e } = /** @type {{key:string,e:any}} */ (this.queue.shift());
      if (this.entries.get(key) !== e) continue; // evicted meanwhile
      this._load(e);
    }
  }

  async _load(e) {
    this.loading++;
    e.state = 'loading';
    try {
      const res = await fetch(e.url);
      if (!res.ok) throw new Error(String(res.status));
      const blob = await res.blob();
      // decode + resize off the main thread, then derive the mip chain from the previous level
      const levels = [await createImageBitmap(blob, { resizeWidth: TEX_W, resizeHeight: TEX_H, resizeQuality: 'high', premultiplyAlpha: 'none' })];
      for (let i = 1; i < MIPS.length; i++) {
        const [w, h] = MIPS[i];
        levels.push(await createImageBitmap(levels[i - 1], { resizeWidth: w, resizeHeight: h, resizeQuality: 'medium', premultiplyAlpha: 'none' }));
      }
      if (this.entries.get(e.key) === e) {
        e.state = 'decoded';
        this.ready.push({ e, levels });
        this.onUpdate();
      } else {
        levels.forEach((b) => b.close());
      }
    } catch {
      e.state = 'failed';
      this.stats.failed++;
      this.onUpdate();
    } finally {
      this.loading--;
      this._pump();
    }
  }
}
