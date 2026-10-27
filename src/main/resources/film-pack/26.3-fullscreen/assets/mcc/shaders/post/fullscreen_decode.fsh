#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D CurrentSampler;
uniform sampler2D AnchorSampler;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    ivec2 outputSize = textureSize(AnchorSampler, 0);
    ivec2 mainSize = textureSize(CurrentSampler, 0);
    ivec2 mainPixel = clamp(ivec2(texCoord * vec2(mainSize)), ivec2(0), mainSize - 1);
    vec4 current = texelFetch(CurrentSampler, mainPixel, 0);
    if (current.r < 0.999) {
        fragColor = current;
        return;
    }
    if (current.g < 0.01 && current.b < 0.01) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    ivec2 pixel = clamp(ivec2(texCoord * vec2(outputSize)), ivec2(0), outputSize - 1);
    ivec2 encoded = ivec2(round(current.gb * 255.0));
    ivec2 motion = (encoded & ivec2(127)) - ivec2(64);
    ivec2 source = clamp(pixel + ivec2(motion.x, -motion.y), ivec2(0), outputSize - 1);
    fragColor = texelFetch(AnchorSampler, source, 0);
}
