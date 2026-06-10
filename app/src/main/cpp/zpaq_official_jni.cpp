#include <jni.h>
#include <string>
#include <vector>
#include <stdexcept>
#include <unistd.h>
#include <fcntl.h>
#include <pthread.h>
#include <cstdio>
#include <cstring>
#include <algorithm>
#include <cctype>
#include <cmath>

#include "zpaqcli/zpaq.cpp"

namespace {

struct CaptureResult {
    int exitCode = 0;
    std::string stdoutText;
    std::string stderrText;
};

struct ProgressParseState {
    pthread_mutex_t mutex = PTHREAD_MUTEX_INITIALIZER;
    long long totalBytes = 1;
    long long lastReportedBytes = -1;
    std::string pendingText;
};

struct ReaderArgs {
    int fd = -1;
    std::string* target = nullptr;
    ProgressParseState* progressState = nullptr;
};

static JavaVM* g_progressVm = nullptr;
static jclass g_progressClass = nullptr;
static jmethodID g_progressMethod = nullptr;

void reportCommandProgress(long long processedBytes, long long totalBytes, const std::string& currentEntry) {
    if (!g_progressVm || !g_progressMethod || !g_progressClass) return;
    JNIEnv* env = nullptr;
    bool attached = false;
    if (g_progressVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_progressVm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }
    jstring entry = env->NewStringUTF(currentEntry.c_str());
    env->CallStaticVoidMethod(g_progressClass, g_progressMethod,
            static_cast<jlong>(processedBytes), static_cast<jlong>(totalBytes), entry);
    env->DeleteLocalRef(entry);
    if (attached) g_progressVm->DetachCurrentThread();
}

static std::string trimCopy(const std::string& value) {
    size_t start = 0;
    while (start < value.size() && std::isspace(static_cast<unsigned char>(value[start]))) ++start;
    size_t end = value.size();
    while (end > start && std::isspace(static_cast<unsigned char>(value[end - 1]))) --end;
    return value.substr(start, end - start);
}

static void parseProgressLineLocked(ProgressParseState& state, const std::string& rawLine) {
    std::string line = trimCopy(rawLine);
    if (line.size() < 2) return;
    size_t percentPos = line.find('%');
    if (percentPos == std::string::npos) return;
    try {
        double percent = std::stod(trimCopy(line.substr(0, percentPos)));
        long long safeTotal = 100;
    long long processed = static_cast<long long>(std::llround(std::max(0.0, std::min(100.0, percent))));
    if (processed != state.lastReportedBytes) {
        reportCommandProgress(processed, safeTotal, line);
        state.lastReportedBytes = processed;
    }
    } catch (...) {
    }
}

static void parseProgressChunk(ProgressParseState& state, const char* data, size_t size) {
    if (!data || size == 0) return;
    pthread_mutex_lock(&state.mutex);
    state.pendingText.append(data, size);
    while (true) {
        size_t lineEnd = state.pendingText.find_first_of("\r\n");
        if (lineEnd == std::string::npos) break;
        std::string line = state.pendingText.substr(0, lineEnd);
        size_t eraseEnd = lineEnd + 1;
        while (eraseEnd < state.pendingText.size()
                && (state.pendingText[eraseEnd] == '\r' || state.pendingText[eraseEnd] == '\n')) {
            ++eraseEnd;
        }
        state.pendingText.erase(0, eraseEnd);
        parseProgressLineLocked(state, line);
    }
    pthread_mutex_unlock(&state.mutex);
}

static void flushProgressState(ProgressParseState& state) {
    pthread_mutex_lock(&state.mutex);
    if (!state.pendingText.empty()) {
        parseProgressLineLocked(state, state.pendingText);
        state.pendingText.clear();
    }
    pthread_mutex_unlock(&state.mutex);
}

void* readPipeThread(void* raw) {
    ReaderArgs* args = static_cast<ReaderArgs*>(raw);
    char buffer[4096];
    while (true) {
        ssize_t n = read(args->fd, buffer, sizeof(buffer));
        if (n <= 0) break;
        args->target->append(buffer, static_cast<size_t>(n));
        if (args->progressState) {
            parseProgressChunk(*args->progressState, buffer, static_cast<size_t>(n));
        }
    }
    close(args->fd);
    return nullptr;
}

CaptureResult runZpaqCommand(const std::vector<std::string>& args, ProgressParseState* progressState = nullptr) {
    if (args.empty()) throw std::runtime_error("empty zpaq command");

    int stdoutPipe[2];
    int stderrPipe[2];
    if (pipe(stdoutPipe) != 0 || pipe(stderrPipe) != 0) {
        throw std::runtime_error("pipe() failed");
    }

    fflush(stdout);
    fflush(stderr);
    int oldStdout = dup(STDOUT_FILENO);
    int oldStderr = dup(STDERR_FILENO);
    if (oldStdout < 0 || oldStderr < 0) {
        throw std::runtime_error("dup() failed");
    }

    if (dup2(stdoutPipe[1], STDOUT_FILENO) < 0 || dup2(stderrPipe[1], STDERR_FILENO) < 0) {
        throw std::runtime_error("dup2() failed");
    }
    close(stdoutPipe[1]);
    close(stderrPipe[1]);

    CaptureResult result;
    ReaderArgs stdoutArgs{stdoutPipe[0], &result.stdoutText, progressState};
    ReaderArgs stderrArgs{stderrPipe[0], &result.stderrText, nullptr};
    pthread_t stdoutThread;
    pthread_t stderrThread;
    pthread_create(&stdoutThread, nullptr, readPipeThread, &stdoutArgs);
    pthread_create(&stderrThread, nullptr, readPipeThread, &stderrArgs);

    global_start = mtime();
    try {
        std::vector<const char*> argv;
        argv.reserve(args.size());
        for (const std::string& arg : args) argv.push_back(arg.c_str());
        Jidac jidac;
        result.exitCode = jidac.doCommand(static_cast<int>(argv.size()), argv.data());
    } catch (const std::exception& e) {
        fprintf(stderr, "zpaq error: %s\n", e.what());
        result.exitCode = 2;
    }
    fflush(stdout);
    fflush(stderr);
    fprintf(stderr, "%1.3f seconds %s\n", (mtime()-global_start)/1000.0,
            result.exitCode > 1 ? "(with errors)" :
            result.exitCode > 0 ? "(with warnings)" : "(all OK)");
    fflush(stderr);

    dup2(oldStdout, STDOUT_FILENO);
    dup2(oldStderr, STDERR_FILENO);
    close(oldStdout);
    close(oldStderr);

    pthread_join(stdoutThread, nullptr);
    pthread_join(stderrThread, nullptr);

    if (progressState) {
        flushProgressState(*progressState);
    }
    return result;
}

jstring makeResult(JNIEnv* env, const CaptureResult& result) {
    std::string payload = "EXIT=" + std::to_string(result.exitCode) + "\n";
    payload += "STDOUT:\n" + result.stdoutText + "\nSTDERR:\n" + result.stderrText;
    return env->NewStringUTF(payload.c_str());
}

std::vector<std::string> toStringVector(JNIEnv* env, jobjectArray argsArray) {
    std::vector<std::string> args;
    if (!argsArray) return args;
    jsize len = env->GetArrayLength(argsArray);
    args.reserve(static_cast<size_t>(len));
    for (jsize i = 0; i < len; ++i) {
        jstring item = static_cast<jstring>(env->GetObjectArrayElement(argsArray, i));
        const char* chars = item ? env->GetStringUTFChars(item, nullptr) : nullptr;
        args.emplace_back(chars ? chars : "");
        if (chars) env->ReleaseStringUTFChars(item, chars);
        if (item) env->DeleteLocalRef(item);
    }
    return args;
}

}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_progressVm = vm;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass localClass = env->FindClass("com/operit/zpaq/ZPAQNative");
    if (!localClass) return JNI_ERR;
    g_progressClass = reinterpret_cast<jclass>(env->NewGlobalRef(localClass));
    env->DeleteLocalRef(localClass);
    if (!g_progressClass) return JNI_ERR;
    g_progressMethod = env->GetStaticMethodID(g_progressClass, "onNativeProgress", "(JJLjava/lang/String;)V");
    if (!g_progressMethod) return JNI_ERR;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_operit_zpaq_ZPAQNative_runCommand(JNIEnv* env, jclass, jobjectArray argsArray) {
    try {
        CaptureResult result = runZpaqCommand(toStringVector(env, argsArray));
        return makeResult(env, result);
    } catch (const std::exception& e) {
        std::string error = std::string("EXIT=2\nSTDOUT:\n\nSTDERR:\n") + e.what();
        return env->NewStringUTF(error.c_str());
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_operit_zpaq_ZPAQNative_addToArchiveNative(JNIEnv* env, jclass, jstring archivePath, jobjectArray inputPaths, jint level, jint threads) {
    try {
        std::vector<std::string> args;
        args.emplace_back("zpaq");
        args.emplace_back("add");
        const char* archiveChars = archivePath ? env->GetStringUTFChars(archivePath, nullptr) : nullptr;
        args.emplace_back(archiveChars ? archiveChars : "");
        if (archiveChars) env->ReleaseStringUTFChars(archivePath, archiveChars);

        std::vector<std::string> inputVec = toStringVector(env, inputPaths);
        for (const std::string& input : inputVec) {
            if (!input.empty()) args.push_back(input);
        }
        args.emplace_back("-method");
        args.emplace_back(std::to_string(std::max(0, std::min(5, static_cast<int>(level)))));
        args.emplace_back("-threads");
        args.emplace_back(std::to_string(std::max(1, static_cast<int>(threads))));
        args.emplace_back("-summary");
        args.emplace_back("1");

        ProgressParseState progressState;
        progressState.totalBytes = 100;
        reportCommandProgress(0, 100, "准备中");
        CaptureResult result = runZpaqCommand(args, &progressState);
        reportCommandProgress(100, 100, "完成");
        pthread_mutex_destroy(&progressState.mutex);
        return makeResult(env, result);
    } catch (const std::exception& e) {
        std::string error = std::string("EXIT=2\nSTDOUT:\n\nSTDERR:\n") + e.what();
        return env->NewStringUTF(error.c_str());
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_operit_zpaq_ZPAQNative_deleteVersionNative(JNIEnv* env, jclass, jstring archivePath, jstring untilValue) {
    try {
        std::vector<std::string> args;
        args.emplace_back("zpaq");
        args.emplace_back("add");

        const char* archiveChars = archivePath ? env->GetStringUTFChars(archivePath, nullptr) : nullptr;
        args.emplace_back(archiveChars ? archiveChars : "");
        if (archiveChars) env->ReleaseStringUTFChars(archivePath, archiveChars);

        args.emplace_back("");  // 空字符串表示不添加任何文件，只更新元数据

        args.emplace_back("-until");
        const char* untilChars = untilValue ? env->GetStringUTFChars(untilValue, nullptr) : nullptr;
        args.emplace_back(untilChars ? untilChars : "0");
        if (untilChars) env->ReleaseStringUTFChars(untilValue, untilChars);

        args.emplace_back("-force");

        CaptureResult result = runZpaqCommand(args);
        return makeResult(env, result);
    } catch (const std::exception& e) {
        std::string error = std::string("EXIT=2\nSTDOUT:\n\nSTDERR:\n") + e.what();
        return env->NewStringUTF(error.c_str());
    }
}
