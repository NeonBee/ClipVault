#include <jni.h>

#include <algorithm>
#include <cctype>
#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

namespace {

constexpr jint kLink = 1 << 0;
constexpr jint kInstagram = 1 << 1;
constexpr jint kYoutube = 1 << 2;
constexpr jint kPersian = 1 << 3;
constexpr jint kEnglish = 1 << 4;
constexpr jint kLongText = 1 << 5;
constexpr jint kDate = 1 << 6;
constexpr jint kOther = 1 << 7;

std::string from_jstring(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

std::string ascii_lower(std::string input) {
    std::transform(input.begin(), input.end(), input.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return input;
}

bool contains(std::string_view haystack, std::string_view needle) {
    return haystack.find(needle) != std::string_view::npos;
}

std::vector<uint32_t> decode_utf8(std::string_view input) {
    std::vector<uint32_t> out;
    out.reserve(input.size());
    for (std::size_t i = 0; i < input.size();) {
        const auto c = static_cast<unsigned char>(input[i]);
        if (c < 0x80) {
            out.push_back(c);
            ++i;
        } else if ((c >> 5U) == 0x6U && i + 1 < input.size()) {
            out.push_back(((c & 0x1FU) << 6U) |
                          (static_cast<unsigned char>(input[i + 1]) & 0x3FU));
            i += 2;
        } else if ((c >> 4U) == 0xEU && i + 2 < input.size()) {
            out.push_back(((c & 0x0FU) << 12U) |
                          ((static_cast<unsigned char>(input[i + 1]) & 0x3FU) << 6U) |
                          (static_cast<unsigned char>(input[i + 2]) & 0x3FU));
            i += 3;
        } else if ((c >> 3U) == 0x1EU && i + 3 < input.size()) {
            out.push_back(((c & 0x07U) << 18U) |
                          ((static_cast<unsigned char>(input[i + 1]) & 0x3FU) << 12U) |
                          ((static_cast<unsigned char>(input[i + 2]) & 0x3FU) << 6U) |
                          (static_cast<unsigned char>(input[i + 3]) & 0x3FU));
            i += 4;
        } else {
            ++i;
        }
    }
    return out;
}

bool is_persian_codepoint(uint32_t cp) {
    return (cp >= 0x0600 && cp <= 0x06FF) ||
           (cp >= 0x0750 && cp <= 0x077F) ||
           (cp >= 0x08A0 && cp <= 0x08FF) ||
           (cp >= 0xFB50 && cp <= 0xFDFF) ||
           (cp >= 0xFE70 && cp <= 0xFEFF);
}

int digit_value(uint32_t cp) {
    if (cp >= '0' && cp <= '9') return static_cast<int>(cp - '0');
    if (cp >= 0x06F0 && cp <= 0x06F9) return static_cast<int>(cp - 0x06F0);
    if (cp >= 0x0660 && cp <= 0x0669) return static_cast<int>(cp - 0x0660);
    return -1;
}

bool has_date_pattern(const std::vector<uint32_t>& cps, std::string_view lower) {
    int groups = 0;
    int digits = 0;
    for (const auto cp : cps) {
        if (digit_value(cp) >= 0) {
            ++digits;
            continue;
        }
        if ((cp == '/' || cp == '-' || cp == '.') && digits >= 1 && digits <= 4) {
            ++groups;
            digits = 0;
            if (groups >= 2) return true;
        } else {
            groups = 0;
            digits = 0;
        }
    }

    constexpr std::string_view months[] = {
        "january", "february", "march", "april", "may", "june", "july",
        "august", "september", "october", "november", "december"
    };
    for (const auto month : months) {
        if (contains(lower, month)) return true;
    }

    return contains(lower, "تاریخ") || contains(lower, "امروز") ||
           contains(lower, "فردا") || contains(lower, "دیروز");
}

std::string trim_and_normalize(std::string input) {
    auto not_space = [](unsigned char c) { return !std::isspace(c); };
    const auto begin = std::find_if(input.begin(), input.end(), not_space);
    if (begin == input.end()) return {};
    const auto end = std::find_if(input.rbegin(), input.rend(), not_space).base();
    std::string out(begin, end);

    std::string normalized;
    normalized.reserve(out.size());
    int consecutive_newlines = 0;
    for (char c : out) {
        if (c == '\r') continue;
        if (c == '\n') {
            ++consecutive_newlines;
            if (consecutive_newlines <= 2) normalized.push_back(c);
        } else {
            consecutive_newlines = 0;
            normalized.push_back(c);
        }
    }
    return normalized;
}

bool looks_sensitive(std::string_view text) {
    const std::string lower = ascii_lower(std::string(text));
    constexpr std::string_view keywords[] = {
        "password", "passcode", "verification code", "one-time", "otp", "2fa",
        "کد تایید", "کد تأیید", "رمز عبور", "رمز یکبار", "رمز یک‌بار"
    };
    for (const auto word : keywords) {
        if (contains(lower, word)) return true;
    }

    int digits = 0;
    int non_space = 0;
    for (const auto cp : decode_utf8(text)) {
        if (digit_value(cp) >= 0) {
            ++digits;
            ++non_space;
        } else if (cp != ' ' && cp != '\n' && cp != '\t') {
            ++non_space;
        }
    }
    return non_space == digits && digits >= 4 && digits <= 8;
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_dev_clipvault_app_nativecore_NativeClassifier_classifyNative(
        JNIEnv* env, jclass, jstring input) {
    const std::string text = from_jstring(env, input);
    if (text.empty()) return kOther;
    const std::string lower = ascii_lower(text);
    const auto codepoints = decode_utf8(text);

    jint flags = 0;
    if (contains(lower, "http://") || contains(lower, "https://") ||
        contains(lower, "www.")) {
        flags |= kLink;
    }
    if (contains(lower, "instagram.com") || contains(lower, "instagr.am")) {
        flags |= kInstagram | kLink;
    }
    if (contains(lower, "youtube.com") || contains(lower, "youtu.be")) {
        flags |= kYoutube | kLink;
    }

    std::size_t persian_count = 0;
    std::size_t english_count = 0;
    for (const auto cp : codepoints) {
        if (is_persian_codepoint(cp)) ++persian_count;
        if ((cp >= 'A' && cp <= 'Z') || (cp >= 'a' && cp <= 'z')) ++english_count;
    }
    if (persian_count > 0) flags |= kPersian;
    if (english_count > 0) flags |= kEnglish;
    if (codepoints.size() >= 280 || std::count(text.begin(), text.end(), '\n') >= 5) {
        flags |= kLongText;
    }
    if (has_date_pattern(codepoints, lower)) flags |= kDate;
    if (flags == 0) flags = kOther;
    return flags;
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_clipvault_app_nativecore_NativeClassifier_normalizeNative(
        JNIEnv* env, jclass, jstring input) {
    const std::string normalized = trim_and_normalize(from_jstring(env, input));
    return env->NewStringUTF(normalized.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_clipvault_app_nativecore_NativeClassifier_isSensitiveNative(
        JNIEnv* env, jclass, jstring input) {
    return looks_sensitive(from_jstring(env, input)) ? JNI_TRUE : JNI_FALSE;
}
