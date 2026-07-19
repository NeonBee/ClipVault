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
constexpr jint kTelegram = 1 << 8;
constexpr jint kTiktok = 1 << 9;
constexpr jint kXTwitter = 1 << 10;
constexpr jint kGithub = 1 << 11;
constexpr jint kEmail = 1 << 12;
constexpr jint kPhone = 1 << 13;
constexpr jint kCode = 1 << 14;
constexpr jint kJson = 1 << 15;
constexpr jint kMixed = 1 << 16;
constexpr jint kSensitive = 1 << 17;

struct Analysis {
    std::string normalized;
    jint flags = 0;
    bool sensitive = false;
    std::string url;
    std::string domain;
};

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

bool ends_with(std::string_view value, std::string_view suffix) {
    return value.size() >= suffix.size() &&
           value.substr(value.size() - suffix.size()) == suffix;
}

bool domain_matches(std::string_view domain, std::string_view base) {
    if (domain == base) return true;
    return domain.size() > base.size() && ends_with(domain, base) &&
           domain[domain.size() - base.size() - 1] == '.';
}

std::vector<uint32_t> decode_utf8(std::string_view input) {
    std::vector<uint32_t> output;
    output.reserve(input.size());
    for (std::size_t i = 0; i < input.size();) {
        const auto first = static_cast<unsigned char>(input[i]);
        uint32_t codepoint = 0;
        std::size_t width = 0;
        if (first < 0x80U) {
            codepoint = first;
            width = 1;
        } else if ((first & 0xE0U) == 0xC0U) {
            codepoint = first & 0x1FU;
            width = 2;
        } else if ((first & 0xF0U) == 0xE0U) {
            codepoint = first & 0x0FU;
            width = 3;
        } else if ((first & 0xF8U) == 0xF0U) {
            codepoint = first & 0x07U;
            width = 4;
        } else {
            ++i;
            continue;
        }
        if (i + width > input.size()) break;
        bool valid = true;
        for (std::size_t offset = 1; offset < width; ++offset) {
            const auto continuation = static_cast<unsigned char>(input[i + offset]);
            if ((continuation & 0xC0U) != 0x80U) {
                valid = false;
                break;
            }
            codepoint = (codepoint << 6U) | (continuation & 0x3FU);
        }
        if (valid) {
            output.push_back(codepoint);
            i += width;
        } else {
            ++i;
        }
    }
    return output;
}

bool is_persian_codepoint(uint32_t codepoint) {
    return (codepoint >= 0x0600U && codepoint <= 0x06FFU) ||
           (codepoint >= 0x0750U && codepoint <= 0x077FU) ||
           (codepoint >= 0x08A0U && codepoint <= 0x08FFU) ||
           (codepoint >= 0xFB50U && codepoint <= 0xFDFFU) ||
           (codepoint >= 0xFE70U && codepoint <= 0xFEFFU);
}

int digit_value(uint32_t codepoint) {
    if (codepoint >= '0' && codepoint <= '9') return static_cast<int>(codepoint - '0');
    if (codepoint >= 0x06F0U && codepoint <= 0x06F9U) return static_cast<int>(codepoint - 0x06F0U);
    if (codepoint >= 0x0660U && codepoint <= 0x0669U) return static_cast<int>(codepoint - 0x0660U);
    return -1;
}

std::string trim_and_normalize(std::string input) {
    auto not_space = [](unsigned char c) { return !std::isspace(c); };
    const auto begin = std::find_if(input.begin(), input.end(), not_space);
    if (begin == input.end()) return {};
    const auto end = std::find_if(input.rbegin(), input.rend(), not_space).base();
    std::string trimmed(begin, end);
    std::string normalized;
    normalized.reserve(trimmed.size());
    int newlines = 0;
    for (const char character : trimmed) {
        if (character == '\r') continue;
        if (character == '\n') {
            ++newlines;
            if (newlines <= 2) normalized.push_back(character);
        } else {
            newlines = 0;
            normalized.push_back(character);
        }
    }
    return normalized;
}

