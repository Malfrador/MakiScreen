const int MCC_SORTED_RGB[128] = int[128](16640, 22272, 27136, 31744, 855309, 1118481, 1381653, 1644825, 1779806, 2171271, 2372989, 2573191, 2631720, 2638673, 2900377, 2960820, 3175280, 3361970, 3431092, 3487029, 3496300, 3549211, 3556123, 3559794, 3618780, 3881787, 4157148, 4210943, 4233878, 4276545, 4287876, 4399454, 4415005, 4418573, 4732196, 4741412, 4746392, 4882687, 4931366, 5000268, 5013401, 5191976, 5197647, 5225655, 5315355, 5329233, 5526663, 5658721, 5783852, 5789784, 5795116, 5801146, 5844093, 5864743, 5869585, 6085589, 6170738, 6316128, 6575154, 6704179, 6717235, 6724056, 6908265, 6966326, 7087140, 7105644, 7157401, 7182640, 7188501, 7368816, 7368884, 7488283, 7566977, 7697781, 7960859, 8087102, 8205720, 8339378, 8368696, 8375321, 8405847, 8543810, 8551254, 8662060, 8683048, 8684676, 8847360, 8881537, 8882055, 9079516, 9211020, 9277598, 9402184, 9474192, 9923917, 9984292, 10040115, 10043834, 10066329, 10526975, 10592548, 10791096, 10987431, 11164020, 11250603, 11445363, 11577398, 11685080, 11796480, 11841964, 11842740, 12217644, 12961068, 13092807, 13659534, 14010764, 14142786, 14188339, 14417920, 14473683, 14474460, 15066419, 15892389, 16247203, 16445005, 16711680, 16776437, 16777215);
const int MCC_SORTED_SYMBOL[128] = int[128](27, 24, 25, 26, 115, 112, 113, 114, 99, 47, 96, 127, 83, 91, 97, 44, 123, 98, 124, 80, 88, 103, 107, 67, 45, 43, 125, 46, 120, 81, 89, 95, 3, 75, 100, 104, 64, 126, 51, 82, 90, 39, 40, 121, 111, 87, 19, 35, 101, 23, 105, 65, 92, 0, 72, 122, 63, 41, 48, 102, 106, 66, 11, 36, 108, 84, 93, 1, 73, 42, 16, 59, 32, 20, 71, 49, 60, 94, 2, 74, 79, 37, 7, 109, 119, 85, 15, 55, 31, 17, 8, 33, 50, 21, 38, 56, 110, 61, 86, 18, 68, 34, 22, 76, 9, 4, 116, 62, 12, 52, 28, 57, 69, 10, 77, 5, 117, 58, 13, 53, 29, 70, 78, 6, 118, 14, 54, 30);
const int MCC_HEADER_RGB[16] = int[16](13659534, 3559794, 3559794, 15066419, 7188501, 11164020, 13659534, 14010764, 31744, 8368696, 11445363, 11445363, 9211020, 9211020, 11445363, 2372989);

ivec3 mccRgb(ivec2 position) {
    return ivec3(round(texelFetch(Sampler0, position, 0).rgb * 255.0));
}

int mccPackedRgb(ivec3 rgb) {
    return (rgb.r << 16) | (rgb.g << 8) | rgb.b;
}

int mccSymbol(ivec2 position);

bool mccFullscreenTexture() {
    return textureSize(Sampler0, 0) == ivec2(128, 128)
        && (mccSymbol(ivec2(127, 15)) == 7 || mccSymbol(ivec2(127, 15)) == 11);
}

bool mccFilmTexture() {
    if (textureSize(Sampler0, 0) != ivec2(128, 128)) return false;
    for (int i = 0; i < 7; i++) {
        ivec2 position = ivec2(i % 8, i / 8) * 16 + ivec2(15);
        if (mccPackedRgb(mccRgb(position)) != MCC_HEADER_RGB[i]) return false;
    }
    int version = mccSymbol(ivec2(127, 15));
    if (version == 5) {
        for (int i = 8; i < 16; i++) {
            ivec2 position = ivec2(i % 8, i / 8) * 16 + ivec2(15);
            if (mccPackedRgb(mccRgb(position)) != MCC_HEADER_RGB[i]) return false;
        }
        return true;
    }
    // Keep this header check identical to mcc_film.glsl.
    int frameKind = mccSymbol(ivec2(127, 31));
    return mccSymbol(ivec2(79, 31)) == 26
        && mccSymbol(ivec2(95, 31)) == 3
        && ((version == 7 && mccSymbol(ivec2(111, 31)) == 7 && frameKind == 98)
            || (version == 11 && mccSymbol(ivec2(111, 31)) == 11
                && (frameKind == 99 || frameKind == 100)));
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
