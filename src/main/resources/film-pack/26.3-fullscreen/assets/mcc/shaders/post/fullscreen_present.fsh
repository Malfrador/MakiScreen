#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D InSampler;
layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

void main() {
    vec2 frameSize = vec2(textureSize(InSampler, 0));
    vec2 windowSize = abs(vec2(1.0 / dFdx(texCoord.x), 1.0 / dFdy(texCoord.y)));
    float videoAspect = frameSize.x / frameSize.y;
    float windowAspect = windowSize.x / windowSize.y;
    vec2 extent = windowAspect > videoAspect
        ? vec2(videoAspect / windowAspect, 1.0)
        : vec2(1.0, windowAspect / videoAspect);
    vec2 uv = (texCoord - (vec2(1.0) - extent) * 0.5) / extent;
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
    } else {
        fragColor = texture(InSampler, uv);
    }
}