std::string extract_url(std::string_view text) {
    const std::string lower = ascii_lower(std::string(text));
    std::size_t start = lower.find("https://");
    if (start == std::string::npos) start = lower.find("http://");
    if (start == std::string::npos) start = lower.find("www.");
    if (start == std::string::npos) return {};
    std::size_t end = start;
    while (end < text.size()) {
        const unsigned char character = static_cast<unsigned char>(text[end]);
        if (std::isspace(character) || character == '<' || character == '>') break;
        ++end;
    }
    constexpr std::string_view trailing = ".,;:!?)]}";
    while (end > start && trailing.find(text[end - 1]) != std::string_view::npos) --end;
    std::string url(text.substr(start, end - start));
    if (ascii_lower(url).rfind("www.", 0) == 0) url.insert(0, "https://");
    return url;
}

std::string extract_domain(const std::string& url) {
    if (url.empty()) return {};
    const std::string lower = ascii_lower(url);
    std::size_t start = lower.find("://");
    start = start == std::string::npos ? 0 : start + 3;
    std::size_t end = lower.find_first_of("/:?#", start);
    if (end == std::string::npos) end = lower.size();
    std::string domain = lower.substr(start, end - start);
    const std::size_t at = domain.rfind('@');
    if (at != std::string::npos) domain.erase(0, at + 1);
    const std::size_t port = domain.find(':');
    if (port != std::string::npos) domain.erase(port);
    if (domain.rfind("www.", 0) == 0) domain.erase(0, 4);
    return domain;
}

bool has_date_pattern(const std::vector<uint32_t>& codepoints, std::string_view lower) {
    int separators = 0;
    int digits = 0;
    for (const auto codepoint : codepoints) {
        if (digit_value(codepoint) >= 0) {
            ++digits;
        } else if ((codepoint == '/' || codepoint == '-' || codepoint == '.') && digits >= 1 && digits <= 4) {
            ++separators;
            digits = 0;
            if (separators >= 2) return true;
        } else {
            separators = 0;
            digits = 0;
        }
    }
    constexpr std::string_view months[] = {
        "january", "february", "march", "april", "may", "june", "july",
        "august", "september", "october", "november", "december"
    };
    for (const auto month : months) if (contains(lower, month)) return true;
    return contains(lower, "امروز") || contains(lower, "فردا") || contains(lower, "دیروز") ||
           contains(lower, "تاریخ");
}

bool has_email(std::string_view text) {
    const std::size_t at = text.find('@');
    if (at == std::string_view::npos || at == 0 || at + 3 >= text.size()) return false;
    const std::size_t dot = text.find('.', at + 2);
    return dot != std::string_view::npos && dot + 1 < text.size();
}

bool has_phone(const std::vector<uint32_t>& codepoints) {
    int run_digits = 0;
    int run_width = 0;
    for (const auto codepoint : codepoints) {
        if (digit_value(codepoint) >= 0) {
            ++run_digits;
            ++run_width;
        } else if (codepoint == '+' || codepoint == '-' || codepoint == '(' || codepoint == ')' || codepoint == ' ') {
            if (run_digits > 0) ++run_width;
        } else {
            if (run_digits >= 7 && run_digits <= 15 && run_width <= 22) return true;
            run_digits = 0;
            run_width = 0;
        }
    }
    return run_digits >= 7 && run_digits <= 15 && run_width <= 22;
}

bool luhn_valid(const std::vector<int>& digits) {
    if (digits.size() < 13 || digits.size() > 19) return false;
    int sum = 0;
    bool double_digit = false;
    for (auto iterator = digits.rbegin(); iterator != digits.rend(); ++iterator) {
        int value = *iterator;
        if (double_digit) {
            value *= 2;
            if (value > 9) value -= 9;
        }
        sum += value;
        double_digit = !double_digit;
    }
    return sum % 10 == 0;
}

bool looks_sensitive(std::string_view text, const std::vector<uint32_t>& codepoints) {
    const std::string lower = ascii_lower(std::string(text));
    constexpr std::string_view keywords[] = {
        "password", "passcode", "verification code", "one-time", "otp", "2fa",
        "api_key", "api key", "secret token", "private key", "کد تایید", "کد تأیید",
        "رمز عبور", "رمز یکبار", "رمز یک‌بار"
    };
    for (const auto keyword : keywords) if (contains(lower, keyword)) return true;

    std::vector<int> digits;
    int non_space = 0;
    for (const auto codepoint : codepoints) {
        const int value = digit_value(codepoint);
        if (value >= 0) {
            digits.push_back(value);
            ++non_space;
        } else if (codepoint != ' ' && codepoint != '\n' && codepoint != '\t' && codepoint != '-' &&
                   codepoint != '(' && codepoint != ')') {
            ++non_space;
        }
    }
    if (static_cast<int>(digits.size()) == non_space && digits.size() >= 4 && digits.size() <= 8) return true;
    return luhn_valid(digits);
}

