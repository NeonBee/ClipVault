package dev.clipvault.app.clipboard;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class ClipboardBridgeContractTest {
    @Test
    public void fakeBridgeExercisesDeduplicationAndAdaptivePolling() {
        List<String> captured = new ArrayList<>();
        ClipboardCaptureCoordinator coordinator = new ClipboardCaptureCoordinator(captured::add);
        FakeBridge bridge = new FakeBridge(true, false, "first");

        assertEquals(500L, coordinator.poll(bridge));
        assertEquals(1, captured.size());
        assertEquals(1_000L, coordinator.poll(bridge));
        assertEquals(1, captured.size());

        bridge.text = "second";
        assertEquals(500L, coordinator.poll(bridge));
        assertEquals(2, captured.size());

        bridge.eventDriven = true;
        assertEquals(30_000L, coordinator.poll(bridge));
    }

    @Test
    public void fakeDisconnectedBridgeBacksOffWithoutCapturing() {
        List<String> captured = new ArrayList<>();
        ClipboardCaptureCoordinator coordinator = new ClipboardCaptureCoordinator(captured::add);
        FakeBridge bridge = new FakeBridge(false, false, null);
        assertEquals(5_000L, coordinator.poll(bridge));
        assertEquals(10_000L, coordinator.poll(bridge));
        assertEquals(0, captured.size());
    }

    private static final class FakeBridge implements ClipboardBridge {
        boolean ready;
        boolean eventDriven;
        String text;

        FakeBridge(boolean ready, boolean eventDriven, String text) {
            this.ready = ready;
            this.eventDriven = eventDriven;
            this.text = text;
        }

        @Override public boolean isReady() { return ready; }
        @Override public boolean isEventDriven() { return eventDriven; }
        @Override public String readText() { return text; }
    }
}
