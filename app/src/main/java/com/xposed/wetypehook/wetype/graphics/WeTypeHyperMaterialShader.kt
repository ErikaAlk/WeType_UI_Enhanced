package com.xposed.wetypehook.wetype.graphics

// XiaoAI IME 0.2.910 inner_shadow.agsl, with the distance function adapted to
// the module continuous corner path. Tint, falloff, stroke and AA are unchanged.
internal const val HYPER_MATERIAL_SHADOW_SHADER = """
uniform shader uInputContent;
uniform float2 uResolution;
uniform float4 uRadii;
uniform float uStrokeWidth;
uniform float uStrokeAlphaTop;
uniform float uStrokeAlphaBottom;
uniform float uOutset;
uniform float3 uShadowColor;
uniform float uShadowAlpha;
uniform float uShadowRadius;
// The module keeps its continuous corners. All lighting equations below
// remain those of XiaoAI's inner_shadow.agsl.
uniform float2 uContour[256];
uniform int uPointCount;
uniform float uCornerExtent;
float sdPanel(float2 point, float2 halfSize) {
    float2 p = point - halfSize;
    float2 q = abs(p) - halfSize;
    // Straight edges and the central area need no contour search.
    if (abs(p.x) < halfSize.x - uCornerExtent ||
        abs(p.y) < halfSize.y - uCornerExtent) {
        return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0)));
    }
    float distanceSquared = 1e20;
    float sign = 1.0;
    float2 previous = uContour[0];
    for (int i = 1; i < 256; ++i) {
        if (i >= uPointCount) break;
        float2 current = uContour[i];
        float2 edge = current - previous;
        float2 offset = point - previous;
        float2 nearest = offset - edge * clamp(dot(offset, edge) / max(dot(edge, edge), 1e-8), 0.0, 1.0);
        distanceSquared = min(distanceSquared, dot(nearest, nearest));
        if ((current.y > point.y) != (previous.y > point.y)) {
            float crossing = previous.x + (point.y - previous.y) * edge.x / edge.y;
            if (point.x < crossing) sign = -sign;
        }
        previous = current;
    }
    return sign * sqrt(distanceSquared);
}
half4 main(float2 fragCoord) {
    float2 center = uResolution * 0.5;
    float2 panelHalf = center - float2(uOutset);
    float2 p = fragCoord - center;
    float dist = sdPanel(fragCoord - float2(uOutset), panelHalf);
    float innerDist = -dist;
    if (innerDist > uStrokeWidth && dist < -1.0) {
        return half4(0.0);
    }
    float panelHeight = uResolution.y - 2.0 * uOutset;
    float vt = clamp((fragCoord.y - uOutset) / panelHeight, 0.0, 1.0);
    float strokeAlpha = mix(uStrokeAlphaTop, uStrokeAlphaBottom, vt);
    float stroke = 1.0 - smoothstep(uStrokeWidth - 1.0, uStrokeWidth, innerDist);
    float edgeCoverage = 1.0 - smoothstep(0.0, 1.0, dist);
    float strokeA = strokeAlpha * stroke * edgeCoverage;
    float outsideMask = smoothstep(-1.0, 0.0, dist);
    float falloff = 1.0 - clamp(dist / uShadowRadius, 0.0, 1.0);
    float shadowA = uShadowAlpha * falloff * outsideMask;
    float3 rgb = float3(1.0) * strokeA + uShadowColor * shadowA;
    float a = strokeA + shadowA;
    return half4(half(rgb.r), half(rgb.g), half(rgb.b), half(a));
}
"""
