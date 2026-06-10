#include <jni.h>
#include <string>
#include <vector>
#include <memory>
#include <fstream>
#include <filesystem>
#include <stdexcept>
#include <algorithm>
#include <cstdint>
#include <ctime>
#include <sstream>
#include <iomanip>
#include <sys/stat.h>
#include <unistd.h>
#include <utime.h>
#include <cerrno>
#include "zpaq/libzpaq.h"
namespace fs = std::filesystem;

static std::string g_last_error;
static JavaVM* g_vm = nullptr;
static jclass g_zpaq_native_class = nullptr;
static jmethodID g_progress_method = nullptr;


struct ProgressState {
    long long totalBytes = 0;
    long long processedBytes = 0;
    long long lastReportedBytes = -1;
    std::string currentEntry;
};

struct EntryMetadata {
    long long size = 0;
    std::time_t mtime = 0;
    mode_t mode = 0;
    uid_t uid = 0;
    gid_t gid = 0;
    bool hasStat = false;
};

static std::string jstringToStd(JNIEnv* env, jstring value) {
    if (!value) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string out = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(value, chars);
    return out;
}

static std::vector<std::string> jarrayToVector(JNIEnv* env, jobjectArray array) {
    std::vector<std::string> out;
    if (!array) return out;
    jsize len = env->GetArrayLength(array);
    out.reserve(len);
    for (jsize i = 0; i < len; ++i) {
        jstring item = static_cast<jstring>(env->GetObjectArrayElement(array, i));
        out.push_back(jstringToStd(env, item));
        env->DeleteLocalRef(item);
    }
    return out;
}

static void reportProgress(const ProgressState& state) {
    if (!g_vm || !g_progress_method || !g_zpaq_native_class) return;
    if (state.processedBytes == state.lastReportedBytes && state.currentEntry.empty()) return;

    JNIEnv* env = nullptr;
    bool attached = false;
    if (g_vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }

    jstring entry = env->NewStringUTF(state.currentEntry.c_str());
    env->CallStaticVoidMethod(g_zpaq_native_class, g_progress_method,
            static_cast<jlong>(state.processedBytes),
            static_cast<jlong>(state.totalBytes),
            entry);
    env->DeleteLocalRef(entry);

    if (attached) g_vm->DetachCurrentThread();
}

static EntryMetadata readEntryMetadata(const fs::path& path, long long fallbackSize) {
    EntryMetadata meta;
    meta.size = fallbackSize;
    struct stat st {};
    if (::lstat(path.c_str(), &st) == 0) {
        meta.size = static_cast<long long>(st.st_size);
        meta.mtime = st.st_mtime;
        meta.mode = st.st_mode;
        meta.uid = st.st_uid;
        meta.gid = st.st_gid;
        meta.hasStat = true;
    }
    return meta;
}

static std::string formatTimestamp(std::time_t value) {
    if (value <= 0) return "00000000000000";
    std::tm tmValue {};
#if defined(_WIN32)
    localtime_s(&tmValue, &value);
#else
    localtime_r(&value, &tmValue);
#endif
    std::ostringstream out;
    out << std::put_time(&tmValue, "%Y%m%d%H%M%S");
    return out.str();
}

static std::string buildComment(const EntryMetadata& meta) {
    std::ostringstream out;
    out << meta.size << ' ' << formatTimestamp(meta.mtime) << ' ';
    std::ostringstream modeStream;
    modeStream << std::oct << (meta.mode & 07777);
    out << "mode=" << modeStream.str();
    out << " uid=" << static_cast<unsigned long long>(meta.uid);
    out << " gid=" << static_cast<unsigned long long>(meta.gid);
    return out.str();
}

