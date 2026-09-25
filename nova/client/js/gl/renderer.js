// @ts-check
// Low-level WebGL2 batch renderer: one program, one instanced draw call per frame.

import { VERT, FRAG } from './shaders.js';

export const KIND = { CARD: 0, RECT: 1, GLYPH: 2, GLOW: 3, RING: 4, CIRCLE: 5 };
const FLOATS = 20; // per instance (see layout below)

function compile(gl, type, src) {
  const s = gl.createShader(type);
  gl.shaderSource(s, src);
  gl.compileShader(s);
  if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) {
    throw new Error('Shader error: ' + gl.getShaderInfoLog(s));
  }
  return s;
}

export class Renderer {
  /** @param {HTMLCanvasElement} canvas */
  constructor(canvas) {
    this.canvas = canvas;
    const gl = canvas.getContext('webgl2', {
      antialias: false, alpha: true, premultipliedAlpha: true, depth: false, stencil: false,
      powerPreference: 'high-performance', desynchronized: false, preserveDrawingBuffer: false,
    });
    if (!gl) throw new Error('WebGL2 is not available in this browser.');
    /** @type {WebGL2RenderingContext} */
    this.gl = gl;
    this.dpr = 1;
    this.width = 1; // css px
    this.height = 1;

    const prog = gl.createProgram();
    gl.attachShader(prog, compile(gl, gl.VERTEX_SHADER, VERT));
    gl.attachShader(prog, compile(gl, gl.FRAGMENT_SHADER, FRAG));
    gl.linkProgram(prog);
    if (!gl.getProgramParameter(prog, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(prog) || 'link failed');
    this.prog = prog;
    this.uRes = gl.getUniformLocation(prog, 'uRes');
    this.uPx = gl.getUniformLocation(prog, 'uPx');
    gl.useProgram(prog);
    gl.uniform1i(gl.getUniformLocation(prog, 'uCards'), 0);
    gl.uniform1i(gl.getUniformLocation(prog, 'uFont'), 1);

    this.vao = gl.createVertexArray();
    gl.bindVertexArray(this.vao);
    const corners = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, corners);
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-0.5, -0.5, 0.5, -0.5, -0.5, 0.5, 0.5, 0.5]), gl.STATIC_DRAW);
    gl.enableVertexAttribArray(0);
    gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);

    this.capacity = 4096;
    this.data = new Float32Array(this.capacity * FLOATS);
    this.instanceBuf = gl.createBuffer();
    gl.bindBuffer(gl.ARRAY_BUFFER, this.instanceBuf);
    gl.bufferData(gl.ARRAY_BUFFER, this.data.byteLength, gl.DYNAMIC_DRAW);
    const stride = FLOATS * 4;
    // aPosSize(4) aRotKind(4) aUV(4) aColor(4) aParam(4)
    for (let i = 0; i < 5; i++) {
      gl.enableVertexAttribArray(1 + i);
      gl.vertexAttribPointer(1 + i, 4, gl.FLOAT, false, stride, i * 16);
      gl.vertexAttribDivisor(1 + i, 1);
    }
    gl.bindVertexArray(null);

    gl.enable(gl.BLEND);
    gl.blendFunc(gl.ONE, gl.ONE_MINUS_SRC_ALPHA);
    gl.disable(gl.DEPTH_TEST);
    this.count = 0;
    this.lost = false;
    canvas.addEventListener('webglcontextlost', (e) => { e.preventDefault(); this.lost = true; });
  }

  resize() {
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    const w = this.canvas.clientWidth, h = this.canvas.clientHeight;
    if (w !== this.width || h !== this.height || dpr !== this.dpr) {
      this.width = Math.max(1, w);
      this.height = Math.max(1, h);
      this.dpr = dpr;
      this.canvas.width = Math.round(this.width * dpr);
      this.canvas.height = Math.round(this.height * dpr);
      return true;
    }
    return false;
  }

  begin() {
    this.count = 0;
  }

  _grow() {
    const next = new Float32Array(this.data.length * 2);
    next.set(this.data);
    this.data = next;
    this.capacity *= 2;
    const gl = this.gl;
    gl.bindBuffer(gl.ARRAY_BUFFER, this.instanceBuf);
    gl.bufferData(gl.ARRAY_BUFFER, this.data.byteLength, gl.DYNAMIC_DRAW);
  }

  /**
   * Appends one quad.
   * @param {number} kind @param {number} x center x @param {number} y center y
   * @param {number} w @param {number} h @param {number} rot radians
   * @param {number} layer @param {number} radius
   * @param {number} u0 @param {number} v0 @param {number} u1 @param {number} v1
   * @param {number} r @param {number} g @param {number} b @param {number} a
   * @param {number} p0 @param {number} p1 @param {number} p2 @param {number} p3
   */
  quad(kind, x, y, w, h, rot, layer, radius, u0, v0, u1, v1, r, g, b, a, p0, p1, p2, p3) {
    if (this.count >= this.capacity) this._grow();
    const d = this.data, o = this.count * FLOATS;
    d[o] = x; d[o + 1] = y; d[o + 2] = w; d[o + 3] = h;
    d[o + 4] = rot; d[o + 5] = kind; d[o + 6] = layer; d[o + 7] = radius;
    d[o + 8] = u0; d[o + 9] = v0; d[o + 10] = u1; d[o + 11] = v1;
    d[o + 12] = r; d[o + 13] = g; d[o + 14] = b; d[o + 15] = a;
    d[o + 16] = p0; d[o + 17] = p1; d[o + 18] = p2; d[o + 19] = p3;
    this.count++;
  }

  /** Solid rounded rectangle. color = [r,g,b,a] 0..1 */
  rect(x, y, w, h, rot, radius, c) {
    this.quad(KIND.RECT, x, y, w, h, rot, 0, radius, 0, 0, 1, 1, c[0], c[1], c[2], c[3], 0, 0, 0, 0);
  }

  ring(x, y, w, h, rot, radius, width, c) {
    this.quad(KIND.RING, x, y, w, h, rot, 0, radius, 0, 0, 1, 1, c[0], c[1], c[2], c[3], width, 0, 0, 0);
  }

  circle(x, y, d, c) {
    this.quad(KIND.CIRCLE, x, y, d, d, 0, 0, 0, 0, 0, 1, 1, c[0], c[1], c[2], c[3], 0, 0, 0, 0);
  }

  /** Soft glow around a rounded rect (w,h = rect size); hollow keeps the inside clear. */
  glow(x, y, w, h, rot, radius, blur, c, hollow = 1) {
    this.quad(KIND.GLOW, x, y, w + blur * 2, h + blur * 2, rot, 0, radius + blur * 0.5, 0, 0, 1, 1,
      c[0], c[1], c[2], c[3], blur, hollow, 0, 0);
  }

  /**
   * @param {{textures?: {tex: WebGLTexture|null, flush: () => void}, fontTex?: WebGLTexture|null}} res
   */
  draw(res) {
    const gl = this.gl;
    if (this.lost) return;
    gl.viewport(0, 0, this.canvas.width, this.canvas.height);
    gl.clearColor(0, 0, 0, 0);
    gl.clear(gl.COLOR_BUFFER_BIT);
    if (!this.count) return;
    gl.useProgram(this.prog);
    gl.uniform2f(this.uRes, this.width, this.height);
    gl.uniform1f(this.uPx, this.dpr);
    if (res.textures) {
      res.textures.flush();
      gl.activeTexture(gl.TEXTURE0);
      gl.bindTexture(gl.TEXTURE_2D_ARRAY, res.textures.tex);
    }
    if (res.fontTex) {
      gl.activeTexture(gl.TEXTURE1);
      gl.bindTexture(gl.TEXTURE_2D, res.fontTex);
    }
    gl.bindVertexArray(this.vao);
    gl.bindBuffer(gl.ARRAY_BUFFER, this.instanceBuf);
    gl.bufferSubData(gl.ARRAY_BUFFER, 0, this.data, 0, this.count * FLOATS);
    gl.drawArraysInstanced(gl.TRIANGLE_STRIP, 0, 4, this.count);
    gl.bindVertexArray(null);
  }
}
