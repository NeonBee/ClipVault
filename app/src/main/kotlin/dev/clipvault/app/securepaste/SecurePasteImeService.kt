package dev.clipvault.app.securepaste

import android.app.KeyguardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.widget.ImageView
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.clipvault.app.ClipVaultApp
import dev.clipvault.app.R
import dev.clipvault.app.data.ClipItem
import dev.clipvault.app.data.ClipQuery

/**
 * Opt-in, retrieval-only secondary IME. No keylogging, generic typing, clipboard writes,
 * accessibility or background capture. Samsung Keyboard remains the user's normal keyboard.
 *
 * Decrypted results live in RAM only while an authenticated editor session is active. Every
 * query and tap is checked against the vault lock epoch and the current editor session.
 */
class SecurePasteImeService : InputMethodService() {
    private lateinit var app: ClipVaultApp
    private val main = Handler(Looper.getMainLooper())
    private var rows: LinearLayout? = null
    private var search: EditText? = null
    private var status: TextView? = null
    private var unlockButton: TextView? = null
    private var searchClear: TextView? = null
    private var recentTab: TextView? = null
    private var pinnedTab: TextView? = null
    private var results: List<ClipItem> = emptyList()
    private var activePackage: String? = null
    private var sessionEpoch = -1L
    private var generation = 0L
    private var pinnedOnly = false
    private var suppressSearch = false
    private var inserting = false
    private var deferredSearch: Runnable? = null
    private var imeWindowVisible = false

    private val lockListener = Runnable {
        clearSensitiveState()
        status?.setText(R.string.secure_paste_locked)
        unlockButton?.visibility = View.VISIBLE
    }

