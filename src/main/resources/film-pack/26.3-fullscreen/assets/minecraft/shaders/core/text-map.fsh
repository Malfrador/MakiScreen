#version 330
#extension GL_ARB_separate_shader_objects : require

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#include <minecraft:fog.glsl>
#endif

#include <minecraft:dynamictransforms.glsl>
#include <minecraft:oit.glsl>

uniform sampler2D Sampler0;

#if !defined(IS_GRAYSCALE) && !defined(IS_GUI)
#include <minecraft:mcc_film_vertex.glsl>
#endif

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
layout(location = 0) in float sphericalVertexDistance;
layout(location = 1) in float cylindricalVertexDistance;
#endif

layout(location = 2) in vec4 vertexColor;
layout(location = 3) in vec2 texCoord0;

#ifndef OIT_ALPHA_ONLY
layout(location = 0) out vec4 fragColor;
#endif

vec4 calculateFinalColor(vec4 color) {
    #ifdef OIT_ACCUMULATE
    color = sampleColorForAccumulation(color);
    #endif

    #if !defined(IS_SEE_THROUGH) && !defined(IS_GUI)
    #ifdef OIT_ACCUMULATE
    vec4 fogColor = vec4(FogColor.rgb * color.a, FogColor.a);
    #else
    vec4 fogColor = FogColor;
    #endif
    color = apply_fog(color, sphericalVertexDistance, cylindricalVertexDistance,
        FogEnvironmentalStart, FogEnvironmentalEnd, FogRenderDistanceStart, FogRenderDistanceEnd, fogColor);
    #endif

    return color;
}

void main() {
#if !defined(IS_GRAYSCALE) && !defined(IS_GUI)
    if (mccFilmTexture() && mccFullscreenTexture()
        && mccSymbol(ivec2(127, 15)) == 7) {
        ivec2 pixel = clamp(ivec2(floor(texCoord0 * 128.0)), ivec2(0), ivec2(127));
        if (pixel.y < 32 && (pixel.x & 15) == 15 && (pixel.y & 15) == 15) {
            pixel.x -= 1;
        }
        vec4 mapColor = texelFetch(Sampler0, pixel, 0);
        mapColor.r = min(mapColor.r, 254.0 / 255.0);
        #ifdef OIT_ALPHA_ONLY
        executeAlphaOnlyPhase(gl_FragCoord.z, mapColor.a);
        #else
        #ifdef OIT_ACCUMULATE
        fragColor = sampleColorForAccumulation(mapColor);
        #else
        fragColor = mapColor;
        #endif
        #endif
        return;
    }
#endif

    #ifdef IS_GRAYSCALE
    vec4 texColor = texture(Sampler0, texCoord0).rrrr;
    #else
    vec4 texColor = texture(Sampler0, texCoord0);
    #endif

    vec4 color = texColor * vertexColor * ColorModulator;
    if (color.a < 0.1) discard;

    #ifdef OIT_ALPHA_ONLY
    executeAlphaOnlyPhase(gl_FragCoord.z, color.a);
    #else
    fragColor = calculateFinalColor(color);
    #endif
}
