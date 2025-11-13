#include <jni.h>
#include <opencv2/imgproc.hpp>
#include <opencv2/core.hpp>
#include <android/log.h>
#include <chrono>
#include <memory>

namespace {
constexpr const char* kTag = "EdgeProcessor";

struct ProcessorContext {
    cv::Mat bgr;
    cv::Mat gray;
    cv::Mat edges;
};

inline void logError(const char* message) {
    __android_log_print(ANDROID_LOG_ERROR, kTag, "%s", message);
}

inline ProcessorContext* fromHandle(jlong handle) {
    return reinterpret_cast<ProcessorContext*>(handle);
}
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_rtedge_nativebridge_EdgeProcessor_nativeCreate(JNIEnv*, jobject) {
    auto* context = new (std::nothrow) ProcessorContext();
    return reinterpret_cast<jlong>(context);
}

JNIEXPORT void JNICALL
Java_com_example_rtedge_nativebridge_EdgeProcessor_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    if (auto* context = fromHandle(handle)) {
        delete context;
    }
}

JNIEXPORT jfloat JNICALL
Java_com_example_rtedge_nativebridge_EdgeProcessor_nativeProcess(
        JNIEnv* env,
        jobject,
        jlong handle,
        jbyteArray nv21Array,
        jint width,
        jint height,
        jobject outputBuffer,
        jboolean edgesOnly) {
    auto* context = fromHandle(handle);
    if (context == nullptr) {
        logError("Processor context is null.");
        return 0.f;
    }

    if (outputBuffer == nullptr) {
        logError("Output buffer is null.");
        return 0.f;
    }

    auto* outputPtr = static_cast<uint8_t*>(env->GetDirectBufferAddress(outputBuffer));
    if (outputPtr == nullptr) {
        logError("Failed to get output buffer address.");
        return 0.f;
    }

    const int totalBytes = env->GetArrayLength(nv21Array);
    jbyte* nv21Ptr = env->GetByteArrayElements(nv21Array, nullptr);
    if (nv21Ptr == nullptr) {
        logError("Failed to map NV21 array.");
        return 0.f;
    }

    const auto start = std::chrono::steady_clock::now();

    cv::Mat nv21(height + height / 2, width, CV_8UC1, reinterpret_cast<uint8_t*>(nv21Ptr), width);

    if (context->bgr.empty() || context->bgr.cols != width || context->bgr.rows != height) {
        context->bgr = cv::Mat(height, width, CV_8UC3);
        context->gray = cv::Mat(height, width, CV_8UC1);
        context->edges = cv::Mat(height, width, CV_8UC1);
    }

    cv::cvtColor(nv21, context->bgr, cv::COLOR_YUV2BGR_NV21);

    if (edgesOnly) {
        cv::cvtColor(context->bgr, context->gray, cv::COLOR_BGR2GRAY);
        cv::GaussianBlur(context->gray, context->gray, cv::Size(5, 5), 1.4);
        cv::Canny(context->gray, context->edges, 50, 150);
        cv::Mat rgba(height, width, CV_8UC4, outputPtr);
        cv::cvtColor(context->edges, rgba, cv::COLOR_GRAY2RGBA);
    } else {
        cv::Mat rgba(height, width, CV_8UC4, outputPtr);
        cv::cvtColor(context->bgr, rgba, cv::COLOR_BGR2RGBA);
    }

    const auto end = std::chrono::steady_clock::now();
    const auto elapsed = std::chrono::duration<float, std::milli>(end - start).count();

    env->ReleaseByteArrayElements(nv21Array, nv21Ptr, JNI_ABORT);
    return elapsed;
}

}  // extern "C"

