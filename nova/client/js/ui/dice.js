// @ts-check
// Rolling dice: a small 3D renderer (canvas 2D) for the d4, d6, d8, d10, d12, d20 and Planechase's planar die. Each
// die tumbles in, bounces and comes to rest with the rolled face toward you. The motion ends exactly on that face: the
// die's orientation is the final one turned by angles that shrink to nothing, so no correction is visible at the end.

const PHI = (1 + Math.sqrt(5)) / 2;

/** @typedef {[number, number, number]} V3 */

const sub = (a, b) => /** @type {V3} */ ([a[0] - b[0], a[1] - b[1], a[2] - b[2]]);
const add = (a, b) => /** @type {V3} */ ([a[0] + b[0], a[1] + b[1], a[2] + b[2]]);
const mul = (a, s) => /** @type {V3} */ ([a[0] * s, a[1] * s, a[2] * s]);
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const cross = (a, b) => /** @type {V3} */ ([a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]);
const norm = (a) => { const l = Math.hypot(a[0], a[1], a[2]) || 1; return /** @type {V3} */ ([a[0] / l, a[1] / l, a[2] / l]); };

// ---------------------------------------------------------------- quaternions [w, x, y, z]

const qmul = (a, b) => [
  a[0] * b[0] - a[1] * b[1] - a[2] * b[2] - a[3] * b[3],
  a[0] * b[1] + a[1] * b[0] + a[2] * b[3] - a[3] * b[2],
  a[0] * b[2] - a[1] * b[3] + a[2] * b[0] + a[3] * b[1],
  a[0] * b[3] + a[1] * b[2] - a[2] * b[1] + a[3] * b[0],
];
const qaxis = (axis, ang) => { const s = Math.sin(ang / 2); return [Math.cos(ang / 2), axis[0] * s, axis[1] * s, axis[2] * s]; };
function qrot(q, v) {
  const [w, x, y, z] = q;
  const ix = w * v[0] + y * v[2] - z * v[1];
  const iy = w * v[1] + z * v[0] - x * v[2];
  const iz = w * v[2] + x * v[1] - y * v[0];
  const iw = -x * v[0] - y * v[1] - z * v[2];
  return /** @type {V3} */ ([ix * w + iw * -x + iy * -z - iz * -y, iy * w + iw * -y + iz * -x - ix * -z, iz * w + iw * -z + ix * -y - iy * -x]);
}
/** The rotation taking the orthonormal frame (r, u, n) to (x, y, z). */
function qFromFrame(r, u, n) {
  // rows of the rotation matrix are r, u, n
  const m00 = r[0], m01 = r[1], m02 = r[2], m10 = u[0], m11 = u[1], m12 = u[2], m20 = n[0], m21 = n[1], m22 = n[2];
  const tr = m00 + m11 + m22;
  let w, x, y, z;
  if (tr > 0) {
    const s = Math.sqrt(tr + 1) * 2;
    w = s / 4; x = (m21 - m12) / s; y = (m02 - m20) / s; z = (m10 - m01) / s;
  } else if (m00 > m11 && m00 > m22) {
    const s = Math.sqrt(1 + m00 - m11 - m22) * 2;
    w = (m21 - m12) / s; x = s / 4; y = (m01 + m10) / s; z = (m02 + m20) / s;
  } else if (m11 > m22) {
    const s = Math.sqrt(1 + m11 - m00 - m22) * 2;
    w = (m02 - m20) / s; x = (m01 + m10) / s; y = s / 4; z = (m12 + m21) / s;
  } else {
    const s = Math.sqrt(1 + m22 - m00 - m11) * 2;
    w = (m10 - m01) / s; x = (m02 + m20) / s; y = (m12 + m21) / s; z = s / 4;
  }
  return [w, x, y, z];
}

// ---------------------------------------------------------------- polyhedra

/**
 * @typedef {{v: V3[], faces: {ix: number[], n: V3, c: V3, up: V3, label: string}[], radius: number}} Shape
 */

