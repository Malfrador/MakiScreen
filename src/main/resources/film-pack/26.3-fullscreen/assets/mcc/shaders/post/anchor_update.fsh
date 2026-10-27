#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D CurrentSampler;
uniform sampler2D ResolvedSampler;
uniform sampler2D AnchorSampler;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    ivec2 frameSize = textureSize(AnchorSampler, 0);
    ivec2 mainSize = textureSize(CurrentSampler, 0);
    vec2 markerUv = vec2(2.5 / float(frameSize.x), 1.0 - 2.5 / float(frameSize.y));
    ivec2 markerPixel = clamp(ivec2(markerUv * vec2(mainSize)), ivec2(0), mainSize - 1);
    vec3 marker = texelFetch(CurrentSampler, markerPixel, 0).rgb;
    bool newAnchor = marker.r > 0.999 && marker.g < 0.01 && marker.b < 0.01;
    ivec2 pixel = clamp(ivec2(texCoord * vec2(frameSize)), ivec2(0), frameSize - 1);
    fragColor = newAnchor
        ? texelFetch(ResolvedSampler, pixel, 0)
        : texelFetch(AnchorSampler, pixel, 0);
}