static EntryMetadata parseComment(const std::string& comment) {
    EntryMetadata meta;
    std::istringstream input(comment);
    std::string sizeToken;
    std::string timeToken;
    if (!(input >> sizeToken)) return meta;
    try {
        meta.size = std::stoll(sizeToken);
    } catch (...) {
        meta.size = 0;
    }
    if (input >> timeToken) {
        if (timeToken.size() >= 14) {
            std::tm tmValue {};
            std::istringstream timeStream(timeToken.substr(0, 14));
            timeStream >> std::get_time(&tmValue, "%Y%m%d%H%M%S");
            if (!timeStream.fail()) {
                meta.mtime = std::mktime(&tmValue);
            }
        }
    }
    std::string token;
    while (input >> token) {
        auto pos = token.find('=');
        if (pos == std::string::npos) continue;
        std::string key = token.substr(0, pos);
        std::string value = token.substr(pos + 1);
        try {
            if (key == "mode") {
                meta.mode = static_cast<mode_t>(std::stoul(value, nullptr, 8));
            } else if (key == "uid") {
                meta.uid = static_cast<uid_t>(std::stoul(value));
            } else if (key == "gid") {
                meta.gid = static_cast<gid_t>(std::stoul(value));
            }
        } catch (...) {
        }
    }
    meta.hasStat = true;
    return meta;
}

static void restoreMetadata(const fs::path& path, const EntryMetadata& meta) {
    if (meta.mtime > 0) {
        struct utimbuf times {};
        times.actime = meta.mtime;
        times.modtime = meta.mtime;
        ::utime(path.c_str(), &times);
    }
    if (meta.mode != 0) {
        ::chmod(path.c_str(), meta.mode & 07777);
    }
    if (meta.uid != 0 || meta.gid != 0) {
        ::chown(path.c_str(), meta.uid, meta.gid);
    }
}

class FileReader final : public libzpaq::Reader {
public:
    FileReader(const std::string& path, ProgressState* progress, std::string entryName)
        : in(path, std::ios::binary), progress(progress), entryName(std::move(entryName)) {}

    int get() override {
        char c;
        if (!in.get(c)) return -1;
        notifyProgress(1);
        return static_cast<unsigned char>(c);
    }

    int read(char* buf, int n) override {
        in.read(buf, n);
        int count = static_cast<int>(in.gcount());
        if (count > 0) notifyProgress(count);
        return count;
    }

    bool good() const { return in.good(); }

private:
    void notifyProgress(int count) {
        if (!progress) return;
        progress->currentEntry = entryName;
        progress->processedBytes += count;
        const long long step = 256 * 1024;
        if (progress->processedBytes == progress->totalBytes ||
            progress->lastReportedBytes < 0 ||
            progress->processedBytes - progress->lastReportedBytes >= step) {
            reportProgress(*progress);
            progress->lastReportedBytes = progress->processedBytes;
        }
    }

    std::ifstream in;
    ProgressState* progress;
    std::string entryName;
};

class FileWriter final : public libzpaq::Writer {
public:
    explicit FileWriter(const std::string& path) : out(path, std::ios::binary) {}
    void put(int c) override { out.put(static_cast<char>(c)); }
    void write(const char* buf, int n) override { out.write(buf, n); }
    bool good() const { return out.good(); }
private:
    std::ofstream out;
};

class StringWriter final : public libzpaq::Writer {
public:
    void put(int c) override { data.push_back(static_cast<char>(c)); }
    void write(const char* buf, int n) override { data.append(buf, n); }
    std::string data;
};

struct InputEntry {
    fs::path sourcePath;
    std::string archiveName;
    bool isDirectory = false;
    uintmax_t size = 0;
    EntryMetadata metadata;
};