/** Faces with outward winding, their centers, normals and the "up" of their numbers. */
function finish(verts, faceIdx, upOf) {
  const v = verts.map((p) => /** @type {V3} */ ([p[0], p[1], p[2]]));
  const radius = Math.max(...v.map((p) => Math.hypot(p[0], p[1], p[2])));
  const faces = faceIdx.map((ix) => {
    let c = /** @type {V3} */ ([0, 0, 0]);
    for (const i of ix) c = add(c, v[i]);
    c = mul(c, 1 / ix.length);
    let n = norm(cross(sub(v[ix[1]], v[ix[0]]), sub(v[ix[2]], v[ix[0]])));
    if (dot(n, c) < 0) { ix = ix.slice().reverse(); n = mul(n, -1); }
    let up = upOf(ix, c, v, n);
    up = norm(sub(up, mul(n, dot(up, n)))); // in the face's plane
    return { ix, n, c, up, label: '' };
  });
  return { v, faces, radius };
}

/** Points of a convex face sorted around its normal. */
function aroundNormal(ix, v, n) {
  let c = /** @type {V3} */ ([0, 0, 0]);
  for (const i of ix) c = add(c, v[i]);
  c = mul(c, 1 / ix.length);
  const a = norm(sub(v[ix[0]], c));
  const b = cross(n, a);
  return ix.slice().sort((i, j) => {
    const pi = sub(v[i], c), pj = sub(v[j], c);
    return Math.atan2(dot(pi, b), dot(pi, a)) - Math.atan2(dot(pj, b), dot(pj, a));
  });
}

/** All vertex triples at the shortest edge length: the faces of a deltahedron (tetra, octa, icosa). */
function triangles(v) {
  let e = Infinity;
  for (let i = 0; i < v.length; i++) for (let j = i + 1; j < v.length; j++) e = Math.min(e, Math.hypot(...sub(v[i], v[j])));
  const near = (i, j) => Math.abs(Math.hypot(...sub(v[i], v[j])) - e) < 1e-6;
  const out = [];
  for (let i = 0; i < v.length; i++) for (let j = i + 1; j < v.length; j++) for (let k = j + 1; k < v.length; k++) {
    if (near(i, j) && near(j, k) && near(i, k)) out.push([i, j, k]);
  }
  return out;
}

const toVertex = (ix, c, v) => sub(v[ix[0]], c);

/** Numbers 1..n with opposite faces adding up to n + 1 (as on real dice). */
function numberOpposite(shape) {
  const n = shape.faces.length;
  const done = new Set();
  let k = 1;
  shape.faces.forEach((f, i) => {
    if (done.has(i)) return;
    const j = shape.faces.findIndex((g, jj) => jj !== i && !done.has(jj) && dot(g.n, f.n) < -0.999);
    f.label = String(k);
    done.add(i);
    if (j >= 0) { shape.faces[j].label = String(n + 1 - k); done.add(j); }
    k++;
  });
  return shape;
}

function tetra() {
  const v = [[1, 1, 1], [1, -1, -1], [-1, 1, -1], [-1, -1, 1]];
  const s = finish(v, triangles(v), toVertex);
  s.faces.forEach((f, i) => { f.label = String(i + 1); });
  return s;
}

function cube() {
  const v = [];
  for (const x of [-1, 1]) for (const y of [-1, 1]) for (const z of [-1, 1]) v.push([x, y, z]);
  const faces = [];
  for (let axis = 0; axis < 3; axis++) for (const sgn of [-1, 1]) {
    const ix = v.map((p, i) => [p, i]).filter(([p]) => p[axis] === sgn).map(([, i]) => i);
    const n = /** @type {V3} */ ([0, 0, 0]);
    n[axis] = sgn;
    faces.push(aroundNormal(ix, v, n));
  }
  // numbers point at an edge (pips are drawn on a square grid)
  return numberOpposite(finish(v, faces, (ix, c, vv) => sub(mul(add(vv[ix[0]], vv[ix[1]]), 0.5), c)));
}

