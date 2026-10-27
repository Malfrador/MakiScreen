#version 330
#extension GL_ARB_separate_shader_objects : require

uniform sampler2D CurrentSampler;
uniform sampler2D PreviousSampler;
uniform sampler2D StateSampler;

layout(location = 0) in vec2 texCoord;
layout(location = 0) out vec4 fragColor;

ivec3 framePixel(ivec2 topLeftPixel, ivec2 frameSize) {
    ivec2 mainSize = textureSize(CurrentSampler, 0);
    vec2 uv = vec2((float(topLeftPixel.x) + 0.5) / float(frameSize.x),
        1.0 - (float(topLeftPixel.y) + 0.5) / float(frameSize.y));
    ivec2 position = clamp(ivec2(uv * vec2(mainSize)), ivec2(0), mainSize - 1);
    return ivec3(round(texelFetch(CurrentSampler, position, 0).rgb * 255.0));
}

int sequence(ivec3 bytes) {
    return bytes.r | (bytes.g << 7) | (bytes.b << 14);
}

void main() {
    ivec2 frameSize = textureSize(PreviousSampler, 0);
    ivec3 marker = framePixel(ivec2(104, 8), frameSize);
    ivec2 pixel = clamp(ivec2(texCoord * vec2(frameSize)), ivec2(0), frameSize - 1);
    ivec2 mainSize = textureSize(CurrentSampler, 0);
    ivec2 mainPixel = clamp(ivec2(texCoord * vec2(mainSize)), ivec2(0), mainSize - 1);
    vec4 current = texelFetch(CurrentSampler, mainPixel, 0);
    if (marker.r != 255 || marker.g != 0 || (marker.b != 1 && marker.b != 2)) {
        fragColor = current;
        return;
    }
    int incoming = sequence(framePixel(ivec2(8, 8), frameSize));
    ivec3 committedBytes = ivec3(round(texelFetch(StateSampler, ivec2(0), 0).rgb * 255.0));
    int committed = sequence(committedBytes);
    // A missed interframe can happen when two bundles reach one client tick.
    // Decode the newest picture from the last displayed frame and resynchronize
    // the sequence. Holding until the next keyframe freezes playback for seconds.
    // Keyframes also refresh a reused sequence ID after a resource reload.
    if (marker.b == 2 && incoming == committed) {
        fragColor = texelFetch(PreviousSampler, pixel, 0);
        return;
    }
    if (pixel.y >= frameSize.y - 16
        && (pixel.x < 16 || (pixel.x >= 48 && pixel.x < 64)
            || (pixel.x >= 96 && pixel.x < 112))) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    ivec3 encoded = ivec3(round(current.rgb * 255.0));
    if (encoded.r < 128) {
        fragColor = vec4(float(encoded.r) / 127.0, current.g, current.b, 1.0);
        return;
    }
    ivec2 motion = ivec2(encoded.r - 192, (encoded.g & 127) - 64);
    int residual = encoded.b - 128;
    ivec2 source = clamp(pixel + ivec2(motion.x, -motion.y), ivec2(0), frameSize - 1);
    vec3 prediction = texelFetch(PreviousSampler, source, 0).rgb;
    fragColor = vec4(clamp(prediction + vec3(float(residual) / 255.0), 0.0, 1.0), 1.0);
}