static void addDirectoryEntries(const fs::path& sourceRoot, const fs::path& current, std::vector<InputEntry>& entries) {
    InputEntry dirEntry;
    dirEntry.sourcePath = current;
    dirEntry.archiveName = fs::relative(current, sourceRoot.parent_path()).generic_string() + "/";
    dirEntry.isDirectory = true;
    dirEntry.size = 0;
    dirEntry.metadata = readEntryMetadata(current, 0);
    entries.push_back(dirEntry);

    std::vector<fs::directory_entry> children;
    for (const auto& child : fs::directory_iterator(current)) {
        children.push_back(child);
    }
    std::sort(children.begin(), children.end(), [](const auto& a, const auto& b) {
        return a.path().filename().generic_string() < b.path().filename().generic_string();
    });

    if (children.empty()) {
        std::string archiveName = fs::relative(current, sourceRoot.parent_path()).generic_string() + "/";
        InputEntry entry;
        entry.sourcePath = current;
        entry.archiveName = archiveName;
        entry.isDirectory = true;
        entry.size = 0;
        entry.metadata = readEntryMetadata(current, 0);
        entries.push_back(entry);
        return;
    }

    for (const auto& child : children) {
        if (child.is_directory()) {
            addDirectoryEntries(sourceRoot, child.path(), entries);
        } else if (child.is_regular_file()) {
            InputEntry entry;
            entry.sourcePath = child.path();
            entry.archiveName = fs::relative(child.path(), sourceRoot.parent_path()).generic_string();
            entry.isDirectory = false;
            entry.size = child.file_size();
            entry.metadata = readEntryMetadata(child.path(), static_cast<long long>(entry.size));
            entries.push_back(entry);
        }
    }
}

static std::vector<InputEntry> collectEntries(const std::vector<std::string>& inputs, long long& totalBytes) {
    std::vector<InputEntry> entries;
    totalBytes = 0;
    for (const auto& input : inputs) {
        fs::path path(input);
        if (!fs::exists(path)) continue;
        if (fs::is_regular_file(path)) {
            uintmax_t size = fs::file_size(path);
            InputEntry entry;
            entry.sourcePath = path;
            entry.archiveName = path.filename().generic_string();
            entry.isDirectory = false;
            entry.size = size;
            entry.metadata = readEntryMetadata(path, static_cast<long long>(size));
            entries.push_back(entry);
        } else if (fs::is_directory(path)) {
            addDirectoryEntries(path, path, entries);
        }
    }
    long long recomputed = 0;
    for (const auto& entry : entries) {
        if (!entry.isDirectory) recomputed += static_cast<long long>(entry.size);
    }
    totalBytes = recomputed;
    return entries;
}