function octa() {
  const v = [[1, 0, 0], [-1, 0, 0], [0, 1, 0], [0, -1, 0], [0, 0, 1], [0, 0, -1]];
  return numberOpposite(finish(v, triangles(v), toVertex));
}

function icosa() {
  const v = [];
  for (const a of [-1, 1]) for (const b of [-PHI, PHI]) { v.push([0, a, b]); v.push([a, b, 0]); v.push([b, 0, a]); }
  return numberOpposite(finish(v, triangles(v), toVertex));
}

function dodeca() {
  const v = [];
  for (const a of [-1, 1]) for (const b of [-1, 1]) for (const c of [-1, 1]) v.push([a, b, c]);
  for (const a of [-1, 1]) for (const b of [-1, 1]) {
    v.push([0, a / PHI, b * PHI]);
    v.push([a / PHI, b * PHI, 0]);
    v.push([a * PHI, 0, b / PHI]);
  }
  // a face around each of these directions (the vertices of the dual icosahedron)
  const dirs = [];
  for (const a of [-1, 1]) for (const b of [-1, 1]) dirs.push(norm([0, a * PHI, b]), norm([a * PHI, b, 0]), norm([b, 0, a * PHI]));
  const faces = dirs.map((d) => {
    const best = v.map((p, i) => [dot(p, d), i]).sort((a, b) => b[0] - a[0]).slice(0, 5).map(([, i]) => i);
    return aroundNormal(best, v, d);
  });
  return numberOpposite(finish(v, faces, toVertex));
}

/** Pentagonal trapezohedron: two apexes, two rings of five; kite faces, numbers pointing at their apex. */
function d10() {
  const h = 1.15, z0 = 0.10557 * h; // the ring height that makes each kite flat
  const v = [[0, 0, h], [0, 0, -h]];
  for (let k = 0; k < 5; k++) v.push([Math.cos(k * 2 * Math.PI / 5), Math.sin(k * 2 * Math.PI / 5), z0]);
  for (let k = 0; k < 5; k++) v.push([Math.cos((k + 0.5) * 2 * Math.PI / 5), Math.sin((k + 0.5) * 2 * Math.PI / 5), -z0]);
  const U = (k) => 2 + (k % 5), L = (k) => 7 + (k % 5);
  const faces = [];
  for (let k = 0; k < 5; k++) faces.push([0, U(k), L(k), U(k + 1)]);
  for (let k = 0; k < 5; k++) faces.push([1, L(k), U(k + 1), L(k + 1)]);
  return numberOpposite(finish(v, faces, (ix, c, vv) => sub(ix.includes(0) ? vv[0] : vv[1], c)));
}

/** @type {Record<string, Shape>} */
const SHAPES = {};
function shapeFor(kind) {
  if (!SHAPES[kind]) {
    SHAPES[kind] = kind === 'd4' ? tetra() : kind === 'd6' || kind === 'planar' ? cube() : kind === 'd8' ? octa()
      : kind === 'd10' ? d10() : kind === 'd12' ? dodeca() : icosa();
  }
  return SHAPES[kind];
}

/** Body and ink colors of each die. */
const STYLE = {
  d4: { body: [46, 140, 88], ink: '#f6f1e0' },
  d6: { body: [236, 228, 208], ink: '#1f1d1a' },
  d8: { body: [120, 82, 196], ink: '#f6f1e0' },
  d10: { body: [38, 104, 178], ink: '#f6f1e0' },
  d12: { body: [178, 58, 46], ink: '#f6f1e0' },
  d20: { body: [128, 26, 44], ink: '#f4c979' },
  planar: { body: [26, 26, 32], ink: '#f2f2f2' },
};

/** The die drawn for a number of sides (unusual dice look like a d20 or d6 with the right number on top). */
export function kindFor(sides, planar = false) {
  if (planar) return 'planar';
  return { 4: 'd4', 6: 'd6', 8: 'd8', 10: 'd10', 12: 'd12', 20: 'd20' }[sides] || (sides <= 6 ? 'd6' : 'd20');
}

// ---------------------------------------------------------------- drawing

