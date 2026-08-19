#include <jni.h>
#include <android/bitmap.h>
#include <android/log.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <limits>
#include <string>
#include <sys/mman.h>
#include <sys/resource.h>
#include <sys/stat.h>
#include <thread>
#include <unistd.h>
#include <vector>

namespace {

constexpr int kFullScale = 65535;
constexpr int kHalfQ16 = 32768;
constexpr int kNormShift = 20;
constexpr int kLutSize = 256;
constexpr int kLutShift = 8;
constexpr int kCfaPeriod = 2;
constexpr int kAlignmentBorder = 64;
constexpr int kCoarseRadius = 12;
constexpr int kRefineRadius = 2;
constexpr int kCoarseStep = 64;
constexpr int kRefineStep = 24;
constexpr int kMinAlignmentSamples = 72;
constexpr float kHardRejectConfidence = 0.07f;
constexpr float kMediumConfidence = 0.30f;
constexpr int kSourceClip = 64200;
constexpr int kSourceBlack = 120;
constexpr char kLogTag[] = "OmniCamNativeRaw";

inline int clampInt(int value, int low, int high) {
    return std::min(high, std::max(low, value));
}

inline float clampFloat(float value, float low, float high) {
    return std::min(high, std::max(low, value));
}

inline int even(int value) {
    return value - (value & 1);
}

inline int cfaIndex(int x, int y) {
    return ((y & 1) << 1) | (x & 1);
}

struct RawFrame {
    int fd = -1;
    size_t bytes = 0;
    const uint16_t* pixels = nullptr;
    int width = 0;
    int height = 0;
    std::array<int, 4> black{};
    std::array<int, 4> range{};
    std::array<int64_t, 4> normMulQ20{};
    int white = kFullScale;
    long long exposureNs = 1;
    int iso = 100;
    double exposureProduct = 1.0;
    int scaleToReferenceQ16 = 65536;
    bool sameExposure = true;
    std::array<std::array<int, kLutSize>, 4> motionThreshold{};
};

struct Alignment {
    int dx = 0;
    int dy = 0;
    float confidence = 0.0f;
};

struct SearchResult {
    int dx = 0;
    int dy = 0;
    long long score = std::numeric_limits<long long>::max();
    long long secondScore = std::numeric_limits<long long>::max();
    int samples = 0;
};

inline int normalized(const RawFrame& frame, int x, int y) {
    const int cfa = cfaIndex(x, y);
    const int raw = frame.pixels[static_cast<size_t>(y) * frame.width + x];
    const int signal = std::max(0, raw - frame.black[cfa]);
    const int value = static_cast<int>((static_cast<int64_t>(signal) * frame.normMulQ20[cfa]) >> kNormShift);
    return clampInt(value, 0, kFullScale);
}

inline int encodeReference(const RawFrame& frame, int normalizedValue, int x, int y) {
    const int cfa = cfaIndex(x, y);
    const int signal = static_cast<int>(
        (static_cast<int64_t>(normalizedValue) * frame.range[cfa] + kHalfQ16) >> 16
    );
    return clampInt(frame.black[cfa] + signal, 0, 65535);
}

inline int blockLuma(const RawFrame& frame, int x, int y) {
    return (
        normalized(frame, x, y) +
        normalized(frame, x + 1, y) +
        normalized(frame, x, y + 1) +
        normalized(frame, x + 1, y + 1)
    ) >> 2;
}

SearchResult searchTranslation(
    const RawFrame& reference,
    const RawFrame& candidate,
    int centerDx,
    int centerDy,
    int radius,
    int sampleStep
) {
    SearchResult out;
    out.dx = even(centerDx);
    out.dy = even(centerDy);

    int dy = even(centerDy - radius);
    const int maxDy = even(centerDy + radius);
    while (dy <= maxDy) {
        int dx = even(centerDx - radius);
        const int maxDx = even(centerDx + radius);
        while (dx <= maxDx) {
            long long error = 0;
            int samples = 0;
            for (int y = even(kAlignmentBorder); y < reference.height - kAlignmentBorder - 2; y += sampleStep) {
                for (int x = even(kAlignmentBorder); x < reference.width - kAlignmentBorder - 2; x += sampleStep) {
                    const int cx = x + dx;
                    const int cy = y + dy;
                    if (cx < 0 || cy < 0 || cx + 1 >= candidate.width || cy + 1 >= candidate.height) {
                        continue;
                    }
                    const int a = blockLuma(reference, x, y);
                    const int source = blockLuma(candidate, cx, cy);
                    const int b = static_cast<int>(
                        (static_cast<int64_t>(source) * candidate.scaleToReferenceQ16 + kHalfQ16) >> 16
                    );
                    if (a < 650 || a > 62000 || b < 650 || b > 62000) {
                        continue;
                    }
                    error += std::abs(a - b);
                    ++samples;
                }
            }
            const long long score = samples >= kMinAlignmentSamples
                ? error / samples
                : std::numeric_limits<long long>::max();
            if (score < out.score) {
                out.secondScore = out.score;
                out.score = score;
                out.dx = dx;
                out.dy = dy;
                out.samples = samples;
            } else if (score < out.secondScore) {
                out.secondScore = score;
            }
            dx += kCfaPeriod;
        }
        dy += kCfaPeriod;
    }
    return out;
}

Alignment estimateTranslation(const RawFrame& reference, const RawFrame& candidate) {
    const SearchResult coarse = searchTranslation(
        reference, candidate, 0, 0, kCoarseRadius, kCoarseStep
    );
    const SearchResult refined = searchTranslation(
        reference, candidate, coarse.dx, coarse.dy, kRefineRadius, kRefineStep
    );
    if (refined.samples <= 0 || refined.score == std::numeric_limits<long long>::max()) {
        return Alignment{refined.dx, refined.dy, 0.0f};
    }

    const double meanResidual = static_cast<double>(refined.score) / static_cast<double>(kFullScale);
    const double residualConfidence = std::clamp(1.0 - meanResidual / 0.10, 0.0, 1.0);
    double separation = 0.0;
    if (refined.secondScore > 0 && refined.secondScore < std::numeric_limits<long long>::max()) {
        separation = std::clamp(
            static_cast<double>(refined.secondScore - refined.score) /
                static_cast<double>(refined.secondScore),
            0.0,
            1.0
        );
    }
    const float confidence = static_cast<float>(
        std::clamp(std::max(residualConfidence, separation * 1.35), 0.0, 1.0)
    );
    return Alignment{refined.dx, refined.dy, confidence};
}

void closeFrames(std::vector<RawFrame>& frames) {
    for (auto& frame : frames) {
        if (frame.pixels != nullptr && frame.bytes > 0) {
            munmap(const_cast<uint16_t*>(frame.pixels), frame.bytes);
            frame.pixels = nullptr;
        }
        if (frame.fd >= 0) {
            close(frame.fd);
            frame.fd = -1;
        }
    }
}

bool mapFrames(
    JNIEnv* env,
    jobjectArray paths,
    int width,
    int height,
    const jlong* exposureNs,
    const jint* iso,
    const jint* black,
    const jint* white,
    std::vector<RawFrame>& frames
) {
    const jsize count = env->GetArrayLength(paths);
    const size_t expectedBytes = static_cast<size_t>(width) * height * sizeof(uint16_t);
    frames.reserve(count);
    for (jsize i = 0; i < count; ++i) {
        auto pathString = static_cast<jstring>(env->GetObjectArrayElement(paths, i));
        if (pathString == nullptr) {
            closeFrames(frames);
            return false;
        }
        const char* utf = env->GetStringUTFChars(pathString, nullptr);
        std::string path = utf != nullptr ? utf : "";
        if (utf != nullptr) env->ReleaseStringUTFChars(pathString, utf);
        env->DeleteLocalRef(pathString);

        RawFrame frame;
        frame.width = width;
        frame.height = height;
        frame.exposureNs = std::max<long long>(1, exposureNs[i]);
        frame.iso = std::max(1, iso[i]);
        frame.exposureProduct = static_cast<double>(frame.exposureNs) * frame.iso;
        frame.white = std::max(1, white[i]);
        for (int c = 0; c < 4; ++c) {
            frame.black[c] = std::max(0, black[i * 4 + c]);
            frame.range[c] = std::max(1, frame.white - frame.black[c]);
            frame.normMulQ20[c] =
                (static_cast<int64_t>(kFullScale) << kNormShift) / frame.range[c];
        }

        frame.fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
        if (frame.fd < 0) {
            closeFrames(frames);
            return false;
        }
        struct stat st{};
        if (fstat(frame.fd, &st) != 0 || static_cast<size_t>(st.st_size) < expectedBytes) {
            close(frame.fd);
            frame.fd = -1;
            closeFrames(frames);
            return false;
        }
        frame.bytes = expectedBytes;
        void* mapped = mmap(nullptr, expectedBytes, PROT_READ, MAP_SHARED, frame.fd, 0);
        if (mapped == MAP_FAILED) {
            close(frame.fd);
            frame.fd = -1;
            closeFrames(frames);
            return false;
        }
        frame.pixels = static_cast<const uint16_t*>(mapped);
        frames.push_back(frame);
    }
    return true;
}

inline double noiseVariance(float slope, float offset, double signal) {
    if (slope >= 0.0f && offset >= 0.0f && (slope > 0.0f || offset > 0.0f)) {
        return std::max(1e-9, static_cast<double>(slope) * std::max(0.0, signal) + offset);
    }
    return std::max(1e-9, 0.000025 + 0.0016 * std::max(0.0, signal));
}

void buildThresholds(
    std::vector<RawFrame>& frames,
    int referenceIndex,
    const jfloat* slopes,
    const jfloat* offsets,
    float denoiseStrength
) {
    RawFrame& reference = frames[referenceIndex];
    const double refProduct = reference.exposureProduct;
    const float strength = clampFloat(denoiseStrength, 0.0f, 1.0f);
    const double sigmaMultiplier = 3.2 + 1.9 * strength;
    const double brightAllowance = 0.025 + 0.025 * strength;

    for (size_t i = 0; i < frames.size(); ++i) {
        RawFrame& frame = frames[i];
        const double scale = std::clamp(refProduct / std::max(1.0, frame.exposureProduct), 0.125, 16.0);
        frame.scaleToReferenceQ16 = std::max(1, static_cast<int>(std::lround(scale * 65536.0)));
        frame.sameExposure = scale >= 0.82 && scale <= 1.22;
        for (int c = 0; c < 4; ++c) {
            const float refSlope = slopes[referenceIndex * 4 + c];
            const float refOffset = offsets[referenceIndex * 4 + c];
            const float srcSlope = slopes[i * 4 + c];
            const float srcOffset = offsets[i * 4 + c];
            for (int bucket = 0; bucket < kLutSize; ++bucket) {
                const double refSignal = static_cast<double>(bucket) / (kLutSize - 1);
                const double srcSignal = std::clamp(refSignal / std::max(0.125, scale), 0.0, 1.0);
                const double variance =
                    noiseVariance(refSlope, refOffset, refSignal) +
                    noiseVariance(srcSlope, srcOffset, srcSignal) * scale * scale;
                const double sigma = std::sqrt(std::max(1e-9, variance));
                const double normalizedThreshold = std::max(
                    0.015 + 0.004 * strength,
                    sigmaMultiplier * sigma + refSignal * brightAllowance
                );
                frame.motionThreshold[c][bucket] = clampInt(
                    static_cast<int>(std::lround(normalizedThreshold * kFullScale)),
                    800,
                    16000
                );
            }
        }
    }
}

int chooseReference(const jlong* exposureNs, const jint* iso, int count) {
    int best = 0;
    long double bestProduct = -1.0;
    for (int i = 0; i < count; ++i) {
        const long double product =
            static_cast<long double>(std::max<jlong>(1, exposureNs[i])) *
            static_cast<long double>(std::max<jint>(1, iso[i]));
        if (product > bestProduct) {
            bestProduct = product;
            best = i;
        }
    }
    return best;
}

// ----- Fast RAW-to-display renderer -----

struct Pixel {
    uint8_t r;
    uint8_t g;
    uint8_t b;
    uint8_t a;
};

struct RenderContext {
    const uint16_t* raw = nullptr;
    int width = 0;
    int height = 0;
    std::array<int, 4> black{};
    std::array<int, 4> range{};
    std::array<float, 4> wb{};
    std::array<float, 9> matrix{};
    int cfaArrangement = 0;
};

inline int colorAt(int arrangement, int x, int y) {
    const int idx = cfaIndex(x, y);
    // 0=R, 1=G, 2=B. Android Bayer arrangement constants: RGGB=0, GRBG=1,
    // GBRG=2, BGGR=3.
    switch (arrangement) {
        case 1: { // GRBG
            static constexpr int map[4] = {1, 0, 2, 1};
            return map[idx];
        }
        case 2: { // GBRG
            static constexpr int map[4] = {1, 2, 0, 1};
            return map[idx];
        }
        case 3: { // BGGR
            static constexpr int map[4] = {2, 1, 1, 0};
            return map[idx];
        }
        default: { // RGGB
            static constexpr int map[4] = {0, 1, 1, 2};
            return map[idx];
        }
    }
}

inline float wbGain(const RenderContext& ctx, int x, int y) {
    const int color = colorAt(ctx.cfaArrangement, x, y);
    if (color == 0) return ctx.wb[0];
    if (color == 2) return ctx.wb[3];
    return (y & 1) == 0 ? ctx.wb[1] : ctx.wb[2];
}

inline float rawSample(const RenderContext& ctx, int x, int y) {
    x = clampInt(x, 0, ctx.width - 1);
    y = clampInt(y, 0, ctx.height - 1);
    const int cfa = cfaIndex(x, y);
    const int value = ctx.raw[static_cast<size_t>(y) * ctx.width + x];
    const float normalized = static_cast<float>(std::max(0, value - ctx.black[cfa])) /
        static_cast<float>(ctx.range[cfa]);
    return std::max(0.0f, normalized * wbGain(ctx, x, y));
}

float averageMatching(
    const RenderContext& ctx,
    int x,
    int y,
    int targetColor,
    const int* offsets,
    int pairCount
) {
    float sum = 0.0f;
    int count = 0;
    for (int i = 0; i < pairCount; ++i) {
        const int nx = clampInt(x + offsets[i * 2], 0, ctx.width - 1);
        const int ny = clampInt(y + offsets[i * 2 + 1], 0, ctx.height - 1);
        if (colorAt(ctx.cfaArrangement, nx, ny) != targetColor) continue;
        sum += rawSample(ctx, nx, ny);
        ++count;
    }
    return count > 0 ? sum / count : rawSample(ctx, x, y);
}

inline std::array<float, 3> demosaic(const RenderContext& ctx, int x, int y) {
    static constexpr int cross[8] = {-1, 0, 1, 0, 0, -1, 0, 1};
    static constexpr int diag[8] = {-1, -1, 1, -1, -1, 1, 1, 1};
    static constexpr int horizontal[4] = {-1, 0, 1, 0};
    static constexpr int vertical[4] = {0, -1, 0, 1};

    const int color = colorAt(ctx.cfaArrangement, x, y);
    const float center = rawSample(ctx, x, y);
    float r = 0.0f;
    float g = 0.0f;
    float b = 0.0f;
    if (color == 0) {
        r = center;
        g = averageMatching(ctx, x, y, 1, cross, 4);
        b = averageMatching(ctx, x, y, 2, diag, 4);
    } else if (color == 2) {
        b = center;
        g = averageMatching(ctx, x, y, 1, cross, 4);
        r = averageMatching(ctx, x, y, 0, diag, 4);
    } else {
        g = center;
        const int leftX = clampInt(x - 1, 0, ctx.width - 1);
        const int rightX = clampInt(x + 1, 0, ctx.width - 1);
        const bool redHorizontal =
            colorAt(ctx.cfaArrangement, leftX, y) == 0 ||
            colorAt(ctx.cfaArrangement, rightX, y) == 0;
        if (redHorizontal) {
            r = averageMatching(ctx, x, y, 0, horizontal, 2);
            b = averageMatching(ctx, x, y, 2, vertical, 2);
        } else {
            r = averageMatching(ctx, x, y, 0, vertical, 2);
            b = averageMatching(ctx, x, y, 2, horizontal, 2);
        }
    }
    return {r, g, b};
}

inline std::array<float, 3> applyMatrix(const RenderContext& ctx, const std::array<float, 3>& sensor) {
    return {
        std::max(0.0f, ctx.matrix[0] * sensor[0] + ctx.matrix[1] * sensor[1] + ctx.matrix[2] * sensor[2]),
        std::max(0.0f, ctx.matrix[3] * sensor[0] + ctx.matrix[4] * sensor[1] + ctx.matrix[5] * sensor[2]),
        std::max(0.0f, ctx.matrix[6] * sensor[0] + ctx.matrix[7] * sensor[1] + ctx.matrix[8] * sensor[2])
    };
}

inline float luma(const std::array<float, 3>& rgb) {
    return rgb[0] * 0.2126f + rgb[1] * 0.7152f + rgb[2] * 0.0722f;
}

float percentileFromHistogram(const std::array<int, 1024>& hist, int total, float percentile) {
    if (total <= 0) return 0.0f;
    const int target = std::max(1, static_cast<int>(std::lround(total * percentile)));
    int cumulative = 0;
    for (int i = 0; i < static_cast<int>(hist.size()); ++i) {
        cumulative += hist[i];
        if (cumulative >= target) {
            return static_cast<float>(i) / static_cast<float>(hist.size() - 1);
        }
    }
    return 1.0f;
}

inline float toneLuma(float value, float highlightProtection) {
    const float hp = clampFloat(highlightProtection, 0.0f, 1.0f);
    const float knee = 0.72f - 0.17f * hp;
    if (value <= knee) return std::max(0.0f, value);
    const float span = std::max(0.05f, 1.0f - knee);
    const float t = (value - knee) / span;
    const float softness = 1.0f + hp * 3.2f;
    const float compressed = 1.0f - 1.0f / (1.0f + t / softness);
    return clampFloat(knee + span * compressed, 0.0f, 1.0f);
}

inline uint8_t lutSrgb(float linear, const std::array<uint8_t, 4096>& lut) {
    const int index = clampInt(static_cast<int>(linear * 4095.0f + 0.5f), 0, 4095);
    return lut[index];
}

std::array<uint8_t, 4096> buildSrgbLut() {
    std::array<uint8_t, 4096> lut{};
    for (int i = 0; i < 4096; ++i) {
        const float x = static_cast<float>(i) / 4095.0f;
        const float srgb = x <= 0.0031308f
            ? 12.92f * x
            : 1.055f * std::pow(x, 1.0f / 2.4f) - 0.055f;
        lut[i] = static_cast<uint8_t>(clampInt(static_cast<int>(srgb * 255.0f + 0.5f), 0, 255));
    }
    return lut;
}

int workerCountForDevice() {
    const unsigned int hw = std::max(1u, std::thread::hardware_concurrency());
    if (hw <= 2) return 1;
    return std::min(6, std::max(2, static_cast<int>(hw) - 2));
}

void lowerWorkerPriority() {
    // On Android/Linux priority is per-thread for PRIO_PROCESS when who=0.
    setpriority(PRIO_PROCESS, 0, 6);
}

} // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_omnicam_camera_camerax_NativeRawBridge_mergeRaw16(
    JNIEnv* env,
    jclass,
    jobjectArray paths,
    jint width,
    jint height,
    jlongArray exposureTimesNs,
    jintArray isoValues,
    jintArray blackLevels,
    jintArray whiteLevels,
    jfloatArray noiseSlopes,
    jfloatArray noiseOffsets,
    jfloat denoiseStrength,
    jfloat highlightProtection,
    jobject outputRaw16,
    jintArray alignmentOut,
    jlongArray statsOut
) {
    if (paths == nullptr || outputRaw16 == nullptr) return -1;
    const int count = env->GetArrayLength(paths);
    if (count < 3 || width <= 0 || height <= 0) return -2;
    if (env->GetArrayLength(exposureTimesNs) != count ||
        env->GetArrayLength(isoValues) != count ||
        env->GetArrayLength(blackLevels) < count * 4 ||
        env->GetArrayLength(whiteLevels) != count ||
        env->GetArrayLength(noiseSlopes) < count * 4 ||
        env->GetArrayLength(noiseOffsets) < count * 4 ||
        env->GetArrayLength(alignmentOut) < count * 3 ||
        env->GetArrayLength(statsOut) < 3) {
        return -3;
    }

    const jlong* exposure = env->GetLongArrayElements(exposureTimesNs, nullptr);
    const jint* iso = env->GetIntArrayElements(isoValues, nullptr);
    const jint* black = env->GetIntArrayElements(blackLevels, nullptr);
    const jint* white = env->GetIntArrayElements(whiteLevels, nullptr);
    const jfloat* slopes = env->GetFloatArrayElements(noiseSlopes, nullptr);
    const jfloat* offsets = env->GetFloatArrayElements(noiseOffsets, nullptr);
    if (!exposure || !iso || !black || !white || !slopes || !offsets) {
        if (exposure) env->ReleaseLongArrayElements(exposureTimesNs, const_cast<jlong*>(exposure), JNI_ABORT);
        if (iso) env->ReleaseIntArrayElements(isoValues, const_cast<jint*>(iso), JNI_ABORT);
        if (black) env->ReleaseIntArrayElements(blackLevels, const_cast<jint*>(black), JNI_ABORT);
        if (white) env->ReleaseIntArrayElements(whiteLevels, const_cast<jint*>(white), JNI_ABORT);
        if (slopes) env->ReleaseFloatArrayElements(noiseSlopes, const_cast<jfloat*>(slopes), JNI_ABORT);
        if (offsets) env->ReleaseFloatArrayElements(noiseOffsets, const_cast<jfloat*>(offsets), JNI_ABORT);
        return -4;
    }

    std::vector<RawFrame> frames;
    const bool mapped = mapFrames(env, paths, width, height, exposure, iso, black, white, frames);
    if (!mapped) {
        env->ReleaseLongArrayElements(exposureTimesNs, const_cast<jlong*>(exposure), JNI_ABORT);
        env->ReleaseIntArrayElements(isoValues, const_cast<jint*>(iso), JNI_ABORT);
        env->ReleaseIntArrayElements(blackLevels, const_cast<jint*>(black), JNI_ABORT);
        env->ReleaseIntArrayElements(whiteLevels, const_cast<jint*>(white), JNI_ABORT);
        env->ReleaseFloatArrayElements(noiseSlopes, const_cast<jfloat*>(slopes), JNI_ABORT);
        env->ReleaseFloatArrayElements(noiseOffsets, const_cast<jfloat*>(offsets), JNI_ABORT);
        return -5;
    }

    const int referenceIndex = chooseReference(exposure, iso, count);
    buildThresholds(frames, referenceIndex, slopes, offsets, denoiseStrength);

    env->ReleaseLongArrayElements(exposureTimesNs, const_cast<jlong*>(exposure), JNI_ABORT);
    env->ReleaseIntArrayElements(isoValues, const_cast<jint*>(iso), JNI_ABORT);
    env->ReleaseIntArrayElements(blackLevels, const_cast<jint*>(black), JNI_ABORT);
    env->ReleaseIntArrayElements(whiteLevels, const_cast<jint*>(white), JNI_ABORT);
    env->ReleaseFloatArrayElements(noiseSlopes, const_cast<jfloat*>(slopes), JNI_ABORT);
    env->ReleaseFloatArrayElements(noiseOffsets, const_cast<jfloat*>(offsets), JNI_ABORT);

    auto* output = static_cast<uint16_t*>(env->GetDirectBufferAddress(outputRaw16));
    const jlong outputCapacity = env->GetDirectBufferCapacity(outputRaw16);
    const size_t requiredBytes = static_cast<size_t>(width) * height * sizeof(uint16_t);
    if (output == nullptr || outputCapacity < static_cast<jlong>(requiredBytes)) {
        closeFrames(frames);
        return -6;
    }

    std::vector<Alignment> alignments(count);
    alignments[referenceIndex] = Alignment{0, 0, 1.0f};
    for (int i = 0; i < count; ++i) {
        if (i == referenceIndex) continue;
        alignments[i] = estimateTranslation(frames[referenceIndex], frames[i]);
    }

    const int workers = std::min(workerCountForDevice(), height);
    std::vector<std::thread> threads;
    std::vector<unsigned long long> acceptedByWorker(workers, 0);
    std::vector<unsigned long long> rejectedByWorker(workers, 0);
    const int rowsPerWorker = (height + workers - 1) / workers;
    const float denoise = clampFloat(denoiseStrength, 0.0f, 1.0f);
    const float highlights = clampFloat(highlightProtection, 0.0f, 1.0f);
    const int referenceWeight = clampInt(static_cast<int>(std::lround(5.0f - 2.8f * denoise)), 2, 5);
    const int sameExposureWeight = 4;
    const int bracketWeight = highlights >= 0.65f ? 2 : 1;
    const int highlightUseThreshold = clampInt(
        static_cast<int>(56000.0f - highlights * 13000.0f),
        42000,
        56000
    );

    for (int worker = 0; worker < workers; ++worker) {
        const int startY = worker * rowsPerWorker;
        const int endY = std::min(height, startY + rowsPerWorker);
        if (startY >= endY) continue;
        threads.emplace_back([&, worker, startY, endY]() {
            lowerWorkerPriority();
            unsigned long long accepted = 0;
            unsigned long long rejected = 0;
            const RawFrame& reference = frames[referenceIndex];
            for (int y = startY; y < endY; ++y) {
                for (int x = 0; x < width; ++x) {
                    const int ref = normalized(reference, x, y);
                    int64_t weighted = static_cast<int64_t>(ref) * referenceWeight;
                    int weightSum = referenceWeight;
                    const int cfa = cfaIndex(x, y);
                    const int bucket = clampInt(ref >> kLutShift, 0, kLutSize - 1);

                    for (int i = 0; i < count; ++i) {
                        if (i == referenceIndex) continue;
                        const RawFrame& candidate = frames[i];
                        const Alignment& alignment = alignments[i];
                        if (alignment.confidence < kHardRejectConfidence) {
                            ++rejected;
                            continue;
                        }
                        const int sx = x + alignment.dx;
                        const int sy = y + alignment.dy;
                        if (sx < 0 || sy < 0 || sx >= width || sy >= height) {
                            ++rejected;
                            continue;
                        }
                        if (!candidate.sameExposure && ref < highlightUseThreshold) {
                            continue;
                        }

                        const int source = normalized(candidate, sx, sy);
                        if (source >= kSourceClip) continue;
                        int normalizedCandidate = static_cast<int>(
                            (static_cast<int64_t>(source) * candidate.scaleToReferenceQ16 + kHalfQ16) >> 16
                        );
                        normalizedCandidate = clampInt(normalizedCandidate, 0, kFullScale * 2);
                        const int delta = std::abs(normalizedCandidate - ref);
                        int threshold = candidate.motionThreshold[cfa][bucket];
                        if (alignment.confidence < kMediumConfidence) {
                            threshold = threshold * 3 / 4;
                        }
                        if (delta > threshold) {
                            ++rejected;
                            continue;
                        }

                        int weight = candidate.sameExposure ? sameExposureWeight : bracketWeight;
                        if (alignment.confidence < kMediumConfidence) weight = std::max(1, weight / 2);
                        if (delta > threshold * 2 / 3) weight = std::max(1, weight / 2);
                        if (source < kSourceBlack) weight = std::max(1, weight / 2);
                        weighted += static_cast<int64_t>(normalizedCandidate) * weight;
                        weightSum += weight;
                        ++accepted;
                    }

                    const int merged = clampInt(static_cast<int>(weighted / std::max(1, weightSum)), 0, kFullScale);
                    output[static_cast<size_t>(y) * width + x] = static_cast<uint16_t>(
                        encodeReference(reference, merged, x, y)
                    );
                }
            }
            acceptedByWorker[worker] = accepted;
            rejectedByWorker[worker] = rejected;
        });
    }
    for (auto& thread : threads) thread.join();

    unsigned long long accepted = 0;
    unsigned long long rejected = 0;
    for (int i = 0; i < workers; ++i) {
        accepted += acceptedByWorker[i];
        rejected += rejectedByWorker[i];
    }

    std::vector<jint> alignmentFlat(static_cast<size_t>(count) * 3);
    for (int i = 0; i < count; ++i) {
        alignmentFlat[i * 3] = alignments[i].dx;
        alignmentFlat[i * 3 + 1] = alignments[i].dy;
        alignmentFlat[i * 3 + 2] = clampInt(
            static_cast<int>(std::lround(alignments[i].confidence * 32767.0f)),
            0,
            32767
        );
    }
    env->SetIntArrayRegion(alignmentOut, 0, static_cast<jsize>(alignmentFlat.size()), alignmentFlat.data());
    const jlong stats[3] = {
        static_cast<jlong>(accepted),
        static_cast<jlong>(rejected),
        static_cast<jlong>(referenceIndex)
    };
    env->SetLongArrayRegion(statsOut, 0, 3, stats);

    closeFrames(frames);
    return 0;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_omnicam_camera_camerax_NativeRawBridge_renderEnhanced(
    JNIEnv* env,
    jclass,
    jobject mergedRaw16,
    jint width,
    jint height,
    jintArray blackLevels,
    jint whiteLevel,
    jint cfaArrangement,
    jfloatArray whiteBalance,
    jfloatArray colorMatrix,
    jfloat highlightProtection,
    jfloat denoiseStrength,
    jobject outputBitmap
) {
    if (mergedRaw16 == nullptr || outputBitmap == nullptr || width <= 2 || height <= 2) return -1;
    auto* raw = static_cast<const uint16_t*>(env->GetDirectBufferAddress(mergedRaw16));
    const jlong rawCapacity = env->GetDirectBufferCapacity(mergedRaw16);
    const size_t rawBytes = static_cast<size_t>(width) * height * sizeof(uint16_t);
    if (raw == nullptr || rawCapacity < static_cast<jlong>(rawBytes)) return -2;
    if (env->GetArrayLength(blackLevels) < 4 ||
        env->GetArrayLength(whiteBalance) < 4 ||
        env->GetArrayLength(colorMatrix) < 9) return -3;

    AndroidBitmapInfo info{};
    if (AndroidBitmap_getInfo(env, outputBitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width == 0 || info.height == 0) {
        return -4;
    }

    const jint* black = env->GetIntArrayElements(blackLevels, nullptr);
    const jfloat* wb = env->GetFloatArrayElements(whiteBalance, nullptr);
    const jfloat* matrix = env->GetFloatArrayElements(colorMatrix, nullptr);
    if (!black || !wb || !matrix) {
        if (black) env->ReleaseIntArrayElements(blackLevels, const_cast<jint*>(black), JNI_ABORT);
        if (wb) env->ReleaseFloatArrayElements(whiteBalance, const_cast<jfloat*>(wb), JNI_ABORT);
        if (matrix) env->ReleaseFloatArrayElements(colorMatrix, const_cast<jfloat*>(matrix), JNI_ABORT);
        return -5;
    }

    RenderContext ctx;
    ctx.raw = raw;
    ctx.width = width;
    ctx.height = height;
    ctx.cfaArrangement = cfaArrangement;
    for (int i = 0; i < 4; ++i) {
        ctx.black[i] = std::max(0, black[i]);
        ctx.range[i] = std::max(1, static_cast<int>(whiteLevel) - ctx.black[i]);
        ctx.wb[i] = clampFloat(wb[i], 0.05f, 12.0f);
    }
    for (int i = 0; i < 9; ++i) ctx.matrix[i] = clampFloat(matrix[i], -3.0f, 4.0f);
    env->ReleaseIntArrayElements(blackLevels, const_cast<jint*>(black), JNI_ABORT);
    env->ReleaseFloatArrayElements(whiteBalance, const_cast<jfloat*>(wb), JNI_ABORT);
    env->ReleaseFloatArrayElements(colorMatrix, const_cast<jfloat*>(matrix), JNI_ABORT);

    std::array<int, 1024> histogram{};
    int histogramSamples = 0;
    const int sampleStep = std::max(16, std::min(width, height) / 96);
    for (int y = sampleStep; y < height - sampleStep; y += sampleStep) {
        for (int x = sampleStep; x < width - sampleStep; x += sampleStep) {
            const auto rgb = applyMatrix(ctx, demosaic(ctx, x, y));
            const float value = clampFloat(luma(rgb), 0.0f, 1.0f);
            const int bucket = clampInt(static_cast<int>(value * 1023.0f + 0.5f), 0, 1023);
            ++histogram[bucket];
            ++histogramSamples;
        }
    }
    const float p50 = std::max(0.004f, percentileFromHistogram(histogram, histogramSamples, 0.50f));
    const float p99 = std::max(0.02f, percentileFromHistogram(histogram, histogramSamples, 0.99f));
    const float hp = clampFloat(highlightProtection, 0.0f, 1.0f);
    const float midExposure = 0.21f / p50;
    const float highlightTarget = 1.32f - hp * 0.25f;
    const float highlightExposure = highlightTarget / p99;
    const float exposure = clampFloat(
        std::min(midExposure, highlightExposure * (1.55f - hp * 0.20f)),
        0.30f,
        6.0f
    );

    const auto srgbLut = buildSrgbLut();
    std::vector<Pixel> base(static_cast<size_t>(width) * height);
    const int workers = std::min(workerCountForDevice(), height);
    const int rowsPerWorker = (height + workers - 1) / workers;
    std::vector<std::thread> renderThreads;
    const float saturation = 1.06f + clampFloat(denoiseStrength, 0.0f, 1.0f) * 0.04f;

    for (int worker = 0; worker < workers; ++worker) {
        const int startY = worker * rowsPerWorker;
        const int endY = std::min(height, startY + rowsPerWorker);
        if (startY >= endY) continue;
        renderThreads.emplace_back([&, startY, endY]() {
            lowerWorkerPriority();
            for (int y = startY; y < endY; ++y) {
                for (int x = 0; x < width; ++x) {
                    auto rgb = applyMatrix(ctx, demosaic(ctx, x, y));
                    rgb[0] *= exposure;
                    rgb[1] *= exposure;
                    rgb[2] *= exposure;
                    const float before = std::max(1e-7f, luma(rgb));
                    const float after = toneLuma(before, hp);
                    const float toneScale = after / before;
                    rgb[0] *= toneScale;
                    rgb[1] *= toneScale;
                    rgb[2] *= toneScale;
                    const float lum = luma(rgb);
                    rgb[0] = lum + (rgb[0] - lum) * saturation;
                    rgb[1] = lum + (rgb[1] - lum) * saturation;
                    rgb[2] = lum + (rgb[2] - lum) * saturation;
                    Pixel& p = base[static_cast<size_t>(y) * width + x];
                    p.r = lutSrgb(clampFloat(rgb[0], 0.0f, 1.0f), srgbLut);
                    p.g = lutSrgb(clampFloat(rgb[1], 0.0f, 1.0f), srgbLut);
                    p.b = lutSrgb(clampFloat(rgb[2], 0.0f, 1.0f), srgbLut);
                    p.a = 255;
                }
            }
        });
    }
    for (auto& thread : renderThreads) thread.join();

    void* bitmapPixels = nullptr;
    if (AndroidBitmap_lockPixels(env, outputBitmap, &bitmapPixels) != ANDROID_BITMAP_RESULT_SUCCESS ||
        bitmapPixels == nullptr) {
        return -6;
    }

    const int outWidth = static_cast<int>(info.width);
    const int outHeight = static_cast<int>(info.height);
    const float scaleX = static_cast<float>(width) / static_cast<float>(outWidth);
    const float scaleY = static_cast<float>(height) / static_cast<float>(outHeight);
    const float detailBoost = 0.10f + clampFloat(denoiseStrength, 0.0f, 1.0f) * 0.06f;
    const int outWorkers = std::min(workerCountForDevice(), outHeight);
    const int outRows = (outHeight + outWorkers - 1) / outWorkers;
    std::vector<std::thread> upscaleThreads;
    auto* destination = static_cast<uint8_t*>(bitmapPixels);

    for (int worker = 0; worker < outWorkers; ++worker) {
        const int startY = worker * outRows;
        const int endY = std::min(outHeight, startY + outRows);
        if (startY >= endY) continue;
        upscaleThreads.emplace_back([&, startY, endY]() {
            lowerWorkerPriority();
            for (int oy = startY; oy < endY; ++oy) {
                Pixel* dst = reinterpret_cast<Pixel*>(destination + static_cast<size_t>(oy) * info.stride);
                const float sy = (oy + 0.5f) * scaleY - 0.5f;
                const int y0 = clampInt(static_cast<int>(std::floor(sy)), 0, height - 1);
                const int y1 = clampInt(y0 + 1, 0, height - 1);
                const float fy = clampFloat(sy - std::floor(sy), 0.0f, 1.0f);
                for (int ox = 0; ox < outWidth; ++ox) {
                    const float sx = (ox + 0.5f) * scaleX - 0.5f;
                    const int x0 = clampInt(static_cast<int>(std::floor(sx)), 0, width - 1);
                    const int x1 = clampInt(x0 + 1, 0, width - 1);
                    const float fx = clampFloat(sx - std::floor(sx), 0.0f, 1.0f);
                    const Pixel& p00 = base[static_cast<size_t>(y0) * width + x0];
                    const Pixel& p10 = base[static_cast<size_t>(y0) * width + x1];
                    const Pixel& p01 = base[static_cast<size_t>(y1) * width + x0];
                    const Pixel& p11 = base[static_cast<size_t>(y1) * width + x1];
                    const int cx = clampInt(static_cast<int>(std::lround(sx)), 0, width - 1);
                    const int cy = clampInt(static_cast<int>(std::lround(sy)), 0, height - 1);
                    const Pixel& center = base[static_cast<size_t>(cy) * width + cx];

                    auto interpolate = [&](uint8_t a, uint8_t b, uint8_t c, uint8_t d, uint8_t centerValue) -> uint8_t {
                        const float top = a + (b - a) * fx;
                        const float bottom = c + (d - c) * fx;
                        const float bilinear = top + (bottom - top) * fy;
                        const float sharpened = bilinear + (static_cast<float>(centerValue) - bilinear) * detailBoost;
                        return static_cast<uint8_t>(clampInt(static_cast<int>(sharpened + 0.5f), 0, 255));
                    };

                    dst[ox] = Pixel{
                        interpolate(p00.r, p10.r, p01.r, p11.r, center.r),
                        interpolate(p00.g, p10.g, p01.g, p11.g, center.g),
                        interpolate(p00.b, p10.b, p01.b, p11.b, center.b),
                        255
                    };
                }
            }
        });
    }
    for (auto& thread : upscaleThreads) thread.join();
    AndroidBitmap_unlockPixels(env, outputBitmap);
    return 0;
}
