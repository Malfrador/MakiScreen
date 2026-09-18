const int MCC_SORTED_RGB[128] = int[128](16640, 22272, 27136, 31744, 855309, 1118481, 1381653, 1644825, 1779806, 2171271, 2372989, 2573191, 2631720, 2638673, 2900377, 2960820, 3175280, 3361970, 3431092, 3487029, 3496300, 3549211, 3556123, 3559794, 3618780, 3881787, 4157148, 4210943, 4233878, 4276545, 4287876, 4399454, 4415005, 4418573, 4732196, 4741412, 4746392, 4882687, 4931366, 5000268, 5013401, 5191976, 5197647, 5225655, 5315355, 5329233, 5526663, 5658721, 5783852, 5789784, 5795116, 5801146, 5844093, 5864743, 5869585, 6085589, 6170738, 6316128, 6575154, 6704179, 6717235, 6724056, 6908265, 6966326, 7087140, 7105644, 7157401, 7182640, 7188501, 7368816, 7368884, 7488283, 7566977, 7697781, 7960859, 8087102, 8205720, 8339378, 8368696, 8375321, 8405847, 8543810, 8551254, 8662060, 8683048, 8684676, 8847360, 8881537, 8882055, 9079516, 9211020, 9277598, 9402184, 9474192, 9923917, 9984292, 10040115, 10043834, 10066329, 10526975, 10592548, 10791096, 10987431, 11164020, 11250603, 11445363, 11577398, 11685080, 11796480, 11841964, 11842740, 12217644, 12961068, 13092807, 13659534, 14010764, 14142786, 14188339, 14417920, 14473683, 14474460, 15066419, 15892389, 16247203, 16445005, 16711680, 16776437, 16777215);
const int MCC_SORTED_SYMBOL[128] = int[128](27, 24, 25, 26, 115, 112, 113, 114, 99, 47, 96, 127, 83, 91, 97, 44, 123, 98, 124, 80, 88, 103, 107, 67, 45, 43, 125, 46, 120, 81, 89, 95, 3, 75, 100, 104, 64, 126, 51, 82, 90, 39, 40, 121, 111, 87, 19, 35, 101, 23, 105, 65, 92, 0, 72, 122, 63, 41, 48, 102, 106, 66, 11, 36, 108, 84, 93, 1, 73, 42, 16, 59, 32, 20, 71, 49, 60, 94, 2, 74, 79, 37, 7, 109, 119, 85, 15, 55, 31, 17, 8, 33, 50, 21, 38, 56, 110, 61, 86, 18, 68, 34, 22, 76, 9, 4, 116, 62, 12, 52, 28, 57, 69, 10, 77, 5, 117, 58, 13, 53, 29, 70, 78, 6, 118, 14, 54, 30);
const int MCC_HEADER_RGB[16] = int[16](13659534, 3559794, 3559794, 15066419, 7188501, 11164020, 13659534, 14010764, 31744, 8368696, 11445363, 11445363, 9211020, 9211020, 11445363, 2372989);

ivec3 mccRgb(ivec2 position) {
    return ivec3(round(texelFetch(Sampler0, position, 0).rgb * 255.0));
}

int mccPackedRgb(ivec3 rgb) {
    return (rgb.r << 16) | (rgb.g << 8) | rgb.b;
}

bool mccFilmTexture() {
    if (textureSize(Sampler0, 0) != ivec2(128, 128)) return false;
    for (int i = 0; i < 16; i++) {
        ivec2 position = ivec2(i % 8, i / 8) * 16 + ivec2(15);
        if (mccPackedRgb(mccRgb(position)) != MCC_HEADER_RGB[i]) return false;
    }
    return true;
}

int mccSymbol(ivec2 position) {
    int rgb = mccPackedRgb(mccRgb(position));
    int lo = 0;
    int hi = 127;
    for (int step = 0; step < 8; step++) {
        if (lo > hi) break;
        int mid = (lo + hi) / 2;
        int value = MCC_SORTED_RGB[mid];
        if (value == rgb) return MCC_SORTED_SYMBOL[mid];
        if (value < rgb) lo = mid + 1;
        else hi = mid - 1;
    }
    return 0;
}

int mccMacroSymbol(ivec2 macro, int symbol) {
    return mccSymbol(macro * 16 + ivec2(symbol % 16, symbol / 16));
}

int mccRawBits(ivec2 macro, int bit, int count) {
    int position = bit / 7;
    int shift = bit % 7;
    int value = mccMacroSymbol(macro, position);
    if (shift + count > 7) value |= mccMacroSymbol(macro, position + 1) << 7;
    if (shift + count > 14) value |= mccMacroSymbol(macro, position + 2) << 14;
    return (value >> shift) & ((1 << count) - 1);
}

int mccBits(ivec2 macro, int bit, int count) {
    int record = bit / 109;
    int field = bit % 109;
    if (field < 16) return mccRawBits(macro, record * 16 + field, count);
    if (field < 32) return mccRawBits(macro, 259 + record * 28 + field - 16, count);
    if (field < 96) return mccRawBits(macro, 707 + record * 64 + field - 32, count);
    // The only cross-plane shader read is the twelve gradient bits plus shadow flag.
    if (field == 96 && count == 13) {
        return mccRawBits(macro, 259 + record * 28 + 16, 12)
            | (mccRawBits(macro, 1736 + record * 3, 1) << 12);
    }
    return mccRawBits(macro, 1736 + record * 3, count);
}

