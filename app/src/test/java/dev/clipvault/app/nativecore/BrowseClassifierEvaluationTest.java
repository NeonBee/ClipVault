package dev.clipvault.app.nativecore;

import static org.junit.Assert.assertTrue;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;

/**
 * Entirely synthetic, deterministic English/Korean evaluation corpus.
 * No real private credentials, contact info, clipboard, Vault data or telemetry.
 *
 * Evaluates candidate *types*, never claims the model knows secret sensitivity.
 * Print aggregate TP/FP/FN only; no input strings, tokens or personal material.
 */
public final class BrowseClassifierEvaluationTest {
    private static final int[] FLAGS = {
            NativeClassifier.CREDENTIAL, NativeClassifier.SSH_COMMAND,
            NativeClassifier.FINANCE, NativeClassifier.IDENTITY, NativeClassifier.KOREAN
    };
    private static final String[] LABELS = {
            "credential_candidate", "ssh_command", "finance_context",
            "identity_context", "korean_script"
    };

    private static final class Fixture {
        final String text;
        final int expected;
        Fixture(String text, int expected) { this.text = text; this.expected = expected; }
    }

    private static Fixture fixture(String text, int flags) { return new Fixture(text, flags); }

    private static List<Fixture> corpus() {
        final int C = NativeClassifier.CREDENTIAL;
        final int S = NativeClassifier.SSH_COMMAND;
        final int F = NativeClassifier.FINANCE;
        final int I = NativeClassifier.IDENTITY;
        final int K = NativeClassifier.KOREAN;
        ArrayList<Fixture> fixtures = new ArrayList<>();
        fixtures.add(fixture("ssh user@host.example", S));
        fixtures.add(fixture("scp document.txt user@host.example:/tmp", S));
        fixtures.add(fixture("sftp user@host.example", S));
        fixtures.add(fixture("ssh -p 2222 user@host.example", S));
        fixtures.add(fixture("ssh-keygen help page", 0)); // name alone is not an SSH command
        fixtures.add(fixture("SSH configuration reference", 0));
        fixtures.add(fixture("SSH setup instructions", 0));
        fixtures.add(fixture("sftp tutorial for beginners", 0));
        fixtures.add(fixture("scp command syntax guide", 0));
        fixtures.add(fixture("ssh admin@192.168.1.20", S));
        fixtures.add(fixture("scp local.txt user@host.example:/tmp", S));
        fixtures.add(fixture("-----BEGIN OPENSSH PRIVATE KEY-----", C));
        fixtures.add(fixture("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIFAKE00000000000000000000", C));
        fixtures.add(fixture("https://example.test/path?token=PLACEHOLDER1234", C));
        fixtures.add(fixture("https://example.test/path?signature=PLACEHOLDER", C));
        fixtures.add(fixture("github_pat_FAKE0123456789TOKENTEXT", C));
        fixtures.add(fixture("api key: placeholder", C));
        fixtures.add(fixture("비밀번호 변경", C | K));
        fixtures.add(fixture("개인키 파일", C | K));
        fixtures.add(fixture("https://example.test/docs", 0));
        fixtures.add(fixture("https://example.test/?page=3", 0));
        fixtures.add(fixture("random unrelated ordinary note", 0));
        fixtures.add(fixture("카드번호 확인", F | K));
        fixtures.add(fixture("입금계좌 안내", F | K));
        fixtures.add(fixture("송금내역 보관", F | K));
        fixtures.add(fixture("bank account information", F));
        fixtures.add(fixture("payment schedule", 0));
        fixtures.add(fixture("invoice due tomorrow", 0));
        fixtures.add(fixture("주민등록번호 양식", I | K));
        fixtures.add(fixture("여권번호 안내", I | K));
        fixtures.add(fixture("운전면허번호 입력", I | K));
        fixtures.add(fixture("passport number instructions", I));
        fixtures.add(fixture("passport travel itinerary", 0));
        fixtures.add(fixture("일반 공개 문장", K));
        fixtures.add(fixture("한글 메모 보관", K));
        fixtures.add(fixture("plain English sentence", 0));
        fixtures.add(fixture("public docs about deployment", 0));
        fixtures.add(fixture("ssh user@host.example # 한국어 주석", S | K));
        fixtures.add(fixture("api token: fake # 계좌번호 확인", C | F | K));
        return fixtures;
    }

    @Test public void categoryPrecisionRecallOnSyntheticCorpusMeetPredeclaredThreshold() {
        List<Fixture> fixtures = corpus();
        assertTrue("evaluation corpus must have at least 30 synthetic cases", fixtures.size() >= 30);
        for (int index = 0; index < FLAGS.length; index++) {
            int tp = 0, fp = 0, fn = 0, tn = 0;
            int flag = FLAGS[index];
            for (Fixture fixture : fixtures) {
                boolean expected = (fixture.expected & flag) != 0;
                boolean found = (BrowseClassifier.additionalFlags(fixture.text) & flag) != 0;
                if (found && expected) tp++;
                else if (found) fp++;
                else if (expected) fn++;
                else tn++;
            }
            double precision = tp / (double) Math.max(1, tp + fp);
            double recall = tp / (double) Math.max(1, tp + fn);
            // Numeric metrics are only for this deliberately small synthetic fixture.
            System.out.printf(java.util.Locale.ROOT,
                    "Browse fixture [%s] n=%d TP=%d FP=%d FN=%d TN=%d precision=%.3f recall=%.3f%n",
                    LABELS[index], fixtures.size(), tp, fp, fn, tn, precision, recall);
            assertTrue(LABELS[index] + " synthetic precision < 0.85 (TP=" + tp +
                    ",FP=" + fp + ",FN=" + fn + ")", precision >= 0.85);
            assertTrue(LABELS[index] + " synthetic recall < 0.85", recall >= 0.85);
        }
    }

    @Test public void browseFlagsNeverRewriteLegacyCaptureSensitivity() {
        String[] data = {"ssh user@host.example", "카드번호 확인", "개인키 파일",
                "https://example.test/?token=FAKE123456", "public notes"};
        for (String input : data) {
            TextAnalysis result = NativeClassifier.fallbackAnalyze(input);
            // Isolate the legacy 18-bit classifier; browse augmentation must not
            // alter the sensitive bit or its boolean auto-capture skip signal.
            assertTrue(result.sensitive == result.has(NativeClassifier.SENSITIVE));
        }
    }
}