const LIGHT = norm([-0.45, 0.65, 1]);
const PIPS = {
  1: [[0, 0]], 2: [[-1, -1], [1, 1]], 3: [[-1, -1], [0, 0], [1, 1]], 4: [[-1, -1], [1, -1], [-1, 1], [1, 1]],
  5: [[-1, -1], [1, -1], [0, 0], [-1, 1], [1, 1]], 6: [[-1, -1], [1, -1], [-1, 0], [1, 0], [-1, 1], [1, 1]],
};

/**
 * One die: kind, the face it lands on (its label), where it rests on the canvas, and its own motion.
 * @param {string} kind
 * @param {string} result the label of the face that ends up toward the viewer
 */
function makeDie(kind, result, x, y, size, delay) {
  const shape = shapeFor(kind);
  let face = shape.faces.find((f) => f.label === result);
  if (!face) face = shape.faces.find((f) => f.label === String(shape.faces.length)) || shape.faces[0];
  const r = norm(cross(face.up, face.n));
  const q = qFromFrame(r, face.up, face.n);
  const rand = () => Math.random() * 2 - 1;
  return {
    kind, shape, result, x, y, size, delay,
    qFinal: q, qFinalFace: face,
    a1: norm([rand(), rand(), rand()]), a2: norm([rand(), rand(), rand()]),
    s1: (3 + Math.random() * 2) * Math.PI * (Math.random() < 0.5 ? -1 : 1),
    s2: (2 + Math.random() * 1.5) * Math.PI * (Math.random() < 0.5 ? -1 : 1),
    fromX: -1 - Math.random() * 0.4,
    dim: false,
    /** a die with more sides than its shape (d100...): the resting face shows the roll instead */
    shown: shape.faces.some((f) => f.label === result) ? '' : result,
  };
}

/** Orientation and position at time t (0..1 of its roll). */
function poseAt(d, t) {
  const e = 1 - Math.pow(1 - Math.min(1, t), 3); // ease-out
  const left = 1 - e;
  const q = qmul(d.qFinal, qmul(qaxis(d.a1, d.s1 * left * left), qaxis(d.a2, d.s2 * left)));
  // slides in from the side, bouncing on the table twice
  const px = d.x + d.fromX * d.size * 3 * Math.pow(left, 1.6);
  const bounce = t < 1 ? Math.abs(Math.cos(t * Math.PI * 2.6)) * Math.pow(1 - t, 2.2) : 0;
  const lift = d.size * 1.6 * bounce;
  return { q, x: px, y: d.y - lift, lift };
}

function shade(rgb, k) {
  return `rgb(${Math.round(rgb[0] * k)},${Math.round(rgb[1] * k)},${Math.round(rgb[2] * k)})`;
}

/**
 * Draws a die at a pose. Perspective: the camera looks down the z axis from distance `cam` (in die radii).
 * @param {CanvasRenderingContext2D} ctx
 */