float mccSelectorLuma(int selector, float low, float high, int detail) {
    float fraction = float(selector) / 15.0;
    if ((detail & 4096) != 0) fraction *= fraction;
    return mix(low, high, fraction);
}

int mccRecordBit(ivec2 block) {
    return ((block.y % 4) * 4 + block.x % 4) * 109;
}

float mccLuma(ivec2 pixel, int detail) {
    ivec2 block = pixel / 4;
    ivec2 macro = block / 4;
    int base = mccRecordBit(block);
    float low = float(mccBits(macro, base, 8));
    float high = float(mccBits(macro, base + 8, 8));
    int mode = mccRawBits(macro, 1736 + (base / 109) * 3 + 1, 2);
    if (mode == 1) return 0.5 * (low + high);
    if (mode == 2) {
        vec4 corners = vec4(
            mccSelectorLuma(mccBits(macro, base + 32, 4), low, high, detail),
            mccSelectorLuma(mccBits(macro, base + 36, 4), low, high, detail),
            mccSelectorLuma(mccBits(macro, base + 40, 4), low, high, detail),
            mccSelectorLuma(mccBits(macro, base + 44, 4), low, high, detail));
        vec2 local = vec2(pixel % 4) / 3.0;
        return mix(mix(corners.x, corners.y, local.x), mix(corners.z, corners.w, local.x), local.y);
    }
    int within = (pixel.y % 4) * 4 + pixel.x % 4;
    float selector = float(mccBits(macro, base + 32 + within * 4, 4)) / 15.0;
    if ((detail & 4096) != 0) selector *= selector;
    return mix(low, high, selector);
}

int mccSignedGradient(int value) {
    return value >= 4 ? value - 8 : value;
}

int mccGradientRecord(int index) {
    ivec2 block = ivec2(index % 32, index / 32);
    return mccBits(block / 4, mccRecordBit(block) + 96, 13);
}

vec2 mccBlockChroma(ivec2 block, ivec2 pixel, int detail) {
    ivec2 macro = block / 4;
    int base = mccRecordBit(block);
    vec2 chroma = vec2(float(mccBits(macro, base + 16, 8)),
        float(mccBits(macro, base + 24, 8))) - 128.0;
    if ((detail & 4095) == 0) return chroma;
    vec4 slope = vec4(
        float(mccSignedGradient(detail & 7)),
        float(mccSignedGradient((detail >> 3) & 7)),
        float(mccSignedGradient((detail >> 6) & 7)),
        float(mccSignedGradient((detail >> 9) & 7))
    );
    vec2 local = clamp(vec2(pixel) + 0.5 - (vec2(block) * 4.0 + 2.0), vec2(-2.0), vec2(2.0));
    return chroma + vec2(dot(slope.xy, local), dot(slope.zw, local));
}

float mccBlockMeanLuma(ivec2 block) {
    ivec2 macro = block / 4;
    int base = mccRecordBit(block);
    return 0.5 * float(mccBits(macro, base, 8) + mccBits(macro, base + 8, 8));
}

vec2 mccSmoothChroma(ivec2 pixel, int ownDetail) {
    ivec2 ownBlock = pixel / 4;
    ivec2 ownMacro = ownBlock / 4;
    int ownBase = mccRecordBit(ownBlock);
    if (mccBits(ownMacro, ownBase + 8, 8) <= 1) return mccBlockChroma(ownBlock, pixel, ownDetail);
    vec2 position = (vec2(pixel) + 0.5) / 4.0 - 0.5;
    ivec2 first = ivec2(floor(position));
    vec2 fraction = fract(position);
    float guide = mccBlockMeanLuma(ownBlock);
    float blendRange = guide < 72.0 || (ownDetail & 4096) != 0 ? 32.0 : 20.0;
    vec2 sum = vec2(0.0);
    float total = 0.0;
    for (int dy = 0; dy < 2; dy++) {
        for (int dx = 0; dx < 2; dx++) {
            ivec2 block = clamp(first + ivec2(dx, dy), ivec2(0), ivec2(31));
            float spatial = (dx == 0 ? 1.0 - fraction.x : fraction.x)
                * (dy == 0 ? 1.0 - fraction.y : fraction.y);
            int detail = all(equal(block, ownBlock)) ? ownDetail : mccGradientRecord(block.y * 32 + block.x);
            float difference = abs(mccBlockMeanLuma(block) - guide) / blendRange;
            float weight = spatial / (1.0 + 4.0 * difference * difference);
            sum += mccBlockChroma(block, pixel, detail) * weight;
            total += weight;
        }
    }
    return sum / max(total, 0.0001);
}

vec3 mccFilmPixel(ivec2 pixel) {
    ivec2 block = pixel / 4;
    int detail = mccGradientRecord(block.y * 32 + block.x);
    float y = mccLuma(pixel, detail);
    vec2 chroma = mccSmoothChroma(pixel, detail);
    float cb = chroma.x;
    float cr = chroma.y;
    return clamp(vec3(y + 1.402 * cr, y - 0.344136 * cb - 0.714136 * cr,
                     y + 1.772 * cb) / 255.0, 0.0, 1.0);
}
