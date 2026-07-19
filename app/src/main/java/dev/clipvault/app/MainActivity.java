package dev.clipvault.app;

import android.Manifest;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.provider.Settings;
import android.security.keystore.KeyPermanentlyInvalidatedException;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;

import dev.clipvault.app.clipboard.ClipboardCaptureService;
import dev.clipvault.app.clipboard.ShizukuController;
import dev.clipvault.app.data.ClipRecord;
import dev.clipvault.app.data.RetentionPolicy;
import dev.clipvault.app.data.VaultRepository;
import dev.clipvault.app.nativecore.NativeClassifier;
import dev.clipvault.app.security.VaultKeyManager;
import dev.clipvault.app.ui.ClipAdapter;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.crypto.Cipher;

import rikka.shizuku.Shizuku;

public final class MainActivity extends AppCompatActivity implements ClipAdapter.Actions {
    private static final int SHIZUKU_PERMISSION_REQUEST = 42;
    private static final int NOTIFICATION_PERMISSION_REQUEST = 43;
    private static final long AUTO_LOCK_DELAY_MS = 30_000L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Runnable autoLock = this::lockVault;
    private final Runnable debouncedRefresh = this::refreshList;

    private ClipVaultApp app;
    private VaultKeyManager keyManager;
    private ClipAdapter adapter;
    private View lockedContainer;
    private View contentContainer;
    private View progressOverlay;
    private View emptyState;
    private TextView lockError;
    private TextView vaultCount;
    private TextView pendingCount;
    private TextView shizukuStatus;
    private TextView listTitle;
    private MaterialButton captureButton;
    private MaterialButton retentionButton;
    private TextInputEditText searchInput;
    private SwipeRefreshLayout swipeRefresh;
    private int selectedFlag;
    private boolean favoritesOnly;
    private String selectedLabel;
    private boolean enrolling;

