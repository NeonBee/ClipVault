package dev.clipvault.app.securepaste

import android.app.KeyguardManager
import android.content.Intent
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
import android.widget.Button
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
    private var unlockButton: Button? = null
    private var results: List<ClipItem> = emptyList()
    private var activePackage: String? = null
    private var sessionEpoch = -1L
    private var generation = 0L
    private var pinnedOnly = false
    private var suppressSearch = false
    private var inserting = false
    private var deferredSearch: Runnable? = null

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
    override fun onEvaluateInputViewShown(): Boolean = true

    override fun onCreateInputView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(10))
            setBackgroundColor(Color.rgb(246, 247, 250))
            isFocusableInTouchMode = true
        }
        val heading = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        heading.addView(TextView(this).apply {
            text = getString(R.string.secure_paste_ime_label)
            textSize = 17f
            setTextColor(Color.BLACK)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        heading.addView(Button(this).apply {
            text = getString(R.string.secure_paste_switch_keyboard)
            setOnClickListener { showPicker() }
        })
        root.addView(heading)

        status = TextView(this).apply {
            setTextColor(Color.DKGRAY)
            setText(R.string.secure_paste_locked)
            setPadding(0, dp(4), 0, dp(4))
        }.also { root.addView(it) }

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        unlockButton = Button(this).apply {
            text = getString(R.string.secure_paste_unlock)
            setOnClickListener { openUnlockActivity() }
        }.also { controls.addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
        controls.addView(Button(this).apply {
            text = getString(R.string.secure_paste_refresh)
            setOnClickListener { refresh() }
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val pinnedButton = Button(this).apply {
            text = getString(R.string.secure_paste_recent)
            setOnClickListener {
                pinnedOnly = !pinnedOnly
                text = getString(if (pinnedOnly) R.string.secure_paste_pinned else R.string.secure_paste_recent)
                refresh()
            }
        }
        controls.addView(pinnedButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(controls)

        search = EditText(this).apply {
            hint = getString(R.string.secure_paste_search_hint)
            setSingleLine(true)
            setShowSoftInputOnFocus(false) // No second QWERTY IME: hardware-keyboard search only in PoC.
            setSaveEnabled(false)
            isLongClickable = false
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (suppressSearch) return
                    deferredSearch?.let(main::removeCallbacks)
                    val work = Runnable { refresh() }
                    deferredSearch = work
                    main.postDelayed(work, 150L)
                }
            })
        }.also { root.addView(it) }

        rows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(rows) },
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(178)))
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
        // After the biometric Activity finishes Android may re-show the same input view.
        refresh()
    }

    override fun onWindowHidden() {
        clearSensitiveState()
        super.onWindowHidden()
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
        app.removeLockListener(lockListener)
        clearSensitiveState()
        rows = null
        search = null
        status = null
        unlockButton = null
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
            activePackage, info.inputType, false,
        )
        if (isLocked || !editorOk) {
            clearResultsOnly()
            unlockButton?.visibility = if (isLocked) View.VISIBLE else View.GONE
            status?.setText(
                if (isLocked) R.string.secure_paste_locked else R.string.secure_paste_editor_unavailable
            )
            return
        }
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
                } else {
                    results = data
                    renderResults()
                }
            }
        }
    }

    private fun renderResults() {
        rows?.removeAllViews()
        status?.setText(if (results.isEmpty()) R.string.secure_paste_empty else R.string.secure_paste_select)
        results.forEachIndexed { index, clip ->
            // A preview is visible only on the authenticated IME view; the IME window uses FLAG_SECURE.
            val preview = clip.title.ifBlank { clip.content.replace('\n', ' ').take(72) }
            rows?.addView(Button(this).apply {
                text = (if (clip.pinned) "★ " else "") + preview
                isAllCaps = false
                maxLines = 2
                setOnClickListener { insert(index) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun insert(index: Int) {
        if (inserting) return
        val clip = results.getOrNull(index) ?: return
        val info = currentInputEditorInfo
        val permitted = SecurePasteTransport.permitted(
            app.isUnlocked, sessionEpoch, app.lockEpoch(), deviceLocked(),
            info?.packageName, activePackage, info?.inputType ?: EditorInfo.TYPE_NULL, inserting,
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