function drawDie(ctx, d, pose, final) {
  const { shape } = d;
  const style = STYLE[d.kind];
  const scale = d.size / shape.radius;
  const cam = 6;
  const proj = (p) => {
    const w = cam / (cam - p[2] / shape.radius);
    return [pose.x + p[0] * scale * w, pose.y - p[1] * scale * w];
  };
  const verts = shape.v.map((p) => qrot(pose.q, p));
  // shadow on the table, smaller and fainter while the die is in the air
  const lift = Math.max(0, pose.lift);
  ctx.save();
  ctx.globalAlpha = (d.dim ? 0.12 : 0.28) * Math.max(0.25, 1 - lift / (d.size * 2.5));
  ctx.fillStyle = '#000';
  ctx.beginPath();
  ctx.ellipse(pose.x, d.y + d.size * 0.9, d.size * (0.95 - Math.min(0.4, lift / d.size * 0.15)), d.size * 0.22, 0, 0, Math.PI * 2);
  ctx.fill();
  ctx.restore();
  ctx.save();
  if (d.dim) ctx.globalAlpha = 0.45;
  ctx.lineJoin = 'round';
  for (const f of shape.faces) {
    const n = qrot(pose.q, f.n);
    if (n[2] <= 0.02) continue; // faces away from the viewer
    const pts = f.ix.map((i) => proj(verts[i]));
    const light = 0.42 + 0.68 * Math.max(0, dot(n, LIGHT));
    ctx.beginPath();
    pts.forEach((p, i) => (i ? ctx.lineTo(p[0], p[1]) : ctx.moveTo(p[0], p[1])));
    ctx.closePath();
    ctx.fillStyle = shade(style.body, Math.min(1.25, light));
    ctx.fill();
    ctx.strokeStyle = shade(style.body, Math.min(1.5, light * 1.25));
    ctx.lineWidth = Math.max(1, d.size * 0.03);
    ctx.stroke();
    // the face's number (or pips, or the planar symbol), laid onto the face
    const c = qrot(pose.q, f.c);
    const up = qrot(pose.q, f.up);
    const right = norm(cross(up, n));
    const unit = shape.radius * 0.1;
    const o = proj(c), ex = proj(add(c, mul(right, unit))), ey = proj(add(c, mul(up, -unit)));
    const k = d.size / 10;
    ctx.save();
    ctx.setTransform(...currentScale(ctx, (ex[0] - o[0]) / k, (ex[1] - o[1]) / k, (ey[0] - o[0]) / k, (ey[1] - o[1]) / k, o[0], o[1]));
    ctx.globalAlpha *= Math.min(1, n[2] * 2.2);
    const isResult = final && f === d.qFinalFace;
    drawMark(ctx, d, f, style, isResult);
    ctx.restore();
  }
  ctx.restore();
}

/** setTransform arguments composed with the canvas's own device-pixel scale. */
function currentScale(ctx, a, b, c, d, e, f) {
  const s = /** @type {any} */ (ctx).novaDpr || 1;
  return [a * s, b * s, c * s, d * s, e * s, f * s];
}

/** In face units (10 = the die's size). */
function drawMark(ctx, d, f, style, isResult) {
  ctx.fillStyle = style.ink;
  if (d.kind === 'd6') {
    const pips = PIPS[Number(f.label)] || [];
    const sp = d.size * 0.3;
    for (const [px, py] of pips) {
      ctx.beginPath();
      ctx.arc(px * sp * 0.95, py * sp * 0.95, d.size * 0.12, 0, Math.PI * 2);
      ctx.fill();
    }
    return;
  }
  if (d.kind === 'planar') {
    const sym = planarFace(f.label);
    if (sym === 'walk') drawPlaneswalk(ctx, d.size * 0.62, style.ink);
    else if (sym === 'chaos') drawChaos(ctx, d.size * 0.55, style.ink);
    return;
  }
  const label = d.shown && d.qFinalFace === f ? d.shown : f.label;
  const fontPx = d.size * (d.kind === 'd4' ? 0.5 : d.kind === 'd8' ? 0.46 : d.kind === 'd10' ? 0.46 : d.kind === 'd12' ? 0.48 : 0.4) * (label.length > 2 ? 0.7 : label.length > 1 ? 0.9 : 1);
  ctx.font = `800 ${fontPx}px "Segoe UI", system-ui, sans-serif`;
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  const dy = d.kind === 'd10' ? -d.size * 0.05 : 0;
  if (isResult) {
    ctx.shadowColor = 'rgba(255,214,120,.9)';
    ctx.shadowBlur = d.size * 0.25;
  }
  ctx.fillText(label, 0, dy);
  // 6 and 9 are underlined, so they aren't confused
  if ((label === '6' || label === '9') && d.kind !== 'd4') {
    const w = ctx.measureText(label).width;
    ctx.fillRect(-w / 2, dy + fontPx * 0.48, w, Math.max(1, fontPx * 0.08));
  }
}

/** The planar die: Planeswalk on one face, Chaos on the opposite one, blanks elsewhere. */
function planarFace(label) {
  return label === '1' ? 'walk' : label === '6' ? 'chaos' : '';
}