static void compressEntries(const std::vector<InputEntry>& entries, libzpaq::Compressor& compressor, ProgressState& progress) {
    for (const auto& entry : entries) {
        std::string comment = buildComment(entry.metadata);
        if (entry.isDirectory) {
            compressor.startSegment(entry.archiveName.c_str(), comment.c_str());
            compressor.endSegment(nullptr);
            continue;
        }
        FileReader reader(entry.sourcePath.string(), &progress, entry.archiveName);
        if (!reader.good()) throw std::runtime_error("cannot open input: " + entry.sourcePath.string());
        compressor.startSegment(entry.archiveName.c_str(), comment.c_str());
        compressor.setInput(&reader);
        while (compressor.compress(1 << 16)) {}
        compressor.endSegment(nullptr);
    }
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad_DISABLED(JavaVM* vm, void*) {
    g_vm = vm;
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }
    jclass localClass = env->FindClass("com/operit/zpaq/ZPAQNative");
    if (!localClass) return JNI_ERR;
    g_zpaq_native_class = reinterpret_cast<jclass>(env->NewGlobalRef(localClass));
    env->DeleteLocalRef(localClass);
    if (!g_zpaq_native_class) return JNI_ERR;
    g_progress_method = env->GetStaticMethodID(g_zpaq_native_class, "onNativeProgress", "(JJLjava/lang/String;)V");
    if (!g_progress_method) return JNI_ERR;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_operit_zpaq_ZPAQNative_compressFiles(JNIEnv* env, jclass, jobjectArray paths, jstring outputPath, jint level) {
    try {
        g_last_error.clear();
        std::vector<std::string> inputs = jarrayToVector(env, paths);
        std::string output = jstringToStd(env, outputPath);
        if (inputs.empty()) return env->NewStringUTF("ERROR: no input paths");
        fs::create_directories(fs::path(output).parent_path());
        FileWriter writer(output);
        if (!writer.good()) return env->NewStringUTF((std::string("ERROR: cannot open output: ") + output).c_str());

        long long totalBytes = 0;
        std::vector<InputEntry> entries = collectEntries(inputs, totalBytes);
        if (entries.empty()) return env->NewStringUTF("ERROR: no valid input entries");

        ProgressState progress;
        progress.totalBytes = std::max<long long>(totalBytes, 1);
        reportProgress(progress);

        libzpaq::Compressor compressor;
        compressor.setOutput(&writer);
        compressor.writeTag();
        compressor.startBlock(std::max(1, std::min(3, static_cast<int>(level) + 1)));
        compressEntries(entries, compressor, progress);
        compressor.endBlock();
        progress.processedBytes = progress.totalBytes;
        reportProgress(progress);
        return env->NewStringUTF("OK");
    } catch (const std::exception& e) {
        return env->NewStringUTF((std::string("ERROR: ") + e.what()).c_str());
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_operit_zpaq_ZPAQNative_decompressFiles(JNIEnv* env, jclass, jstring archivePath, jstring outputDir) {
    try {
        g_last_error.clear();
        std::string archive = jstringToStd(env, archivePath);
        std::string outDir = jstringToStd(env, outputDir);
        fs::create_directories(outDir);
        FileReader reader(archive, nullptr, "");
        if (!reader.good()) return env->NewStringUTF((std::string("ERROR: cannot open archive: ") + archive).c_str());

        libzpaq::Decompresser decompresser;
        decompresser.setInput(&reader);
        while (decompresser.findBlock()) {
            StringWriter nameWriter;
            StringWriter commentWriter;
            while (decompresser.findFilename(&nameWriter)) {
                std::string name = nameWriter.data;
                nameWriter.data.clear();
                decompresser.readComment(&commentWriter);
                std::string comment = commentWriter.data;
                commentWriter.data.clear();
                EntryMetadata metadata = parseComment(comment);
                fs::path target = fs::path(outDir) / fs::path(name);
                if (!name.empty() && name.back() == '/') {
                    fs::create_directories(target);
                    restoreMetadata(target, metadata);
                    decompresser.readSegmentEnd(nullptr);
                    continue;
                }
                fs::create_directories(target.parent_path());
                FileWriter writer(target.string());
                if (!writer.good()) throw std::runtime_error("cannot create output: " + target.string());
                decompresser.setOutput(&writer);
                while (decompresser.decompress(1 << 16)) {}
                decompresser.readSegmentEnd(nullptr);
                restoreMetadata(target, metadata);
            }
        }
        return env->NewStringUTF("OK");
    } catch (const std::exception& e) {
        return env->NewStringUTF((std::string("ERROR: ") + e.what()).c_str());
    }
}
extern "C" JNIEXPORT jstring JNICALL
Java_com_operit_zpaq_ZPAQNative_listEntries(JNIEnv* env, jclass, jstring archivePath) {
    try {
        g_last_error.clear();
        std::string archive = jstringToStd(env, archivePath);
        FileReader reader(archive, nullptr, "");
        if (!reader.good()) return env->NewStringUTF((std::string("ERROR: cannot open archive: ") + archive).c_str());

        libzpaq::Decompresser decompresser;
        decompresser.setInput(&reader);
        std::string out;
        while (decompresser.findBlock()) {
            StringWriter nameWriter;
            StringWriter commentWriter;
            while (decompresser.findFilename(&nameWriter)) {
                std::string name = nameWriter.data;
                nameWriter.data.clear();
                decompresser.readComment(&commentWriter);
                std::string comment = commentWriter.data;
                commentWriter.data.clear();
                out += name;
                out += '\t';
                out += comment;
                out += '\n';
                decompresser.readSegmentEnd(nullptr);
            }
        }
        return env->NewStringUTF(out.c_str());
    } catch (const std::exception& e) {
        return env->NewStringUTF((std::string("ERROR: ") + e.what()).c_str());
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_operit_zpaq_ZPAQNative_getVersion(JNIEnv* env, jclass) {
    return env->NewStringUTF("libzpaq 7.15 / zpaq715_fixed");
}

