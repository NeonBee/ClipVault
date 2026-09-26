package dev.clipvault.app.clipboard;

import static dev.clipvault.app.clipboard.ClipboardBridgeProtocol.CAP_EVENT_LISTENER;
import static dev.clipvault.app.clipboard.ClipboardBridgeProtocol.CAP_PAYLOAD_LIMIT;
import static dev.clipvault.app.clipboard.ClipboardBridgeProtocol.CAP_POLL_FALLBACK;
import static dev.clipvault.app.clipboard.ClipboardBridgeProtocol.CAP_READ;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

public class BridgeHealthTest {
    @Test
    public void readAndListenerCapabilitiesSelectEventMode() {
        BridgeHealth health = BridgeHealth.fromProbe(
                CAP_READ | CAP_EVENT_LISTENER | CAP_POLL_FALLBACK | CAP_PAYLOAD_LIMIT, ClipboardBridgeProtocol.OK);
        assertEquals(BridgeHealth.State.READY_EVENT, health.state);
        assertTrue(health.isReady());
        assertTrue(health.isEventDriven());
        assertEquals("", health.diagnosticCode());
    }

    @Test
    public void missingListenerFallsBackToPolling() {
        BridgeHealth health = BridgeHealth.fromProbe(
                CAP_READ | CAP_POLL_FALLBACK, ClipboardBridgeProtocol.LISTENER_SIGNATURE_UNSUPPORTED);
        assertEquals(BridgeHealth.State.READY_POLL, health.state);
        assertFalse(health.isEventDriven());
        assertEquals(BridgeHealth.State.READY_POLL,
                BridgeHealth.fromProbe(CAP_READ | CAP_EVENT_LISTENER, 0).withoutEvents().state);
    }

    @Test
    public void binderWithoutWorkingReadIsDegradedNotReady() {
        BridgeHealth health = BridgeHealth.fromProbe(
                CAP_EVENT_LISTENER | CAP_PAYLOAD_LIMIT, ClipboardBridgeProtocol.GET_PRIMARY_CLIP_SIGNATURE_UNSUPPORTED);
        assertEquals(BridgeHealth.State.DEGRADED, health.state);
        assertFalse(health.isReady());
        assertEquals("DEGRADED:GET_PRIMARY_CLIP_SIGNATURE_UNSUPPORTED", health.diagnosticCode());
        assertEquals(ClipboardBridgeProtocol.INVOCATION_FAILED,
                BridgeHealth.fromProbe(0, ClipboardBridgeProtocol.OK).errorCode);
    }

    @Test
    public void oversizedClipKeepsTheBridgeReadyButIsReported() {
        BridgeHealth health = BridgeHealth.fromProbe(CAP_READ, ClipboardBridgeProtocol.OK)
                .withError(ClipboardBridgeProtocol.TRANSACTION_TOO_LARGE);
        assertTrue(health.isReady());
        assertEquals("CAPTURE_REJECTED_TOO_LARGE", health.diagnosticCode());
        assertFalse(ClipboardBridgeProtocol.isApiFailure(ClipboardBridgeProtocol.TRANSACTION_TOO_LARGE));
        assertTrue(ClipboardBridgeProtocol.isApiFailure(ClipboardBridgeProtocol.SECURITY_EXCEPTION));
        assertTrue(ClipboardBridgeProtocol.isApiFailure(ClipboardBridgeProtocol.GET_PRIMARY_CLIP_SIGNATURE_UNSUPPORTED));
        // A single failed invocation (odd ClipData, dead reply) is transient, not an API break.
        assertFalse(ClipboardBridgeProtocol.isApiFailure(ClipboardBridgeProtocol.INVOCATION_FAILED));
        assertTrue(ClipboardBridgeProtocol.isTransientReadFailure(ClipboardBridgeProtocol.INVOCATION_FAILED));
    }

    @Test
    public void preBindStatesHaveStableDiagnostics() {
        assertEquals("NO_SHIZUKU", BridgeHealth.of(BridgeHealth.State.NO_SHIZUKU).diagnosticCode());
        assertEquals("DEGRADED:BACKEND_NOT_SHELL",
                BridgeHealth.degraded(ClipboardBridgeProtocol.BACKEND_NOT_SHELL).diagnosticCode());
    }

    @Test
    public void protocolConstantsAreDistinct() {
        int[] capabilities = {CAP_READ, CAP_EVENT_LISTENER, CAP_POLL_FALLBACK,
                ClipboardBridgeProtocol.CAP_USER_SCOPED, CAP_PAYLOAD_LIMIT};
        int combined = 0;
        for (int capability : capabilities) {
            assertEquals(1, Integer.bitCount(capability));
            assertEquals(0, combined & capability);
            combined |= capability;
        }
        Set<String> names = new HashSet<>();
        for (int code = 0; code <= ClipboardBridgeProtocol.PROTOCOL_MISMATCH; code++) {
            assertTrue(names.add(ClipboardBridgeProtocol.errorName(code)));
        }
        assertEquals("UNKNOWN_99", ClipboardBridgeProtocol.errorName(99));
        assertEquals(10, ClipboardBridgeProtocol.userIdOf(1_010_123));
        assertEquals(0, ClipboardBridgeProtocol.userIdOf(ClipboardBridgeProtocol.SHELL_UID));
    }
}