    override fun onCreate() {
        super.onCreate()
        app = application as ClipVaultApp
        app.addLockListener(lockListener)
        window?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    // A DeX hardware keyboard is not a reason to hide this retrieval-only IME surface.
    override fun onEvaluateInputViewShown(): Boolean {
        // Respect InputMethodService's required lifecycle evaluation even when a physical
        // keyboard is present; this retrieval-only panel is intentionally always visible.
        super.onEvaluateInputViewShown()
        return true
    }

    override fun onCreateInputView(): View {
        // A native, dependency-free secure panel. Keep it compact for phone keyboards and
        // bounded in width on DeX; the active editor still owns all ordinary typing.
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // Preserve editor-side hardware typing until the user explicitly taps Search.
            isFocusableInTouchMode = true
            // One UI's bottom-left keyboard switcher can live inside the gesture-nav area.
            // Keep the last tappable result clear of that zone even when nav bar insets are 0.
            setPadding(dp(12), dp(10), dp(12), dp(28))
            setBackgroundColor(ui.background)
        }
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val panelWidth = if (resources.configuration.screenWidthDp >= 680) {
            dp(620)
        } else LinearLayout.LayoutParams.MATCH_PARENT
        root.addView(panel, LinearLayout.LayoutParams(panelWidth, LinearLayout.LayoutParams.WRAP_CONTENT))

        // Identity + a clearly labeled return path to the regular keyboard.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(ImageView(this).apply {
            setImageResource(android.R.drawable.ic_lock_lock)
            imageTintList = ColorStateList.valueOf(ui.accent)
            background = shape(ui.accentSurface, 13)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            contentDescription = getString(R.string.secure_paste_ime_label)
        }, LinearLayout.LayoutParams(dp(42), dp(42)))
        val identity = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        identity.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 17f
            setTextColor(ui.text)
            typeface = Typeface.DEFAULT_BOLD
        })
        identity.addView(TextView(this).apply {
            setText(R.string.secure_paste_panel_subtitle)
            textSize = 10f
            letterSpacing = 0.12f
            setTextColor(ui.accent)
        })
        header.addView(identity, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = dp(10)
        })
        header.addView(actionText(getString(R.string.secure_paste_switch_keyboard), ui.text,
            ui.surface, ui.outline).apply {
            contentDescription = getString(R.string.secure_paste_switch_keyboard)
            setOnClickListener { showPicker() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(40)))
        panel.addView(header)

        val footer = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(2), dp(8), dp(2), 0)
        }
        footer.addView(ImageView(this).apply {
            setImageResource(android.R.drawable.ic_lock_lock)
            imageTintList = ColorStateList.valueOf(ui.accent)
            contentDescription = null
        }, LinearLayout.LayoutParams(dp(14), dp(14)))
        footer.addView(TextView(this).apply {
            setText(R.string.secure_paste_private_footer)
            textSize = 11f
            setTextColor(ui.secondary)
            setPadding(dp(7), 0, 0, 0)
            maxLines = 2
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        panel.addView(footer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        status = TextView(this).apply {
            setTextColor(ui.secondary)
            textSize = 12f
            maxLines = 2
            setPadding(dp(2), dp(9), dp(2), dp(7))
            setText(R.string.secure_paste_locked)
        }.also { panel.addView(it) }

        unlockButton = actionText(getString(R.string.secure_paste_unlock), ui.buttonText,
            ui.accentButton, null).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            minHeight = dp(44)
            setOnClickListener { openUnlockActivity() }
        }.also { panel.addView(it, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(44)).apply { bottomMargin = dp(8) }) }

        // Search is local to the IME, never an editor-side text operation or clipboard read.
        val searchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = shape(ui.surface, 14, ui.outline)
            setPadding(dp(12), 0, dp(8), 0)
            minimumHeight = dp(46)
        }
        searchRow.addView(ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_search)
            imageTintList = ColorStateList.valueOf(ui.secondary)
            contentDescription = null // decorative: hint labels the editable search field
        }, LinearLayout.LayoutParams(dp(20), dp(20)))
        search = EditText(this).apply {
            hint = getString(R.string.secure_paste_search_hint)
            setHintTextColor(ui.secondary)
            setTextColor(ui.text)
            textSize = 14f
            setSingleLine(true)
            setShowSoftInputOnFocus(false) // Hardware search PoC, not a replacement typing IME.
            setSaveEnabled(false)
            isLongClickable = false
            background = null
            setPadding(dp(9), 0, dp(6), 0)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    searchClear?.visibility = if (s.isNullOrEmpty()) View.GONE else View.VISIBLE
                    if (suppressSearch) return
                    deferredSearch?.let(main::removeCallbacks)
                    val work = Runnable { refresh() }
                    deferredSearch = work
                    main.postDelayed(work, 150L)
                }
            })
        }.also { searchRow.addView(it, LinearLayout.LayoutParams(0, dp(46), 1f)) }
        searchClear = actionText("×", ui.secondary, ui.surface, null).apply {
            textSize = 22f
            contentDescription = getString(R.string.secure_paste_clear_search)
            visibility = View.GONE
            setOnClickListener { search?.text?.clear() }
        }.also { searchRow.addView(it, LinearLayout.LayoutParams(dp(35), dp(36))) }
        panel.addView(searchRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(46)).apply { bottomMargin = dp(9) })

        // Recent/Pinned are separate visible states rather than one ambiguous toggle button.
        val filters = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        recentTab = actionText(getString(R.string.secure_paste_recent), ui.text,
            ui.accentSurface, null).apply {
            setOnClickListener {
                if (pinnedOnly) {
                    pinnedOnly = false
                    updateFilters()
                    refresh()
                }
            }
        }.also { filters.addView(it, LinearLayout.LayoutParams(dp(94), dp(36))) }
        pinnedTab = actionText(getString(R.string.secure_paste_pinned), ui.secondary,
            ui.surface, ui.outline).apply {
            setOnClickListener {
                if (!pinnedOnly) {
                    pinnedOnly = true
                    updateFilters()
                    refresh()
                }
            }
        }.also { filters.addView(it, LinearLayout.LayoutParams(dp(94), dp(36)).apply {
            marginStart = dp(7)
        }) }
        filters.addView(View(this), LinearLayout.LayoutParams(0, dp(1), 1f))
        filters.addView(ImageView(this).apply {
            setImageResource(android.R.drawable.ic_menu_rotate)
            imageTintList = ColorStateList.valueOf(ui.secondary)
            background = touchShape(ui.surface, 12, ui.outline)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            contentDescription = getString(R.string.secure_paste_refresh)
            isFocusable = true
            setOnClickListener { refresh() }
        }, LinearLayout.LayoutParams(dp(38), dp(38)))
        panel.addView(filters, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(38)).apply { bottomMargin = dp(7) })

        rows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(3))
        }
        val listHeight = (resources.configuration.screenHeightDp / 3).coerceIn(128, 210)
        panel.addView(ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(rows)
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(listHeight)))

        updateFilters()
        return root
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        clearSensitiveState()
        activePackage = attribute?.packageName
        sessionEpoch = app.lockEpoch()
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        activePackage = info?.packageName
        sessionEpoch = app.lockEpoch()
        refresh()
    }

    override fun onWindowShown() {
        super.onWindowShown()
        // Unlike an Activity, the IME does not contribute to ActivityLifecycleCallbacks.
        // A visible user-selected panel is foreground Vault UI: pause only the background
        // timeout, not explicit / screen-off locks. The callback may be repeated on One UI.
        updateImeVisibility(true)
        // After the biometric Activity finishes Android may re-show the same input view.
        refresh()
    }

    override fun onWindowHidden() {
        clearSensitiveState()
        updateImeVisibility(false)
        super.onWindowHidden()
    }

    private fun updateImeVisibility(visible: Boolean) {
        if (imeWindowVisible == visible) return
        imeWindowVisible = visible
        app.onSecureImeVisibilityChanged(visible)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        clearSensitiveState()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        clearSensitiveState()
        activePackage = null
        super.onFinishInput()
    }

    override fun onDestroy() {
        // onWindowHidden is not guaranteed before process/service teardown.
        updateImeVisibility(false)
        app.removeLockListener(lockListener)
        clearSensitiveState()
        rows = null
        search = null
        status = null
        unlockButton = null
        searchClear = null
        recentTab = null
        pinnedTab = null
        super.onDestroy()
    }

    private fun refresh() {
        deferredSearch?.let(main::removeCallbacks)
        deferredSearch = null
        val epoch = app.lockEpoch()
        val info = currentInputEditorInfo
        val isLocked = !app.isUnlocked || deviceLocked()
        val editorOk = info != null && SecurePasteTransport.permitted(
            app.isUnlocked, epoch, epoch, deviceLocked(), info.packageName,
            activePackage, info.inputType, false, packageName,
        )
        if (isLocked || !editorOk) {
            clearResultsOnly()
            search?.isEnabled = false
            unlockButton?.visibility = if (isLocked) View.VISIBLE else View.GONE
            val message = if (isLocked) R.string.secure_paste_locked
                else R.string.secure_paste_editor_unavailable
            status?.setText(message)
            showPlaceholder(message)
            return
        }
        search?.isEnabled = true
        unlockButton?.visibility = View.GONE
        status?.setText(R.string.secure_paste_loading)
        clearResultsOnly()
        sessionEpoch = epoch
        val version = ++generation
        val editor = activePackage
        val query = ClipQuery.builder()
            .search(search?.text?.toString().orEmpty())
            .pinnedOnly(pinnedOnly)
            .page(30, 0)
            .build()
        // Use the same serialized executor as vault open/close and write operations.
        app.io().execute {
            val data = runCatching { app.repository()?.query(query)?.items }.getOrNull()
            main.post {
                if (version != generation || !app.isUnlocked || app.lockEpoch() != epoch ||
                    activePackage != editor || deviceLocked()
                ) return@post
                if (data == null) {
                    status?.setText(R.string.secure_paste_failed)
                    showPlaceholder(R.string.secure_paste_failed)
                } else {
                    results = data
                    renderResults()
                }
            }
        }
    }

    private fun renderResults() {
        rows?.removeAllViews()
        if (results.isEmpty()) {
            status?.setText(R.string.secure_paste_empty)
            showPlaceholder(R.string.secure_paste_empty)
            return
        }
        status?.text = getString(R.string.secure_paste_count, results.size)
        results.forEachIndexed { index, clip ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(13), dp(10), dp(12), dp(10))
                background = touchShape(ui.surface, 14, ui.outline)
                isFocusable = true
                isClickable = true
                contentDescription = getString(R.string.secure_paste_insert_item)
                setOnClickListener { insert(index) }
            }
            val badge = TextView(this).apply {
                text = if (clip.pinned) "★" else "▤"
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(ui.accent)
                background = shape(ui.accentSurface, 10)
            }
            card.addView(badge, LinearLayout.LayoutParams(dp(36), dp(36)))
            val details = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val firstLine = clip.content.lineSequence().firstOrNull().orEmpty().trim()
            val name = clip.title.ifBlank { firstLine }.ifBlank {
                getString(R.string.secure_paste_ime_label)
            }.replace('\n', ' ').replace('\r', ' ').take(96)
            details.addView(TextView(this).apply {
                text = name
                setTextColor(ui.text)
                textSize = 13.5f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            val preview = clip.content.replace('\n', ' ').replace('\r', ' ').trim().take(150)
            if (preview.isNotBlank() && preview != name) {
                details.addView(TextView(this).apply {
                    text = preview
                    setTextColor(ui.secondary)
                    textSize = 11.5f
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, dp(3), 0, 0)
                })
            }
            card.addView(details, LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(11)
            })
            card.addView(TextView(this).apply {
                text = "↗"
                textSize = 20f
                setTextColor(ui.secondary)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(dp(28), dp(34)))
            rows?.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(6) })
        }
    }

    private fun showPlaceholder(message: Int) {
        rows?.removeAllViews()
        rows?.addView(TextView(this).apply {
            setText(message)
            setTextColor(ui.secondary)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(dp(18), dp(38), dp(18), dp(32))
            background = shape(ui.surface, 14, ui.outline)
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun updateFilters() {
        recentTab?.apply {
            setTextColor(if (!pinnedOnly) ui.accent else ui.secondary)
            background = touchShape(if (!pinnedOnly) ui.accentSurface else ui.surface,
                12, if (pinnedOnly) ui.outline else null)
            typeface = if (!pinnedOnly) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            isSelected = !pinnedOnly
        }
        pinnedTab?.apply {
            setTextColor(if (pinnedOnly) ui.accent else ui.secondary)
            background = touchShape(if (pinnedOnly) ui.accentSurface else ui.surface,
                12, if (pinnedOnly) null else ui.outline)
            typeface = if (pinnedOnly) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            isSelected = pinnedOnly
        }
    }

    private data class UiColors(
        val background: Int,
        val surface: Int,
        val accentSurface: Int,
        val accent: Int,
        val accentButton: Int,
        val buttonText: Int,
        val text: Int,
        val secondary: Int,
        val outline: Int,
        val ripple: Int,
    )

    private val ui: UiColors by lazy {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        if (night) UiColors(
            Color.rgb(12, 19, 32), Color.rgb(25, 36, 53), Color.rgb(28, 66, 65),
            Color.rgb(103, 232, 198), Color.rgb(45, 163, 143), Color.rgb(8, 27, 29),
            Color.rgb(239, 247, 252), Color.rgb(171, 187, 202), Color.rgb(62, 79, 99),
            Color.argb(54, 103, 232, 198),
        ) else UiColors(
            Color.rgb(246, 249, 251), Color.WHITE, Color.rgb(225, 246, 241),
            Color.rgb(14, 115, 103), Color.rgb(18, 130, 113), Color.WHITE,
            Color.rgb(27, 43, 53), Color.rgb(100, 119, 131), Color.rgb(220, 230, 235),
            Color.argb(40, 14, 115, 103),
        )
    }

    private fun shape(fill: Int, radiusDp: Int, border: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radiusDp).toFloat()
            if (border != null) setStroke(dp(1), border)
        }

    private fun touchShape(fill: Int, radiusDp: Int, border: Int? = null): RippleDrawable =
        RippleDrawable(ColorStateList.valueOf(ui.ripple),
            shape(fill, radiusDp, border), shape(Color.WHITE, radiusDp))

    private fun actionText(label: String, color: Int, fill: Int, border: Int?): TextView =
        TextView(this).apply {
            text = label
            textSize = 12.5f
            setTextColor(color)
            gravity = Gravity.CENTER
            minWidth = dp(48)
            minHeight = dp(36)
            setPadding(dp(13), dp(6), dp(13), dp(6))
            background = touchShape(fill, 12, border)
            isFocusable = true
        }

    private fun insert(index: Int) {
        if (inserting) return
        val clip = results.getOrNull(index) ?: return
        val info = currentInputEditorInfo
        val permitted = SecurePasteTransport.permitted(
            app.isUnlocked, sessionEpoch, app.lockEpoch(), deviceLocked(),
            info?.packageName, activePackage, info?.inputType ?: EditorInfo.TYPE_NULL, inserting, packageName,
        )
        if (!permitted) {
            clearSensitiveState()
            status?.setText(R.string.secure_paste_editor_unavailable)
            return
        }
        inserting = true
        // Only InputConnection. Never fall back to system clipboard even on rejection/exception.
        val accepted = SecurePasteTransport.commit(currentInputConnection, clip.content)
        clearSensitiveState()
        if (accepted) {
            val returned = if (Build.VERSION.SDK_INT >= 28) {
                runCatching { switchToPreviousInputMethod() }.getOrDefault(false)
            } else false
            if (!returned) status?.setText(R.string.secure_paste_inserted_manual_switch)
        } else {
            status?.setText(R.string.secure_paste_failed)
        }
        inserting = false
    }

    private fun clearResultsOnly() {
        generation++
        results = emptyList()
        rows?.removeAllViews()
    }

    private fun clearSensitiveState() {
        deferredSearch?.let(main::removeCallbacks)
        deferredSearch = null
        clearResultsOnly()
        suppressSearch = true
        search?.text?.clear()
        suppressSearch = false
        pinnedOnly = false
        updateFilters()
        searchClear?.visibility = View.GONE
    }

    private fun openUnlockActivity() {
        // Prompt belongs to a foreground FragmentActivity, never a service or a bypass key.
        startActivity(Intent(this, SecurePasteUnlockActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun showPicker() {
        (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)?.showInputMethodPicker()
    }

    private fun deviceLocked(): Boolean =
        getSystemService(KeyguardManager::class.java)?.isDeviceLocked != false

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
