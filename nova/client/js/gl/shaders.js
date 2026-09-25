// @ts-check
// One program draws everything: each instance is a rotated quad with a "kind".
//   0 card image (texture array layer, rounded corners)
//   1 solid rounded rectangle
//   2 SDF glyph (font atlas), optional outline
//   3 soft glow / shadow around a rounded rectangle
//   4 rounded ring (border only)
//   5 filled circle

export const VERT = `#version 300 es
precision highp float;
layout(location=0) in vec2 aCorner;
layout(location=1) in vec4 aPosSize;   // cx, cy, w, h (css px)
layout(location=2) in vec4 aRotKind;   // rotation, kind, layer, corner radius
layout(location=3) in vec4 aUV;        // u0, v0, u1, v1
layout(location=4) in vec4 aColor;     // straight rgba
layout(location=5) in vec4 aParam;     // kind specific
uniform vec2 uRes;
out vec2 vUV;
out vec2 vLocal;
flat out vec2 vHalf;
flat out int vKind;
flat out float vLayer;
flat out float vRadius;
out vec4 vColor;
flat out vec4 vParam;
void main() {
  vec2 local = aCorner * aPosSize.zw;
  float c = cos(aRotKind.x), s = sin(aRotKind.x);
  vec2 world = aPosSize.xy + vec2(local.x * c - local.y * s, local.x * s + local.y * c);
  vec2 clip = world / uRes * 2.0 - 1.0;
  gl_Position = vec4(clip.x, -clip.y, 0.0, 1.0);
  vUV = mix(aUV.xy, aUV.zw, aCorner + 0.5);
  vLocal = local;
  vHalf = aPosSize.zw * 0.5;
  vKind = int(aRotKind.y + 0.5);
  vLayer = aRotKind.z;
  vRadius = aRotKind.w;
  vColor = aColor;
  vParam = aParam;
}`;

export const FRAG = `#version 300 es
precision highp float;
precision highp sampler2DArray;
uniform sampler2DArray uCards;
uniform sampler2D uFont;
uniform float uPx;      // device pixels per css pixel
in vec2 vUV;
in vec2 vLocal;
flat in vec2 vHalf;
flat in int vKind;
flat in float vLayer;
flat in float vRadius;
in vec4 vColor;
flat in vec4 vParam;
out vec4 outColor;

float sdRound(vec2 p, vec2 b, float r) {
  vec2 q = abs(p) - b + r;
  return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

void main() {
  float aa = 0.75 / uPx;
  vec4 col;
  if (vKind == 0) {
    float d = sdRound(vLocal, vHalf, vRadius);
    float mask = 1.0 - smoothstep(-aa, aa, d);
    vec4 tex = texture(uCards, vec3(vUV, vLayer));
    vec3 rgb = tex.rgb * vColor.rgb;
    float g = dot(rgb, vec3(0.299, 0.587, 0.114));
    rgb = mix(rgb, vec3(g) * 0.8, vParam.x);          // desaturate (phased out, lost players)
    rgb *= 1.0 + vParam.y;                            // brighten (hover)
    // inner edge darkening gives cards some depth
    float edge = smoothstep(-6.0, 0.0, d);
    rgb *= 1.0 - edge * 0.18 * vParam.z;
    float a = mask * vColor.a * tex.a;
    col = vec4(rgb * a, a);
  } else if (vKind == 1) {
    float d = sdRound(vLocal, vHalf, vRadius);
    float a = (1.0 - smoothstep(-aa, aa, d)) * vColor.a;
    col = vec4(vColor.rgb * a, a);
  } else if (vKind == 2) {
    float dist = texture(uFont, vUV).r;
    float w = max(fwidth(dist) * 0.7, 0.004);
    float fill = smoothstep(0.75 - w, 0.75 + w, dist);
    float a = fill;
    vec3 rgb = vColor.rgb;
    if (vParam.x > 0.0) {
      float o = smoothstep(vParam.x - w, vParam.x + w, dist);
      rgb = mix(vParam.yzw, vColor.rgb, fill);
      a = o;
    }
    a *= vColor.a;
    col = vec4(rgb * a, a);
  } else if (vKind == 3) {
    float blur = max(vParam.x, 0.5);
    float d = sdRound(vLocal, vHalf - blur, vRadius);
    float a = 1.0 - smoothstep(0.0, blur, d);
    a = a * a;
    a *= mix(1.0, smoothstep(-2.0, 1.0, d), vParam.y);  // hollow: don't cover the card itself
    a *= vColor.a;
    col = vec4(vColor.rgb * a, a);
  } else if (vKind == 4) {
    float d = sdRound(vLocal, vHalf, vRadius);
    float a = (1.0 - smoothstep(-aa, aa, d)) * smoothstep(-vParam.x - aa, -vParam.x + aa, d) * vColor.a;
    col = vec4(vColor.rgb * a, a);
  } else {
    float d = length(vLocal) - vHalf.x;
    float a = (1.0 - smoothstep(-aa, aa, d)) * vColor.a;
    col = vec4(vColor.rgb * a, a);
  }
  outColor = col;
}`;
