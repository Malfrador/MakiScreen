const int MCC_SORTED_RGB[128] = int[128](16640, 22272, 27136, 31744, 855309, 1118481, 1381653, 1644825, 1779806, 2171271, 2372989, 2573191, 2631720, 2638673, 2900377, 2960820, 3175280, 3361970, 3431092, 3487029, 3496300, 3549211, 3556123, 3559794, 3618780, 3881787, 4157148, 4210943, 4233878, 4276545, 4287876, 4399454, 4415005, 4418573, 4732196, 4741412, 4746392, 4882687, 4931366, 5000268, 5013401, 5191976, 5197647, 5225655, 5315355, 5329233, 5526663, 5658721, 5783852, 5789784, 5795116, 5801146, 5844093, 5864743, 5869585, 6085589, 6170738, 6316128, 6575154, 6704179, 6717235, 6724056, 6908265, 6966326, 7087140, 7105644, 7157401, 7182640, 7188501, 7368816, 7368884, 7488283, 7566977, 7697781, 7960859, 8087102, 8205720, 8339378, 8368696, 8375321, 8405847, 8543810, 8551254, 8662060, 8683048, 8684676, 8847360, 8881537, 8882055, 9079516, 9211020, 9277598, 9402184, 9474192, 9923917, 9984292, 10040115, 10043834, 10066329, 10526975, 10592548, 10791096, 10987431, 11164020, 11250603, 11445363, 11577398, 11685080, 11796480, 11841964, 11842740, 12217644, 12961068, 13092807, 13659534, 14010764, 14142786, 14188339, 14417920, 14473683, 14474460, 15066419, 15892389, 16247203, 16445005, 16711680, 16776437, 16777215);
const int MCC_SORTED_SYMBOL[128] = int[128](27, 24, 25, 26, 115, 112, 113, 114, 99, 47, 96, 127, 83, 91, 97, 44, 123, 98, 124, 80, 88, 103, 107, 67, 45, 43, 125, 46, 120, 81, 89, 95, 3, 75, 100, 104, 64, 126, 51, 82, 90, 39, 40, 121, 111, 87, 19, 35, 101, 23, 105, 65, 92, 0, 72, 122, 63, 41, 48, 102, 106, 66, 11, 36, 108, 84, 93, 1, 73, 42, 16, 59, 32, 20, 71, 49, 60, 94, 2, 74, 79, 37, 7, 109, 119, 85, 15, 55, 31, 17, 8, 33, 50, 21, 38, 56, 110, 61, 86, 18, 68, 34, 22, 76, 9, 4, 116, 62, 12, 52, 28, 57, 69, 10, 77, 5, 117, 58, 13, 53, 29, 70, 78, 6, 118, 14, 54, 30);
const int MCC_HEADER_RGB[16] = int[16](13659534, 3559794, 3559794, 15066419, 7188501, 11164020, 13659534, 4415005, 31744, 8368696, 11445363, 11445363, 9211020, 9211020, 11445363, 2372989);
const int MCC_GRADIENT_BASE = 14352;

ivec3 mccRgb(int index) {
    return ivec3(round(texelFetch(Sampler0, ivec2(index % 128, index / 128), 0).rgb * 255.0));
}

int mccPackedRgb(ivec3 rgb) {
    return (rgb.r << 16) | (rgb.g << 8) | rgb.b;
}

bool mccFilmTexture() {
    if (textureSize(Sampler0, 0) != ivec2(128, 128)) return false;
    for (int i = 0; i < 16; i++) {
        if (mccPackedRgb(mccRgb(i)) != MCC_HEADER_RGB[i]) return false;
    }
    return true;
}

int mccSymbol(int index) {
    int rgb = mccPackedRgb(mccRgb(index));
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

int mccBits(int base, int bit, int count) {
    int position = base + bit / 7;
    int shift = bit % 7;
    int value = mccSymbol(position);
    if (shift + count > 7) value |= mccSymbol(position + 1) << 7;
    return (value >> shift) & ((1 << count) - 1);
}

float mccLuma(ivec2 pixel, int detail) {
    ivec2 block = pixel / 4;
    int base = 16 + (block.y * 32 + block.x) * 14;
    float low = float(mccBits(base, 0, 8));
    float high = float(mccBits(base, 8, 8));
    int within = (pixel.y % 4) * 4 + pixel.x % 4;
    float selector = float(mccBits(base, 32 + within * 4, 4)) / 15.0;
    if ((detail & 4096) != 0) selector *= selector;
    return mix(low, high, selector);
}

int mccSignedGradient(int value) {
    return value >= 4 ? value - 8 : value;
}

int mccGradientRecord(int index) {
    int bit = index * 13;
    int position = MCC_GRADIENT_BASE + bit / 7;
    int shift = bit % 7;
    int value = mccSymbol(position) | (mccSymbol(position + 1) << 7)
        | (mccSymbol(position + 2) << 14);
    return (value >> shift) & 8191;
}

vec2 mccBlockChroma(ivec2 block, ivec2 pixel, int detail) {
    int index = block.y * 32 + block.x;
    int base = 16 + index * 14;
    vec2 chroma = vec2(float(mccBits(base, 16, 8)), float(mccBits(base, 24, 8))) - 128.0;
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
    int base = 16 + (block.y * 32 + block.x) * 14;
    return 0.5 * float(mccBits(base, 0, 8) + mccBits(base, 8, 8));
}

vec2 mccSmoothChroma(ivec2 pixel, int ownDetail) {
    ivec2 ownBlock = pixel / 4;
    int ownBase = 16 + (ownBlock.y * 32 + ownBlock.x) * 14;
    if (mccBits(ownBase, 8, 8) <= 1) return mccBlockChroma(ownBlock, pixel, ownDetail);
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
