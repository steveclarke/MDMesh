package com.mdmesh.agent

import android.app.ActivityManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mdmesh.agent.service.CheckInService
import com.mdmesh.core.kiosk.KioskApplier
import com.mdmesh.core.store.KioskStateStore
import com.mdmesh.core.telemetry.EventSink
import com.mdmesh.kiosk.CrashLoopGuard
import com.mdmesh.kiosk.KioskController
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.proto.KioskApplyPayload
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * MDMesh kiosk HOME. This is the device's persistent launcher (`CATEGORY_HOME`), repointed to
 * by [KioskController.enter] via `addPersistentPreferredActivity`. It renders the last-applied
 * [KioskApplyPayload] persisted in [KioskStateStore]:
 *
 *  - `mode == "single"` → launch + pin the single allowed app ([KioskApplyPayload.pinPackage]).
 *  - `mode == "launcher"` → a themed grid of [KioskApplyPayload.allowedPackages].
 *  - no payload → an idle "managed device" screen (the agent is not in kiosk).
 *
 * Exit affordance is driven by [KioskApplyPayload.exitMode] (`gesture` 7-tap corner / `visible`
 * button / `remote` none) and gated by [KioskApplyPayload.password].
 *
 * A [CrashLoopGuard] protects against a crashing pinned app bouncing back to HOME in a tight
 * loop: each single-app launch registers a fault. A tripped guard shows locked recovery until
 * an administrator retries or exits; device-owner policy and persistent HOME stay in place.
 */
@AndroidEntryPoint
class KioskLauncherActivity : ComponentActivity() {

    @Inject lateinit var applier: KioskApplier
    @Inject lateinit var events: EventSink
    @Inject lateinit var crashGuard: CrashLoopGuard

