#include <jni.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <thread>
#include <vector>

namespace {

inline int clampInt(int value, int low, int high) {
    return std::min(high, std::max(low, value));
}

inline float clampFloat(float value, float low, float high) {
    return std::min(high, std::max(low, value));
}

inline int evenFloor(int value) {
    return value & ~1;
}

inline int workerCountForRows(int rows) {
    const unsigned int hw = std::thread::hardware_concurrency();
    const int wanted = hw == 0 ? 4 : static_cast<int>(hw);
    return std::max(1, std::min({wanted, rows, 8}));
}

inline uint16_t samplePlaneBilinear(
    const uint16_t* input,
    int width,
    int height,
    int cropLeft,
    int cropTop,
    int cropWidth,
    int cropHeight,
    int outX,
    int outY
) {
    const int parityX = outX & 1;
    const int parityY = outY & 1;
    const int outPlaneX = outX >> 1;
    const int outPlaneY = outY >> 1;
    const int outPlaneWidth = std::max(1, width >> 1);
    const int outPlaneHeight = std::max(1, height >> 1);
    const int cropPlaneWidth = std::max(1, cropWidth >> 1);
    const int cropPlaneHeight = std::max(1, cropHeight >> 1);

    const float sourcePlaneX =
        ((static_cast<float>(outPlaneX) + 0.5f) * cropPlaneWidth / outPlaneWidth) - 0.5f;
    const float sourcePlaneY =
        ((static_cast<float>(outPlaneY) + 0.5f) * cropPlaneHeight / outPlaneHeight) - 0.5f;

    const int x0p = clampInt(static_cast<int>(std::floor(sourcePlaneX)), 0, cropPlaneWidth - 1);
    const int y0p = clampInt(static_cast<int>(std::floor(sourcePlaneY)), 0, cropPlaneHeight - 1);
    const int x1p = clampInt(x0p + 1, 0, cropPlaneWidth - 1);
    const int y1p = clampInt(y0p + 1, 0, cropPlaneHeight - 1);
    const float fx = clampFloat(sourcePlaneX - std::floor(sourcePlaneX), 0.0f, 1.0f);
    const float fy = clampFloat(sourcePlaneY - std::floor(sourcePlaneY), 0.0f, 1.0f);

    auto sample = [&](int planeX, int planeY) -> float {
        const int sx = clampInt(cropLeft + (planeX << 1) + parityX, 0, width - 1);
        const int sy = clampInt(cropTop + (planeY << 1) + parityY, 0, height - 1);
        return static_cast<float>(input[static_cast<size_t>(sy) * width + sx]);
    };

    const float p00 = sample(x0p, y0p);
    const float p10 = sample(x1p, y0p);
    const float p01 = sample(x0p, y1p);
    const float p11 = sample(x1p, y1p);
    const float top = p00 + (p10 - p00) * fx;
    const float bottom = p01 + (p11 - p01) * fx;
    const float value = top + (bottom - top) * fy;
    return static_cast<uint16_t>(clampInt(static_cast<int>(std::lround(value)), 0, 65535));
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_omnicam_camera_camerax_NativeRawBridge_resampleRawZoom(
    JNIEnv* env,
    jclass,
    jobject inputRaw16,
    jint width,
    jint height,
    jfloat zoomRatio,
    jobject outputRaw16
) {
    if (inputRaw16 == nullptr || outputRaw16 == nullptr || width < 4 || height < 4) return -1;
    auto* input = static_cast<const uint16_t*>(env->GetDirectBufferAddress(inputRaw16));
    auto* output = static_cast<uint16_t*>(env->GetDirectBufferAddress(outputRaw16));
    const jlong inputCapacity = env->GetDirectBufferCapacity(inputRaw16);
    const jlong outputCapacity = env->GetDirectBufferCapacity(outputRaw16);
    const size_t bytes = static_cast<size_t>(width) * height * sizeof(uint16_t);
    if (input == nullptr || output == nullptr || inputCapacity < static_cast<jlong>(bytes) ||
        outputCapacity < static_cast<jlong>(bytes)) {
        return -2;
    }

    const float zoom = clampFloat(zoomRatio, 1.0f, 8.0f);
    if (zoom <= 1.0005f) {
        std::memcpy(output, input, bytes);
        return 0;
    }

    int cropWidth = evenFloor(static_cast<int>(std::floor(width / zoom)));
    int cropHeight = evenFloor(static_cast<int>(std::floor(height / zoom)));
    cropWidth = std::max(4, std::min(evenFloor(width), cropWidth));
    cropHeight = std::max(4, std::min(evenFloor(height), cropHeight));
    const int cropLeft = evenFloor((width - cropWidth) / 2);
    const int cropTop = evenFloor((height - cropHeight) / 2);

    const int workers = workerCountForRows(height);
    const int rowsPerWorker = (height + workers - 1) / workers;
    std::vector<std::thread> threads;
    threads.reserve(workers);
    for (int worker = 0; worker < workers; ++worker) {
        const int startY = worker * rowsPerWorker;
        const int endY = std::min(height, startY + rowsPerWorker);
        if (startY >= endY) continue;
        threads.emplace_back([=]() {
            for (int y = startY; y < endY; ++y) {
                uint16_t* row = output + static_cast<size_t>(y) * width;
                for (int x = 0; x < width; ++x) {
                    row[x] = samplePlaneBilinear(
                        input,
                        width,
                        height,
                        cropLeft,
                        cropTop,
                        cropWidth,
                        cropHeight,
                        x,
                        y
                    );
                }
            }
        });
    }
    for (auto& thread : threads) thread.join();
    return 0;
}