function drawPlaneswalk(ctx, s, color) {
  // a stylized planeswalker symbol: a tall spike with two side spikes and an arc
  ctx.fillStyle = color;
  ctx.beginPath();
  ctx.moveTo(0, -s * 0.95);
  ctx.lineTo(s * 0.13, s * 0.15);
  ctx.lineTo(0, s * 0.3);
  ctx.lineTo(-s * 0.13, s * 0.15);
  ctx.closePath();
  ctx.fill();
  for (const sgn of [-1, 1]) {
    ctx.beginPath();
    ctx.moveTo(sgn * s * 0.42, -s * 0.55);
    ctx.lineTo(sgn * s * 0.5, s * 0.05);
    ctx.lineTo(sgn * s * 0.36, s * 0.12);
    ctx.lineTo(sgn * s * 0.3, -s * 0.2);
    ctx.closePath();
    ctx.fill();
  }
  ctx.beginPath();
  ctx.ellipse(0, s * 0.25, s * 0.62, s * 0.42, 0, 0.15 * Math.PI, 0.85 * Math.PI);
  ctx.lineWidth = s * 0.1;
  ctx.strokeStyle = color;
  ctx.stroke();
}

function drawChaos(ctx, s, color) {
  // the chaos symbol: a ring with four arrows pointing outward
  ctx.strokeStyle = color;
  ctx.fillStyle = color;
  ctx.lineWidth = s * 0.12;
  ctx.beginPath();
  ctx.arc(0, 0, s * 0.42, 0, Math.PI * 2);
  ctx.stroke();
  for (let k = 0; k < 4; k++) {
    const a = k * Math.PI / 2 + Math.PI / 4;
    ctx.save();
    ctx.rotate(a);
    ctx.beginPath();
    ctx.moveTo(s * 0.5, -s * 0.12);
    ctx.lineTo(s * 0.95, 0);
    ctx.lineTo(s * 0.5, s * 0.12);
    ctx.closePath();
    ctx.fill();
    ctx.restore();
  }
}

// ---------------------------------------------------------------- the roll

const DURATION = 1500;

/**
 * Rolls dice on a canvas. `dice`: [{kind, result (face label), dim}]; the callback runs once they all rest.
 * Returns {skip} to jump to the end (a click on the canvas does that too).
 * @param {HTMLCanvasElement} canvas
 */
export function rollDice(canvas, dice, onDone = () => {}) {
  const ctx = /** @type {CanvasRenderingContext2D} */ (canvas.getContext('2d'));
  const dpr = Math.min(2, window.devicePixelRatio || 1);
  const w = canvas.clientWidth || 480, h = canvas.clientHeight || 200;
  canvas.width = Math.round(w * dpr);
  canvas.height = Math.round(h * dpr);
  /** @type {any} */ (ctx).novaDpr = dpr;
  const n = dice.length;
  const size = Math.min(h * 0.3, (w / Math.max(1, n)) * 0.3, 64);
  const gap = Math.min(size * 3, (w - size * 2) / Math.max(1, n));
  const startX = w / 2 - (gap * (n - 1)) / 2;
  const list = dice.map((d, i) => Object.assign(makeDie(d.kind, d.result, startX + i * gap, h * 0.5, size, i * 90), { dim: !!d.dim }));
  const t0 = performance.now();
  let skipped = false;
  let done = false;
  const total = DURATION + (n - 1) * 90;
  const frame = () => {
    const now = performance.now();
    const elapsed = skipped ? total : now - t0;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, w, h);
    for (const d of list) {
      const t = Math.max(0, Math.min(1, (elapsed - d.delay) / DURATION));
      drawDie(ctx, d, poseAt(d, t), t >= 1);
    }
    if (elapsed >= total) {
      if (!done) { done = true; onDone(); }
      return;
    }
    requestAnimationFrame(frame);
  };
  canvas.addEventListener('click', () => { skipped = true; if (!done) frame(); });
  requestAnimationFrame(frame);
  return { skip: () => { skipped = true; if (!done) frame(); } };
}