    /** Current kiosk state, retained while rendering its locked recovery screen. */
    private var active: KioskApplyPayload? = null
    private var showingRecovery = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A kiosk device boots straight into HOME (this); keep the command channel alive even if
        // the user never opens the status screen.
        ContextCompat.startForegroundService(this, Intent(this, CheckInService::class.java))
        setContentView(idleView())
        // React to kiosk.enter/kiosk.exit live: those run in the check-in service, not here, so we
        // observe the persisted state and re-render (enter → grid/pin, exit → unpin + idle) without
        // waiting for the user to touch the screen. Each foreground return starts one collector,
        // so a bounced pinned app is counted/relaunched once, not once in both onStart and onResume.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                applier.launcherState().collect(::applyState)
            }
        }
    }

    private fun applyState(p: KioskApplyPayload?) {
        active = p
        if (p == null) {
            showingRecovery = false
            stopLockTaskSafely()
            setContentView(idleView())
            return
        }
        if (bailOnCrashLoop()) return
        showingRecovery = false
        startLockTaskSafely()
        if (p.mode == "single" && p.pinPackage != null) {
            launchPinned(p)
        } else {
            setContentView(launcherGrid(p))
        }
    }

    /** Launch + show the pinned app (single mode), with a themed splash behind it. */
    private fun launchPinned(p: KioskApplyPayload) {
        val intent = p.pinPackage?.let { packageManager.getLaunchIntentForPackage(it) }
        if (intent == null) {
            setContentView(launcherGrid(p)) // unknown package → fall back to the grid
            return
        }
        // Count attempts as well as returned apps, including activity/process recreation.
        crashGuard.registerFault()
        if (bailOnCrashLoop()) return
        setContentView(splashView(p))
        runCatching { startActivity(intent) }
    }

    /** A fault only changes the foreground surface; it never releases kiosk policy or HOME. */
    private fun bailOnCrashLoop(): Boolean {
        val p = active
        if (p == null || !crashGuard.isCrashLoopDetected()) return false
        if (!showingRecovery) events.record("kioskCrashLoop", "locked recovery after repeated exits or crashes")
        showingRecovery = true
        startLockTaskSafely()
        setContentView(recoveryView(p))
        return true
    }

    private fun startLockTaskSafely() {
        runCatching {
            val am = getSystemService(ActivityManager::class.java)
            if (am?.lockTaskModeState == android.app.ActivityManager.LOCK_TASK_MODE_NONE) startLockTask()
        }
    }

    private fun stopLockTaskSafely() {
        runCatching {
            val am = getSystemService(ActivityManager::class.java)
            if (am?.lockTaskModeState != android.app.ActivityManager.LOCK_TASK_MODE_NONE) stopLockTask()
        }
    }

    // --- Exit flow ---------------------------------------------------------------------------

    private fun promptExit(p: KioskApplyPayload) = promptAdminAction(p, retry = false)

    private fun promptAdminAction(p: KioskApplyPayload, retry: Boolean) {
        if (p.exitMode == "remote" || p.password.isNullOrBlank()) return
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Admin password"
        }
        val action = if (retry) "Retry" else "Exit"
        AlertDialog.Builder(this)
            .setTitle(if (retry) "Retry kiosk app" else "Exit kiosk")
            .setView(input)
            .setPositiveButton(action) { _, _ ->
                lifecycleScope.launch {
                    val result = if (retry) applier.retryWithPassword(input.text.toString())
                    else applier.exitWithPassword(input.text.toString())
                    if (result == KioskResult.Ok) {
                        events.record(if (retry) "kioskRetry" else "kioskExit", "authenticated on-device")
                        if (!retry) finish()
                    } else {
                        android.widget.Toast.makeText(
                            this@KioskLauncherActivity,
                            "Administrator action failed. Check the password or contact your administrator.",
                            android.widget.Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- Views -------------------------------------------------------------------------------

    private fun splashView(p: KioskApplyPayload): View {
        val bg = parseColor(p.theme.backgroundColor, INK)
        val fg = parseColor(p.theme.textColor, TEXT)
        return frame(bg).apply {
            addView(centeredText("Loading…", 18f, fg))
            addExitAffordance(p, this)
        }
    }

    private fun launcherGrid(p: KioskApplyPayload): View {
        val bg = parseColor(p.theme.backgroundColor, INK)
        val fg = parseColor(p.theme.textColor, TEXT)
        val cell = iconCellPx(p.theme.iconSize)
        val cols = maxOf(2, (resources.displayMetrics.widthPixels - dp(24)) / (cell + dp(24)))

        val grid = GridLayout(this).apply {
            columnCount = cols
            setPadding(dp(12), dp(16), dp(12), dp(28))
        }
        var rendered = 0
        for (pkg in p.allowedPackages.distinct()) {
            val app = runCatching { packageManager.getApplicationInfo(pkg, 0) }.getOrNull() ?: continue
            val icon = runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull() ?: continue
            val label = runCatching { packageManager.getApplicationLabel(app).toString() }.getOrDefault(pkg)
            grid.addView(appCell(pkg, label, icon, cell, fg))
            rendered++
        }

        // Always render a header + (when nothing resolved) an empty-state, so kiosk is never a
        // bare black screen — that previously happened whenever the allowlist was empty or none of
        // the packages were installed on the device.
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(28), dp(24), dp(12))
            layoutParams = ViewGroup.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        column.addView(text("MDMesh Kiosk", 20f, fg, bold = true))
        if (rendered == 0) {
            column.addView(
                text(
                    "No available apps. Add installed app packages to this kiosk's allowed list.",
                    14f,
                    MUTED,
                ).apply { setPadding(0, dp(10), 0, 0) },
            )
        }
        column.addView(grid)

        val root = frame(bg)
        root.addView(
            ScrollView(this).apply {
                addView(column)
                layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            },
        )
        addExitAffordance(p, root)
        return root
    }

    private fun appCell(
        pkg: String,
        label: String,
        icon: android.graphics.drawable.Drawable,
        cellPx: Int,
        fg: Int,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(12), dp(12), dp(12))
        isClickable = true
        addView(
            ImageView(this@KioskLauncherActivity).apply {
                setImageDrawable(icon)
                layoutParams = LinearLayout.LayoutParams(cellPx, cellPx)
            },
        )
        addView(
            text(label, 12f, fg).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(0, dp(6), 0, 0)
            },
        )
        setOnClickListener {
            runCatching {
                packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
            }
        }
    }

    private fun idleView(): View = frame(INK).apply {
        val col = LinearLayout(this@KioskLauncherActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }
        col.addView(centeredText("MDMesh", 28f, SIGNAL, bold = true))
        col.addView(centeredText("Managed device", 14f, MUTED))
        addView(col)
    }

    private fun recoveryView(p: KioskApplyPayload): View = frame(INK).apply {
        val col = LinearLayout(this@KioskLauncherActivity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), 0, dp(28), 0)
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }
        col.addView(centeredText("Kiosk recovery", 22f, ALERT, bold = true))
        col.addView(
            centeredText(
                "The kiosk app exited or crashed repeatedly. This device remains locked. " +
                    "Contact your administrator to retry or exit.",
                14f,
                MUTED,
            ).apply { setPadding(0, dp(12), 0, 0) },
        )
        if (p.exitMode != "remote" && !p.password.isNullOrBlank()) {
            col.addView(Button(this@KioskLauncherActivity).apply {
                text = "Admin retry"
                setOnClickListener { promptAdminAction(p, retry = true) }
            })
        }
        addView(col)
        addExitAffordance(p, this)
    }

    /** Add the per-[KioskApplyPayload.exitMode] exit affordance to [parent]. */
    private fun addExitAffordance(p: KioskApplyPayload, parent: ViewGroup) {
        if (p.password.isNullOrBlank()) return
        when (p.exitMode) {
            "visible" -> {
                val btn = Button(this).apply {
                    text = "Exit kiosk"
                    setOnClickListener { promptExit(p) }
                }
                parent.addView(
                    FrameWrap(this, btn, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, dp(24)),
                )
            }
            "gesture" -> {
                // Invisible top-right corner target; 7 taps within the window opens the prompt.
                val target = View(this).apply {
                    var taps = 0
                    var first = 0L
                    setOnClickListener {
                        val nowMs = System.currentTimeMillis()
                        if (nowMs - first > GESTURE_WINDOW_MS) { taps = 0; first = nowMs }
                        if (++taps >= GESTURE_TAPS) { taps = 0; promptExit(p) }
                    }
                }
                parent.addView(
                    FrameWrap(this, target, Gravity.TOP or Gravity.END, 0, dp(72), dp(72)),
                )
            }
            else -> Unit // "remote": no on-device exit
        }
    }

    // --- View helpers ------------------------------------------------------------------------

    private fun frame(bg: Int): android.widget.FrameLayout =
        android.widget.FrameLayout(this).apply {
            setBackgroundColor(bg)
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }

    private fun centeredText(s: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        text(s, sizeSp, color, bold).apply { gravity = Gravity.CENTER }

    private fun text(s: String, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun iconCellPx(size: String?): Int = when (size?.uppercase()) {
        "LARGE" -> dp(96)
        "MEDIUM" -> dp(72)
        else -> dp(56)
    }

    private fun parseColor(value: String?, fallback: Int): Int =
        value?.let { runCatching { Color.parseColor(it) }.getOrNull() } ?: fallback

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val GESTURE_TAPS = 7
        const val GESTURE_WINDOW_MS = 3_000L
        val INK = Color.parseColor("#0E1117")
        val TEXT = Color.parseColor("#E8EEF4")
        val MUTED = Color.parseColor("#8693A4")
        val SIGNAL = Color.parseColor("#F4B942")
        val ALERT = Color.parseColor("#F2545B")
    }
}

/** A [android.widget.FrameLayout.LayoutParams]-positioned wrapper, kept tiny for the launcher's
 *  programmatic UI (no XML). Places [child] at [gravity] with optional margins/size. */
private class FrameWrap(
    activity: ComponentActivity,
    child: View,
    gravity: Int,
    marginPx: Int,
    widthPx: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
    heightPx: Int = ViewGroup.LayoutParams.WRAP_CONTENT,
) : android.widget.FrameLayout(activity) {
    init {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        addView(
            child,
            android.widget.FrameLayout.LayoutParams(widthPx, heightPx, gravity).apply {
                setMargins(marginPx, marginPx, marginPx, marginPx)
            },
        )
    }
}
