package expo.modules.liquidglasstabsandroid

/**
 * Liquid Glass AGSL shader for 1–8 rounded shapes (squircle corners). Shapes closer than
 * `merge` melt into each other with a liquid bridge (smooth union) — used by GlassContainer.
 *
 *  - interior: 1 texture sample (fast path)
 *  - bezel ring only: refraction + chromatic split + specular rim
 *  - optional lens zoom (switch / slider knob) and touch glow (buttons)
 */
internal const val GLASS_AGSL = """
uniform shader content;

uniform float4 s0; uniform float4 s1; uniform float4 s2; uniform float4 s3;   // l, t, r, b
uniform float4 s4; uniform float4 s5; uniform float4 s6; uniform float4 s7;
uniform float4 radiiA;     // radii of s0..s3
uniform float4 radiiB;     // radii of s4..s7
uniform float count;
uniform float merge;
uniform float bezel;
uniform float refraction;
uniform float chroma;
uniform float lightAngle;
uniform float highlight;
uniform float saturation;
uniform float4 tint;
uniform float zoom;
uniform float2 zoomCenter;
uniform float2 glowPos;
uniform float glowStrength;
uniform float glowRadius;

float cornerLength(float2 v) {
    if (v.x <= 0.0) { return v.y; }
    if (v.y <= 0.0) { return v.x; }
    return pow(pow(v.x, 2.6) + pow(v.y, 2.6), 0.384615);
}

float one(float2 p, float4 sh, float radius) {
    float2 c = (sh.xy + sh.zw) * 0.5;
    float2 b = (sh.zw - sh.xy) * 0.5;
    float rad = min(radius, min(b.x, b.y));
    float2 q = abs(p - c) - b + rad;
    return cornerLength(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - rad;
}

float smin(float a, float b, float k) {
    if (k <= 0.0) { return min(a, b); }
    float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
    return mix(b, a, h) - k * h * (1.0 - h);
}

float sdf(float2 p) {
    float d = one(p, s0, radiiA.x);
    if (count > 1.5) { d = smin(d, one(p, s1, radiiA.y), merge); }
    if (count > 2.5) { d = smin(d, one(p, s2, radiiA.z), merge); }
    if (count > 3.5) { d = smin(d, one(p, s3, radiiA.w), merge); }
    if (count > 4.5) { d = smin(d, one(p, s4, radiiB.x), merge); }
    if (count > 5.5) { d = smin(d, one(p, s5, radiiB.y), merge); }
    if (count > 6.5) { d = smin(d, one(p, s6, radiiB.z), merge); }
    if (count > 7.5) { d = smin(d, one(p, s7, radiiB.w), merge); }
    return d;
}

float3 shade(float3 col) {
    float lum = dot(col, float3(0.2126, 0.7152, 0.0722));
    col = mix(float3(lum), col, saturation);
    return mix(col, tint.rgb, tint.a);
}

float3 glow(float3 col, float2 coord) {
    if (glowStrength <= 0.001) { return col; }
    float2 g = coord - glowPos;
    return col + glowStrength * 0.22 * exp(-dot(g, g) / (glowRadius * glowRadius));
}

half4 main(float2 coord) {
    float d = sdf(coord);
    if (d > 1.0) { return half4(0.0); }

    float2 p = coord;
    if (zoom > 1.001) { p = zoomCenter + (coord - zoomCenter) / zoom; }

    float t = clamp(-d / max(bezel, 1.0), 0.0, 1.0);
    if (t >= 1.0) {
        return half4(half3(clamp(glow(shade(content.eval(p).rgb), coord), 0.0, 1.0)), 1.0);
    }

    float2 grad = float2(sdf(coord + float2(1.0, 0.0)) - d, sdf(coord + float2(0.0, 1.0)) - d);
    float2 n = grad / max(length(grad), 0.0001);
    float bend = pow(1.0 - t, 2.5);
    float2 offset = -n * bend * refraction;
    float ca = chroma * bend;
    float3 col;
    col.r = content.eval(p + offset * (1.0 + ca)).r;
    col.g = content.eval(p + offset).g;
    col.b = content.eval(p + offset * (1.0 - ca)).b;
    col = shade(col);

    float2 L = float2(cos(lightAngle), sin(lightAngle));
    float spec = pow(abs(dot(n, L)), 5.0);
    float rim = 1.0 - smoothstep(0.0, 2.5, -d);
    col += (rim * (0.2 + 0.8 * spec) + pow(1.0 - t, 4.0) * 0.12 * spec) * highlight;

    col = clamp(glow(col, coord), 0.0, 1.0);
    float alpha = 1.0 - smoothstep(-0.75, 0.75, d);
    return half4(half3(col) * alpha, alpha);
}
"""