    private final BroadcastReceiver dataChangedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (app.isUnlocked()) refreshList();
        }
    };

    private final Shizuku.OnBinderReceivedListener shizukuBinderListener = () ->
            mainHandler.post(this::updateCaptureState);
    private final Shizuku.OnBinderDeadListener shizukuDeadListener = () ->
            mainHandler.post(this::updateCaptureState);
    private final Shizuku.OnRequestPermissionResultListener shizukuPermissionListener =
            (requestCode, grantResult) -> {
                if (requestCode != SHIZUKU_PERMISSION_REQUEST) return;
                mainHandler.post(() -> {
                    updateCaptureState();
                    if (grantResult == PackageManager.PERMISSION_GRANTED) requestNotificationThenStartCapture();
                });
            };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setContentView(R.layout.activity_main);

        app = (ClipVaultApp) getApplication();
        keyManager = new VaultKeyManager(this);
        bindViews();
        configureList();
        configureCategories();
        configureActions();
        registerDataReceiver();
        registerShizukuListeners();
        showLocked(false);
        updateCaptureState();

        if (!app.settings().getBoolean(ClipVaultApp.PREF_ONBOARDED, false)) showOnboarding();
    }

    private void bindViews() {
        lockedContainer = findViewById(R.id.lockedContainer);
        contentContainer = findViewById(R.id.contentContainer);
        progressOverlay = findViewById(R.id.progressOverlay);
        emptyState = findViewById(R.id.emptyState);
        lockError = findViewById(R.id.lockError);
        vaultCount = findViewById(R.id.vaultCount);
        pendingCount = findViewById(R.id.pendingCount);
        shizukuStatus = findViewById(R.id.shizukuStatus);
        listTitle = findViewById(R.id.listTitle);
        captureButton = findViewById(R.id.captureButton);
        retentionButton = findViewById(R.id.retentionButton);
        searchInput = findViewById(R.id.searchInput);
        swipeRefresh = findViewById(R.id.swipeRefresh);
    }

    private void configureList() {
        RecyclerView list = findViewById(R.id.clipList);
        adapter = new ClipAdapter(this);
        list.setAdapter(adapter);
        list.setItemAnimator(new androidx.recyclerview.widget.DefaultItemAnimator());
        list.setHasFixedSize(false);
        swipeRefresh.setColorSchemeResources(R.color.primary, R.color.secondary);
        swipeRefresh.setOnRefreshListener(this::refreshList);
    }

    private void configureCategories() {
        ChipGroup group = findViewById(R.id.categoryGroup);
        List<FilterOption> filters = Arrays.asList(
                new FilterOption("✦  همه", 0, false),
                new FilterOption("◎  اینستاگرام", NativeClassifier.INSTAGRAM, false),
                new FilterOption("▶  یوتیوب", NativeClassifier.YOUTUBE, false),
                new FilterOption("↗  لینک‌ها", NativeClassifier.LINK, false),
                new FilterOption("◷  تاریخ‌ها", NativeClassifier.DATE, false),
                new FilterOption("فا  فارسی", NativeClassifier.PERSIAN, false),
                new FilterOption("EN  انگلیسی", NativeClassifier.ENGLISH, false),
                new FilterOption("≡  طولانی‌ها", NativeClassifier.LONG_TEXT, false),
                new FilterOption("★  محبوب‌ها", 0, true));
        int[][] states = {{android.R.attr.state_checked}, {}};
        int[] backgroundColors = {ContextCompat.getColor(this, R.color.primary), ContextCompat.getColor(this, R.color.surface)};
        int[] textColors = {Color.WHITE, ContextCompat.getColor(this, R.color.ink_muted)};
        for (int index = 0; index < filters.size(); index++) {
            FilterOption option = filters.get(index);
            Chip chip = new Chip(this);
            chip.setId(View.generateViewId());
            chip.setText(option.label);
            chip.setCheckable(true);
            chip.setCheckedIconVisible(false);
            chip.setChipCornerRadius(dp(15));
            chip.setChipMinHeight(dp(38));
            chip.setChipStrokeWidth(dp(1));
            chip.setChipStrokeColor(new ColorStateList(states, new int[]{
                    ContextCompat.getColor(this, R.color.primary), ContextCompat.getColor(this, R.color.line)}));
            chip.setChipBackgroundColor(new ColorStateList(states, backgroundColors));
            chip.setTextColor(new ColorStateList(states, textColors));
            chip.setTag(option);
            chip.setOnClickListener(view -> {
                FilterOption selected = (FilterOption) view.getTag();
                selectedFlag = selected.flag;
                favoritesOnly = selected.favorites;
                selectedLabel = selected.label.replaceFirst("^[^ ]+\\s+", "");
                listTitle.setText(selectedLabel);
                refreshList();
            });
            group.addView(chip);
            if (index == 0) {
                chip.setChecked(true);
                selectedLabel = getString(R.string.all_clips);
            }
        }
    }

    private void configureActions() {
        findViewById(R.id.unlockButton).setOnClickListener(view -> beginBiometricUnlock());
        findViewById(R.id.lockButton).setOnClickListener(view -> lockVault());
        findViewById(R.id.settingsButton).setOnClickListener(view -> showSecuritySettings());
        retentionButton.setOnClickListener(view -> showRetentionPicker());
        captureButton.setOnClickListener(view -> toggleCapture());
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                mainHandler.removeCallbacks(debouncedRefresh);
                mainHandler.postDelayed(debouncedRefresh, 220L);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
        updateRetentionLabel();
    }

    private void beginBiometricUnlock() {
        lockError.setVisibility(View.GONE);
        int availability = BiometricManager.from(this).canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG);
        if (availability != BiometricManager.BIOMETRIC_SUCCESS) {
            lockError.setText(R.string.biometric_unavailable);
            lockError.setVisibility(View.VISIBLE);
            if (availability == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED && Build.VERSION.SDK_INT >= 30) {
                Intent enroll = new Intent(Settings.ACTION_BIOMETRIC_ENROLL)
                        .putExtra(Settings.EXTRA_BIOMETRIC_AUTHENTICATORS_ALLOWED,
                                BiometricManager.Authenticators.BIOMETRIC_STRONG);
                try {
                    startActivity(enroll);
                } catch (RuntimeException ignored) {
                    startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
                }
            }
            return;
        }

        try {
            enrolling = !keyManager.isProvisioned();
            Cipher cipher = enrolling ? keyManager.createEnrollmentCipher() : keyManager.createUnlockCipher();
            BiometricPrompt prompt = new BiometricPrompt(this, ContextCompat.getMainExecutor(this),
                    new BiometricPrompt.AuthenticationCallback() {
                        @Override
                        public void onAuthenticationError(int errorCode, @NonNull CharSequence errorString) {
                            lockError.setText(errorString);
                            lockError.setVisibility(View.VISIBLE);
                        }

                        @Override
                        public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                            BiometricPrompt.CryptoObject object = result.getCryptoObject();
                            if (object == null || object.getCipher() == null) {
                                showLockError("خطای امنیتی: CryptoObject در دسترس نیست.");
                                return;
                            }
                            finishBiometricUnlock(object.getCipher(), enrolling);
                        }

                        @Override
                        public void onAuthenticationFailed() {
                            showLockError("اثر انگشت تأیید نشد؛ دوباره تلاش کن.");
                        }
                    });
            BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                    .setTitle(getString(R.string.unlock_prompt_title))
                    .setSubtitle(getString(R.string.unlock_prompt_subtitle))
                    .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                    .setNegativeButtonText(getString(R.string.unlock_prompt_cancel))
                    .setConfirmationRequired(false)
                    .build();
            prompt.authenticate(info, new BiometricPrompt.CryptoObject(cipher));
        } catch (KeyPermanentlyInvalidatedException error) {
            showLockError(getString(R.string.key_invalidated));
        } catch (GeneralSecurityException | RuntimeException error) {
            showLockError("راه‌اندازی امن ناموفق بود: " + safeMessage(error));
        }
    }

    private void finishBiometricUnlock(@NonNull Cipher cipher, boolean wasEnrollment) {
        progressOverlay.setVisibility(View.VISIBLE);
        app.io().execute(() -> {
            byte[] databaseKey = null;
            try {
                databaseKey = wasEnrollment
                        ? keyManager.finishEnrollment(cipher)
                        : keyManager.finishUnlock(cipher);
                app.openVault(databaseKey);
                databaseKey = null; // Ownership was transferred and the app wiped it.
                runOnUiThread(() -> {
                    progressOverlay.setVisibility(View.GONE);
                    showUnlocked();
                    refreshList();
                    restartCaptureIfEnabled();
                });
            } catch (GeneralSecurityException | RuntimeException error) {
                if (databaseKey != null) Arrays.fill(databaseKey, (byte) 0);
                runOnUiThread(() -> {
                    progressOverlay.setVisibility(View.GONE);
                    showLockError("باز کردن دیتابیس ناموفق بود: " + safeMessage(error));
                });
            }
        });
    }

    private void showUnlocked() {
        lockedContainer.animate().alpha(0f).setDuration(160).withEndAction(() -> {
            lockedContainer.setVisibility(View.GONE);
            contentContainer.setAlpha(0f);
            contentContainer.setTranslationY(dp(12));
            contentContainer.setVisibility(View.VISIBLE);
            contentContainer.animate().alpha(1f).translationY(0f).setDuration(260)
                    .setInterpolator(new DecelerateInterpolator()).start();
        }).start();
    }

    private void showLocked(boolean animate) {
        adapter.submitList(new ArrayList<>());
        contentContainer.setVisibility(View.GONE);
        lockedContainer.setVisibility(View.VISIBLE);
        lockedContainer.setAlpha(animate ? 0f : 1f);
        if (animate) lockedContainer.animate().alpha(1f).setDuration(180).start();
    }

    private void lockVault() {
        mainHandler.removeCallbacks(autoLock);
        if (!app.isUnlocked()) return;
        app.lockVault();
        showLocked(true);
    }

    private void refreshList() {
        if (!app.isUnlocked()) {
            swipeRefresh.setRefreshing(false);
            return;
        }
        String query = searchInput.getText() == null ? "" : searchInput.getText().toString().trim();
        int flag = selectedFlag;
        boolean favoriteFilter = favoritesOnly;
        app.io().execute(() -> {
            VaultRepository repository = app.repository();
            if (repository == null) return;
            List<ClipRecord> records = repository.query(query, flag, favoriteFilter, 1_000);
            int count = repository.count();
            int staged = app.pending().count();
            runOnUiThread(() -> {
                if (!app.isUnlocked()) return;
                adapter.submitList(records);
                emptyState.setVisibility(records.isEmpty() ? View.VISIBLE : View.GONE);
                vaultCount.setText(getString(R.string.vault_count_format, count));
                pendingCount.setText(getString(R.string.pending_import_format, staged));
                pendingCount.setVisibility(staged > 0 ? View.VISIBLE : View.GONE);
                swipeRefresh.setRefreshing(false);
            });
        });
    }

    private void toggleCapture() {
        boolean enabled = app.settings().getBoolean(ClipVaultApp.PREF_CAPTURE_ENABLED, false);
        if (enabled) {
            stopService(new Intent(this, ClipboardCaptureService.class).setAction(ClipboardCaptureService.ACTION_STOP));
            app.settings().edit().putBoolean(ClipVaultApp.PREF_CAPTURE_ENABLED, false).apply();
            updateCaptureState();
            return;
        }
        if (!ShizukuController.isBinderRunning()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Shizuku آماده نیست")
                    .setMessage("Shizuku را نصب و با Wireless debugging یا روت اجرا کن. بدون آن، اندروید ۱۰ به بعد اجازهٔ خواندن دائمی کلیپ‌بورد در پس‌زمینه را نمی‌دهد.")
                    .setNegativeButton(R.string.cancel, null)
                    .setPositiveButton("راهنمای Shizuku", (dialog, which) ->
                            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/guide/setup/"))))
                    .show();
            return;
        }
        if (!ShizukuController.hasPermission()) {
            try {
                Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST);
            } catch (RuntimeException error) {
                Toast.makeText(this, R.string.capture_permission_needed, Toast.LENGTH_LONG).show();
            }
            return;
        }
        requestNotificationThenStartCapture();
    }

    private void requestNotificationThenStartCapture() {
        if (Build.VERSION.SDK_INT >= 33 && ActivityCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_REQUEST);
        } else {
            startCaptureService();
        }
    }

    private void startCaptureService() {
        ContextCompat.startForegroundService(this,
                new Intent(this, ClipboardCaptureService.class).setAction(ClipboardCaptureService.ACTION_START));
        app.settings().edit().putBoolean(ClipVaultApp.PREF_CAPTURE_ENABLED, true).apply();
        updateCaptureState();
    }

    private void restartCaptureIfEnabled() {
        if (app.settings().getBoolean(ClipVaultApp.PREF_CAPTURE_ENABLED, false) &&
                ShizukuController.hasPermission()) {
            startCaptureService();
        }
    }

    private void updateCaptureState() {
        boolean ready = ShizukuController.hasPermission();
        boolean enabled = app.settings().getBoolean(ClipVaultApp.PREF_CAPTURE_ENABLED, false);
        shizukuStatus.setText(ready ? R.string.shizuku_ready : R.string.shizuku_offline);
        captureButton.setText(enabled ? R.string.disable_capture : R.string.enable_capture);
    }

    private void showRetentionPicker() {
        String[] labels = {
                getString(R.string.retention_one_month), getString(R.string.retention_three_months),
                getString(R.string.retention_six_months), getString(R.string.retention_forever)};
        int[] values = {
                RetentionPolicy.ONE_MONTH, RetentionPolicy.THREE_MONTHS,
                RetentionPolicy.SIX_MONTHS, RetentionPolicy.FOREVER};
        int current = app.settings().getInt(ClipVaultApp.PREF_RETENTION_MONTHS, RetentionPolicy.FOREVER);
        int checked = 3;
        for (int index = 0; index < values.length; index++) if (values[index] == current) checked = index;
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.retention)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    app.settings().edit().putInt(ClipVaultApp.PREF_RETENTION_MONTHS, values[which]).apply();
                    app.applyRetentionNow();
                    updateRetentionLabel();
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void updateRetentionLabel() {
        int months = app.settings().getInt(ClipVaultApp.PREF_RETENTION_MONTHS, RetentionPolicy.FOREVER);
        int label = months == RetentionPolicy.ONE_MONTH ? R.string.retention_one_month
                : months == RetentionPolicy.THREE_MONTHS ? R.string.retention_three_months
                : months == RetentionPolicy.SIX_MONTHS ? R.string.retention_six_months
                : R.string.retention_forever;
        retentionButton.setText(label);
    }

    private void showSecuritySettings() {
        boolean[] checked = {app.settings().getBoolean(ClipVaultApp.PREF_SKIP_SENSITIVE, true)};
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.security_settings_title)
                .setMessage(R.string.auto_lock_description)
                .setMultiChoiceItems(new String[]{getString(R.string.sensitive_skip)}, checked,
                        (dialog, which, isChecked) -> checked[0] = isChecked)
                .setPositiveButton("ذخیره", (dialog, which) -> app.settings().edit()
                        .putBoolean(ClipVaultApp.PREF_SKIP_SENSITIVE, checked[0]).apply())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showOnboarding() {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.onboarding_title)
                .setMessage(R.string.onboarding_body)
                .setCancelable(false)
                .setPositiveButton(R.string.onboarding_action, (dialog, which) -> {
                    app.settings().edit().putBoolean(ClipVaultApp.PREF_ONBOARDED, true).apply();
                    beginBiometricUnlock();
                })
                .show();
    }

    @Override
    public void onCopy(@NonNull ClipRecord record) {
        ClipboardManager manager = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("ClipVault", record.content);
        PersistableBundle extras = new PersistableBundle();
        extras.putBoolean(Build.VERSION.SDK_INT >= 33
                ? ClipDescription.EXTRA_IS_SENSITIVE : "android.content.extra.IS_SENSITIVE", true);
        clip.getDescription().setExtras(extras);
        manager.setPrimaryClip(clip);
        Toast.makeText(this, R.string.clipboard_copied, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onFavorite(@NonNull ClipRecord record) {
        app.io().execute(() -> {
            VaultRepository repository = app.repository();
            if (repository != null) repository.setFavorite(record.id, !record.favorite);
            runOnUiThread(this::refreshList);
        });
    }

    @Override
    public void onDelete(@NonNull ClipRecord record) {
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.delete_confirm_title)
                .setMessage(R.string.delete_confirm_body)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.confirm_delete, (dialog, which) -> app.io().execute(() -> {
                    VaultRepository repository = app.repository();
                    if (repository != null) repository.delete(record.id);
                    runOnUiThread(this::refreshList);
                }))
                .show();
    }

    private void registerDataReceiver() {
        IntentFilter filter = new IntentFilter(ClipVaultApp.ACTION_DATA_CHANGED);
        ContextCompat.registerReceiver(
                this, dataChangedReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private void registerShizukuListeners() {
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderListener);
        Shizuku.addBinderDeadListener(shizukuDeadListener);
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) startCaptureService();
    }

    @Override
    protected void onStart() {
        super.onStart();
        mainHandler.removeCallbacks(autoLock);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateCaptureState();
        if (app.isUnlocked()) refreshList();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (!isChangingConfigurations() && app.isUnlocked()) mainHandler.postDelayed(autoLock, AUTO_LOCK_DELAY_MS);
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        unregisterReceiver(dataChangedReceiver);
        Shizuku.removeBinderReceivedListener(shizukuBinderListener);
        Shizuku.removeBinderDeadListener(shizukuDeadListener);
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener);
        super.onDestroy();
    }

    private void showLockError(@NonNull String message) {
        lockError.setText(message);
        lockError.setVisibility(View.VISIBLE);
    }

    @NonNull
    private static String safeMessage(@NonNull Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private float dp(int value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static final class FilterOption {
        final String label;
        final int flag;
        final boolean favorites;

        FilterOption(String label, int flag, boolean favorites) {
            this.label = label;
            this.flag = flag;
            this.favorites = favorites;
        }
    }
}
