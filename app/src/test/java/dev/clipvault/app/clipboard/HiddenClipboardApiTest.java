package dev.clipvault.app.clipboard;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class HiddenClipboardApiTest {
    /** Stand-ins: resolution matches on simple names, like the framework types. */
    static final class ClipData {}
    interface IOnPrimaryClipChangedListener {}

    interface Api34 {
        ClipData getPrimaryClip(String pkg, String attributionTag, int userId, int deviceId);
        void addPrimaryClipChangedListener(IOnPrimaryClipChangedListener listener, String callingPackage,
                                           String attributionTag, int userId, int deviceId);
    }

    interface Api33Late {
        ClipData getPrimaryClip(String pkg, String attributionTag, int userId);
        void addPrimaryClipChangedListener(IOnPrimaryClipChangedListener listener, String callingPackage,
                                           String attributionTag, int userId);
    }

    interface Api29 {
        ClipData getPrimaryClip(String pkg, int userId);
        void addPrimaryClipChangedListener(IOnPrimaryClipChangedListener listener, String callingPackage, int userId);
    }

    interface Api24 {
        ClipData getPrimaryClip(String pkg);
        void addPrimaryClipChangedListener(IOnPrimaryClipChangedListener listener, String callingPackage);
    }

    /** A vendor adds same-name overloads next to the AOSP one. */
    interface VendorOverloaded {
        ClipData getPrimaryClip(String pkg, int userId, int vendorFlags);
        ClipData getPrimaryClip(String pkg, String attributionTag, int userId, int deviceId);
        ClipData getPrimaryClip(int userId, String pkg);
    }

    /** Only unknown shapes: the old name-only heuristic would have guessed arguments here. */
    interface UnknownOnly {
        ClipData getPrimaryClip(int userId, String pkg);
        ClipData getPrimaryClip(String pkg, int deviceId, int userId, int extra);
        void addPrimaryClipChangedListener(IOnPrimaryClipChangedListener listener, int userId);
    }

    interface WrongReturnType {
        Object getPrimaryClip(String pkg, int userId);
    }

    @Test
    public void resolvesEachAospGenerationToItsExactSignature() {
        assertEquals("api34+", readLabel(Api34.class));
        assertEquals("api33-late", readLabel(Api33Late.class));
        assertEquals("api29-33", readLabel(Api29.class));
        assertEquals("api24-28", readLabel(Api24.class));

        assertEquals("api34+", listenerLabel(Api34.class));
        assertEquals("api33-late", listenerLabel(Api33Late.class));
        assertEquals("api29-33", listenerLabel(Api29.class));
        assertEquals("api24-28", listenerLabel(Api24.class));
    }

    @Test
    public void vendorOverloadsDoNotChangeTheSelectedAospSignature() {
        HiddenClipboardApi.Binding binding =
                HiddenClipboardApi.resolve(VendorOverloaded.class, HiddenClipboardApi.READ_SIGNATURES);
        assertNotNull(binding);
        assertEquals("api34+", binding.signature.label);
        assertEquals(4, binding.method.getParameterCount());
    }

    @Test
    public void unknownSignaturesFailClosed() {
        assertNull(HiddenClipboardApi.resolve(UnknownOnly.class, HiddenClipboardApi.READ_SIGNATURES));
        assertNull(HiddenClipboardApi.resolve(UnknownOnly.class, HiddenClipboardApi.LISTENER_SIGNATURES));
        assertNull(HiddenClipboardApi.resolve(WrongReturnType.class, HiddenClipboardApi.READ_SIGNATURES));
        assertNull(HiddenClipboardApi.resolve(VendorOverloaded.class, HiddenClipboardApi.LISTENER_SIGNATURES));
    }

    @Test
    public void argumentsFollowTheResolvedParameterRoles() {
        Object listener = new Object();
        assertArrayEquals(new Object[]{"com.android.shell", null, 10, 0},
                read(Api34.class).arguments(10, null));
        assertArrayEquals(new Object[]{"com.android.shell", null, 10},
                read(Api33Late.class).arguments(10, null));
        assertArrayEquals(new Object[]{"com.android.shell", 10},
                read(Api29.class).arguments(10, null));
        assertArrayEquals(new Object[]{"com.android.shell"},
                read(Api24.class).arguments(10, null));
        assertArrayEquals(new Object[]{listener, "com.android.shell", null, 10, 0},
                listener(Api34.class).arguments(10, listener));
        assertArrayEquals(new Object[]{listener, "com.android.shell", 0},
                listener(Api29.class).arguments(0, listener));
    }

    @Test
    public void userScopingIsReportedPerSignature() {
        assertTrue(read(Api34.class).isUserScoped());
        assertTrue(read(Api29.class).isUserScoped());
        assertFalse(read(Api24.class).isUserScoped());
        assertFalse(listener(Api24.class).isUserScoped());
    }

    @Test
    public void invokePassesExactArgumentsToTheImplementation() throws Exception {
        List<Object> calls = new ArrayList<>();
        Api29 implementation = new Api29() {
            @Override public ClipData getPrimaryClip(String pkg, int userId) {
                calls.add(pkg);
                calls.add(userId);
                return new ClipData();
            }

            @Override public void addPrimaryClipChangedListener(
                    IOnPrimaryClipChangedListener listener, String callingPackage, int userId) {}
        };
        // Resolve on the implementation class, as the service does with the IClipboard proxy.
        HiddenClipboardApi.Binding binding =
                HiddenClipboardApi.resolve(implementation.getClass(), HiddenClipboardApi.READ_SIGNATURES);
        assertNotNull(binding);
        assertTrue(binding.invoke(implementation, 10, null) instanceof ClipData);
        assertEquals(List.of("com.android.shell", 10), calls);
    }

    private static HiddenClipboardApi.Binding read(Class<?> api) {
        HiddenClipboardApi.Binding binding = HiddenClipboardApi.resolve(api, HiddenClipboardApi.READ_SIGNATURES);
        assertNotNull(binding);
        return binding;
    }

    private static HiddenClipboardApi.Binding listener(Class<?> api) {
        HiddenClipboardApi.Binding binding = HiddenClipboardApi.resolve(api, HiddenClipboardApi.LISTENER_SIGNATURES);
        assertNotNull(binding);
        return binding;
    }

    private static String readLabel(Class<?> api) {
        return read(api).signature.label;
    }

    private static String listenerLabel(Class<?> api) {
        return listener(api).signature.label;
    }
}
