#version 330
#extension GL_ARB_separate_shader_objects : require

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
#include <minecraft:fog.glsl>
#include <minecraft:sample_lightmap.glsl>
#endif
#include <minecraft:dynamictransforms.glsl>
#include <minecraft:projection.glsl>

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;
layout(location = 2) in vec2 UV0;
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
layout(location = 3) in ivec2 UV2;
#endif

uniform sampler2D Sampler0;
#if !defined(IS_GRAYSCALE) && !defined(IS_GUI)
#include <minecraft:mcc_film_vertex.glsl>
#endif

#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
uniform sampler2D Sampler2;
layout(location = 0) out float sphericalVertexDistance;
layout(location = 1) out float cylindricalVertexDistance;
#endif
layout(location = 2) out vec4 vertexColor;
layout(location = 3) out vec2 texCoord0;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
    sphericalVertexDistance = fog_spherical_distance(Position);
    cylindricalVertexDistance = fog_cylindrical_distance(Position);
    vertexColor = Color * sample_lightmap(Sampler2, UV2);
#else
    vertexColor = Color;
#endif
    texCoord0 = UV0;

#if !defined(IS_GRAYSCALE) && !defined(IS_GUI)
    if (mccFilmTexture() && mccFullscreenTexture()) {
        float tileX = float(mccSymbol(ivec2(15, 31)));
        float tileY = float(mccSymbol(ivec2(31, 31)));
        float tilesWide = float(max(1, mccSymbol(ivec2(47, 31))));
        float tilesHigh = float(max(1, mccSymbol(ivec2(63, 31))));
        vec2 screenUv = (vec2(tileX, tileY) + UV0) / vec2(tilesWide, tilesHigh);
        // The world text pipeline uses reverse depth (GREATER_OR_EQUAL).
        // Keep the carrier just inside its near plane so world geometry cannot hide it.
        gl_Position = vec4(screenUv.x * 2.0 - 1.0, 1.0 - screenUv.y * 2.0, 0.999, 1.0);
        vertexColor = vec4(1.0);
#if !defined(IS_GUI) && !defined(IS_SEE_THROUGH)
        sphericalVertexDistance = 0.0;
        cylindricalVertexDistance = 0.0;
#endif
    }
#endif
}
