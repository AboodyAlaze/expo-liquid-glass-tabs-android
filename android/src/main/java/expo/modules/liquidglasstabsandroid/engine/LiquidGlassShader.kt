package expo.modules.liquidglasstabsandroid.engine

/**
 * Liquid Glass AGSL shader — performance-tuned.
 *
 * Cost per pixel:
 *  - outside the shapes: 1 SDF eval, then exit (transparent)
 *  - interior (most of the glass): 1 SDF eval + 1 texture sample  ← fast path
 *  - bezel ring only: 3 SDF evals + 3 samples (refraction + chroma + specular)
 * Squircle corners use pow() only inside the corner regions.
 */
const val LIQUID_GLASS_AGSL = """
uniform shader content;

uniform float4 shape0;
uniform float4 shape1;
uniform float4 shape2;
uniform float4 shape3;
uniform float4 radii;
uniform float shapeCount;
uniform float merge;
uniform float bezel;
uniform float refraction;
uniform float chroma;
uniform float lightAngle;
uniform float highlight;
uniform float saturation;
uniform float4 tint;
uniform float adaptive;
uniform float zoom;
uniform float2 zoomCenter;
uniform float2 glowPos;
uniform float glowStrength;
uniform float glowRadius;

// Superellipse length; v is already >= 0. pow() only where both components are positive (corners).
float cornerLength(float2 v) {
    if (v.x <= 0.0) { return v.y; }
    if (v.y <= 0.0) { return v.x; }
    return pow(pow(v.x, 2.6) + pow(v.y, 2.6), 0.384615);
}

float sdSquircle(float2 p, float4 r, float rad) {
    float2 c = (r.xy + r.zw) * 0.5;
    float2 b = (r.zw - r.xy) * 0.5;
    rad = min(rad, min(b.x, b.y));
    float2 q = abs(p - c) - b + rad;
    return cornerLength(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - rad;
}

float smin(float a, float b, float k) {
    if (k <= 0.0) { return min(a, b); }
    float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
    return mix(b, a, h) - k * h * (1.0 - h);
}

float sceneSdf(float2 p) {
    float d = sdSquircle(p, shape0, radii.x);
    if (shapeCount > 1.5) { d = smin(d, sdSquircle(p, shape1, radii.y), merge); }
    if (shapeCount > 2.5) { d = smin(d, sdSquircle(p, shape2, radii.z), merge); }
    if (shapeCount > 3.5) { d = smin(d, sdSquircle(p, shape3, radii.w), merge); }
    return d;
}

float3 shade(float3 col) {
    float lum = dot(col, float3(0.2126, 0.7152, 0.0722));
    col = mix(float3(lum), col, saturation);
    col += mix(0.08, -0.04, lum) * adaptive;
    return mix(col, tint.rgb, tint.a);
}

// Soft light under the finger (iOS touch glow)
float3 addGlow(float3 col, float2 coord) {
    if (glowStrength <= 0.001) { return col; }
    float2 g = coord - glowPos;
    return col + glowStrength * 0.22 * exp(-dot(g, g) / (glowRadius * glowRadius));
}

half4 main(float2 coord) {
    float d = sceneSdf(coord);
    if (d > 1.0) { return half4(0.0); }

    // Lens magnification (used by the tab-bar lens)
    float2 p = coord;
    if (zoom > 1.001) { p = zoomCenter + (coord - zoomCenter) / zoom; }

    float t = clamp(-d / max(bezel, 1.0), 0.0, 1.0);

    // FAST PATH: deep inside the glass -> single sample, no gradient, no specular
    if (t >= 1.0) {
        return half4(half3(clamp(addGlow(shade(content.eval(p).rgb), coord), 0.0, 1.0)), 1.0);
    }

    // Bezel ring: normal from forward differences (2 extra SDF evals)
    float2 grad = float2(sceneSdf(coord + float2(1.0, 0.0)) - d,
                         sceneSdf(coord + float2(0.0, 1.0)) - d);
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
    float innerGlow = pow(1.0 - t, 4.0) * 0.12;
    col += (rim * (0.2 + 0.8 * spec) + innerGlow * spec) * highlight;

    col = clamp(addGlow(col, coord), 0.0, 1.0);
    float alpha = 1.0 - smoothstep(-0.75, 0.75, d);
    return half4(half3(col) * alpha, alpha);
}
"""
