package dev.clipvault.app.nativecore;

import androidx.annotation.NonNull;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Browse-only classification augmentation shared by JNI and Java fallback.
 *
 * IMPORTANT: These flags are NOT a security decision. In particular, they must
 * NEVER change TextAnalysis.sensitive or the pre-existing skip-sensitive auto
 * capture policy. Matching a keyword does not prove that the clip is a secret.
 *
 * The augmentation runs after both native and Java text analysis so the JNI
 * classifier remains the source of normalization and legacy capture decisions.
 */
public final class BrowseClassifier {
    public static final int VERSION = 1;

    private static final Pattern KOREAN = Pattern.compile(
            "[\\u1100-\\u11FF\\u3130-\\u318F\\uAC00-\\uD7A3]");
    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "-----BEGIN (?:[A-Z0-9]+ )?PRIVATE KEY-----");
    private static final Pattern SSH_PUBLIC_KEY = Pattern.compile(
            "(?i)(?:^|\\s)(?:ssh-rsa|ssh-ed25519|ecdsa-sha2-[a-z0-9-]+)\\s+[A-Za-z0-9+/=]{24,}");
    private static final Pattern JWT = Pattern.compile(
            "(?:^|[^A-Za-z0-9_-])eyJ[A-Za-z0-9_-]{8,}\\.eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}");
    private static final Pattern TOKEN_PREFIX = Pattern.compile(
            "(?:ghp_[A-Za-z0-9]{12,}|github_pat_[A-Za-z0-9_]{12,}|AKIA[0-9A-Z]{16})");
    private static final Pattern TOKENIZED_URL = Pattern.compile(
            "(?i)[?&](?:token|access_token|api_key|key|sig|signature|secret|auth)=[^&#\\s]{3,}");
    // Requiring a plausible target avoids treating documentation headings such
    // as "SSH configuration reference" as actual connection commands. A bare
    // ambiguous word is deliberately not classified as an SSH destination.
    private static final Pattern SSH_COMMAND = Pattern.compile(
            "(?im)(?:^|\\s)(?:ssh|sftp)\\s+(?:-[A-Za-z0-9]+(?:\\s+\\d+)?\\s+)*" +
            "(?:[\\w.-]+@[\\w.-]+|localhost|[\\w-]+(?:\\.[\\w-]+)+)(?=\\s|$|:)");
    private static final Pattern SCP_COMMAND = Pattern.compile(
            "(?im)(?:^|\\s)scp\\s+(?:-[A-Za-z0-9]+\\s+)*(?:\\S+\\s+)*" +
            "(?:[\\w.-]+@[\\w.-]+|[\\w-]+(?:\\.[\\w-]+)+):\\S+");
    private static final Pattern FINANCE_CONTEXT = Pattern.compile(
            "(?:계좌번호|입금계좌|은행계좌|카드번호|카드 번호|송금내역|이체내역|account number|bank account|card number)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern IDENTITY_CONTEXT = Pattern.compile(
            "(?:주민등록번호|외국인등록번호|여권번호|운전면허번호|신분증번호|passport number|national id number)",
            Pattern.CASE_INSENSITIVE);

    private BrowseClassifier() {}

    /** A bitmask of candidate browsing types, not an authorization/secret score. */
    public static int additionalFlags(@NonNull String normalized) {
        int result = 0;
        if (KOREAN.matcher(normalized).find()) result |= NativeClassifier.KOREAN;
        String lower = normalized.toLowerCase(Locale.ROOT);
        boolean credentialKeyword = lower.contains("비밀번호") ||
                lower.contains("개인키") || lower.contains("비밀키") ||
                lower.contains("인증번호") || lower.contains("api token") ||
                lower.contains("api key") || lower.contains("access token");
        if (credentialKeyword || PRIVATE_KEY.matcher(normalized).find() ||
                SSH_PUBLIC_KEY.matcher(normalized).find() ||
                JWT.matcher(normalized).find() ||
                TOKEN_PREFIX.matcher(normalized).find() ||
                TOKENIZED_URL.matcher(normalized).find()) {
            result |= NativeClassifier.CREDENTIAL;
        }
        if (SSH_COMMAND.matcher(normalized).find() || SCP_COMMAND.matcher(normalized).find()) {
            result |= NativeClassifier.SSH_COMMAND;
        }
        if (FINANCE_CONTEXT.matcher(normalized).find()) result |= NativeClassifier.FINANCE;
        if (IDENTITY_CONTEXT.matcher(normalized).find()) result |= NativeClassifier.IDENTITY;
        return result;
    }
}
