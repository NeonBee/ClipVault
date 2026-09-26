package dev.clipvault.app.clipboard;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class ClipboardCaptureCoordinatorConcurrencyTest {
    @Test
    public void closeDropsEveryLaterCapture() {
        List<String> captured = new CopyOnWriteArrayList<>();
        ClipboardCaptureCoordinator coordinator = new ClipboardCaptureCoordinator(captured::add);
        assertTrue(coordinator.accept("before close"));
        coordinator.close();
        assertFalse(coordinator.accept("after close"));
        StaticBridge bridge = new StaticBridge("polled after close");
        coordinator.poll(bridge);
        assertEquals(List.of("before close"), captured);
    }

    /** close() must wait for a capture already in progress, then block every later one. */
    @Test
    public void closeWaitsForInFlightCaptureAndDropsTheRest() throws Exception {
        CountDownLatch captureEntered = new CountDownLatch(1);
        CountDownLatch releaseCapture = new CountDownLatch(1);
        List<String> captured = new CopyOnWriteArrayList<>();
        ClipboardCaptureCoordinator coordinator = new ClipboardCaptureCoordinator(text -> {
            captureEntered.countDown();
            await(releaseCapture);
            captured.add(text);
        });

        Thread inFlight = new Thread(() -> coordinator.accept("in flight"));
        inFlight.start();
        assertTrue(captureEntered.await(5, TimeUnit.SECONDS));

        CountDownLatch closed = new CountDownLatch(1);
        Thread closer = new Thread(() -> {
            coordinator.close();
            closed.countDown();
        });
        closer.start();
        assertFalse("close must wait for the in-flight capture", closed.await(200, TimeUnit.MILLISECONDS));

        releaseCapture.countDown();
        assertTrue(closed.await(5, TimeUnit.SECONDS));
        inFlight.join(5_000);
        assertFalse(coordinator.accept("late"));
        assertEquals(List.of("in flight"), captured);
    }

    /** The privileged Binder read happens outside the monitor, so accept()/close() never wait on it. */
    @Test
    public void pollReadsTheBridgeOutsideTheMonitor() throws Exception {
        CountDownLatch readEntered = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        List<String> captured = new CopyOnWriteArrayList<>();
        ClipboardCaptureCoordinator coordinator = new ClipboardCaptureCoordinator(captured::add);
        ClipboardBridge slowBridge = new ClipboardBridge() {
            @Override public boolean isReady() { return true; }
            @Override public boolean isEventDriven() { return false; }
            @Override public String readText() {
                readEntered.countDown();
                await(releaseRead);
                return "polled";
            }
        };

        Thread poller = new Thread(() -> coordinator.poll(slowBridge));
        poller.start();
        assertTrue(readEntered.await(5, TimeUnit.SECONDS));

        CountDownLatch closed = new CountDownLatch(1);
        Thread closer = new Thread(() -> {
            coordinator.close();
            closed.countDown();
        });
        closer.start();
        assertTrue("close must not wait for the Binder read", closed.await(2, TimeUnit.SECONDS));

        releaseRead.countDown();
        poller.join(5_000);
        // The read finished after close: its text is dropped.
        assertEquals(0, captured.size());
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class StaticBridge implements ClipboardBridge {
        private final String text;

        StaticBridge(String text) { this.text = text; }

        @Override public boolean isReady() { return true; }
        @Override public boolean isEventDriven() { return false; }
        @Override public String readText() { return text; }
    }
}