Analysis analyze(std::string input) {
    Analysis result;
    result.normalized = trim_and_normalize(std::move(input));
    if (result.normalized.empty()) {
        result.flags = kOther;
        return result;
    }
    const std::string lower = ascii_lower(result.normalized);
    const auto codepoints = decode_utf8(result.normalized);
    result.url = extract_url(result.normalized);
    result.domain = extract_domain(result.url);
    if (!result.url.empty()) result.flags |= kLink;
    if (domain_matches(result.domain, "instagram.com") || domain_matches(result.domain, "instagr.am")) result.flags |= kInstagram | kLink;
    if (domain_matches(result.domain, "youtube.com") || domain_matches(result.domain, "youtu.be")) result.flags |= kYoutube | kLink;
    if (domain_matches(result.domain, "telegram.me") || domain_matches(result.domain, "t.me")) result.flags |= kTelegram | kLink;
    if (domain_matches(result.domain, "tiktok.com")) result.flags |= kTiktok | kLink;
    if (domain_matches(result.domain, "twitter.com") || domain_matches(result.domain, "x.com")) result.flags |= kXTwitter | kLink;
    if (domain_matches(result.domain, "github.com") || domain_matches(result.domain, "githubusercontent.com")) result.flags |= kGithub | kLink;

    std::size_t persian = 0;
    std::size_t english = 0;
    for (const auto codepoint : codepoints) {
        if (is_persian_codepoint(codepoint)) ++persian;
        if ((codepoint >= 'A' && codepoint <= 'Z') || (codepoint >= 'a' && codepoint <= 'z')) ++english;
    }
    if (persian > 0) result.flags |= kPersian;
    if (english > 0) result.flags |= kEnglish;
    if (persian > 0 && english > 0) result.flags |= kMixed;
    if (codepoints.size() >= 280 || std::count(result.normalized.begin(), result.normalized.end(), '\n') >= 5) result.flags |= kLongText;
    if (has_date_pattern(codepoints, lower)) result.flags |= kDate;
    if (has_email(result.normalized)) result.flags |= kEmail;
    if (has_phone(codepoints)) result.flags |= kPhone;

    const std::size_t first = result.normalized.find_first_not_of(" \n\t");
    const std::size_t last = result.normalized.find_last_not_of(" \n\t");
    if (first != std::string::npos && last != std::string::npos &&
        ((result.normalized[first] == '{' && result.normalized[last] == '}') ||
         (result.normalized[first] == '[' && result.normalized[last] == ']'))) result.flags |= kJson;
    if (contains(lower, "#include") || contains(lower, "function ") || contains(lower, "class ") ||
        contains(lower, "const ") || contains(lower, "fun ") || contains(lower, "public static")) result.flags |= kCode;

    result.sensitive = looks_sensitive(result.normalized, codepoints);
    if (result.sensitive) result.flags |= kSensitive;
    if (result.flags == 0) result.flags = kOther;
    return result;
}

jstring new_string(JNIEnv* env, const std::string& value) {
    return env->NewStringUTF(value.c_str());
}

}  // namespace

extern "C" JNIEXPORT jobjectArray JNICALL
Java_dev_clipvault_app_nativecore_NativeClassifier_analyzeNative(
        JNIEnv* env, jclass, jstring input) {
    const Analysis result = analyze(from_jstring(env, input));
    jclass string_class = env->FindClass("java/lang/String");
    if (string_class == nullptr) return nullptr;
    jobjectArray output = env->NewObjectArray(5, string_class, nullptr);
    if (output == nullptr) return nullptr;
    const std::string flags = std::to_string(result.flags);
    env->SetObjectArrayElement(output, 0, new_string(env, result.normalized));
    env->SetObjectArrayElement(output, 1, new_string(env, flags));
    env->SetObjectArrayElement(output, 2, new_string(env, result.sensitive ? "1" : "0"));
    env->SetObjectArrayElement(output, 3, new_string(env, result.url));
    env->SetObjectArrayElement(output, 4, new_string(env, result.domain));
    return output;
}
