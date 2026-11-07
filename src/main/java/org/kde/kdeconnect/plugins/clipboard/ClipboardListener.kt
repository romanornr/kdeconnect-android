/*
 * SPDX-FileCopyrightText: 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 * SPDX-FileCopyrightText: 2021 Ilmaz Gumerov <ilmaz1309@gmail.com>
 *
 * SPDX-License-Identifier: GPL-2.0-only OR GPL-3.0-only OR LicenseRef-KDE-Accepted-GPL
 */
package org.kde.kdeconnect.plugins.clipboard

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.getSystemService
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import org.kde.kdeconnect.helpers.ThreadHelper
import org.kde.kdeconnect_tp.BuildConfig
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

class ClipboardListener internal constructor(
    ctx: Context,
    private val lifecycle: Lifecycle = ProcessLifecycleOwner.get().lifecycle,
    private val startProcess: (Array<String>) -> Process = { Runtime.getRuntime().exec(it) },
) {
    enum class ClipboardContentType {
        Text,
        Password,
    }

    interface ClipboardObserver {
        fun clipboardChanged(content: String, contentType: ClipboardContentType)
    }

    private val observers: HashSet<ClipboardObserver> = HashSet()

    private val context = ctx.applicationContext
    var currentContent: String? = null
        private set
    var currentContentType: ClipboardContentType = ClipboardContentType.Text
        private set
    var updateTimestamp: Long = 0
        private set

    private lateinit var cm: ClipboardManager

    private val logReaderRunning = AtomicBoolean(false)
    private val isResumed = AtomicBoolean(false)

    init {
        Handler(Looper.getMainLooper()).post {
            cm = context.getSystemService<ClipboardManager>()!!
            cm.addPrimaryClipChangedListener { this.onClipboardChanged() }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                isResumed.set(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
                lifecycle.addObserver(object : DefaultLifecycleObserver {
                    override fun onResume(owner: LifecycleOwner) {
                        isResumed.set(true)
                        startLogReader()
                    }

                    override fun onPause(owner: LifecycleOwner) {
                        isResumed.set(false)
                    }
                })
                startLogReader()
            }
        }
    }

    private fun startLogReader() {
        // Start logcat only while an activity is resumed. Since Android 13, logd asks the user for
        // consent only when the app is the top app. Otherwise it denies silently but keeps the logcat
        // process alive and blind to other UIDs, so clipboard sync stops until the process is killed.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !isResumed.get()
        ) {
            return
        }
        if (!ClipboardPlugin.canSyncAutomatically(context) || !logReaderRunning.compareAndSet(false, true)) {
            return
        }
        ThreadHelper.runOnNewThread {
            var process: Process? = null
            try {
                // The app may have left the foreground since this was queued.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isResumed.get()) {
                    return@runOnNewThread
                }
                val timeStamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                // Listen only ClipboardService errors after now
                val logcatFilter = if (Build.VERSION.SDK_INT > Build.VERSION_CODES.VANILLA_ICE_CREAM) { "E ClipboardService" } else { "ClipboardService:E" }
                process = startProcess(arrayOf("logcat", "-T", timeStamp, logcatFilter, "*:S"))
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    reader.forEachLine { line ->
                        if (line.contains(BuildConfig.APPLICATION_ID)) {
                            context.startActivity(ClipboardFloatingActivity.getIntent(context, false))
                        }
                    }
                }
                Log.w("ClipboardListener", "Clipboard log reader ended; will retry when the app resumes")
            } catch (e: Exception) {
                Log.w("ClipboardListener", "Clipboard log reader failed; will retry when the app resumes", e)
            } finally {
                process?.destroy()
                logReaderRunning.set(false)
            }
        }
    }

    fun registerObserver(observer: ClipboardObserver) {
        observers.add(observer)
    }

    fun removeObserver(observer: ClipboardObserver) {
        observers.remove(observer)
    }

    fun onClipboardChanged() {
        try {
            val clip = cm.primaryClip!!
            val item = clip.getItemAt(0)
            val content = item.coerceToText(context).toString()
            val contentType = detectContentType(clip)

            if (content == currentContent && contentType == currentContentType) {
                return
            }
            updateTimestamp = System.currentTimeMillis()
            currentContent = content
            currentContentType = contentType

            for (observer in observers) {
                observer.clipboardChanged(content, contentType)
            }
        } catch (_: Exception) {
            //Probably clipboard was not text
        }
    }

    fun setText(text: String?) {
        if (this::cm.isInitialized) {
            if (text == currentContent) {
                return
            }
            updateTimestamp = System.currentTimeMillis()
            currentContent = text
            currentContentType = ClipboardContentType.Text
            cm.setPrimaryClip(ClipData.newPlainText(null, text))
        }
    }

    companion object {
        private var _instance: ClipboardListener? = null

        fun instance(context: Context): ClipboardListener {
            // FIXME: The _instance we return won't be completely initialized yet since initialization happens on a new thread (why?)
            return _instance ?: ClipboardListener(context).also { _instance = it }
        }

        fun detectContentType(clip: ClipData?): ClipboardContentType {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                return ClipboardContentType.Text
            }
            if (clip?.description?.extras
                    ?.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false) == true
            ) {
                return ClipboardContentType.Password
            }
            return ClipboardContentType.Text
        }
    }
}
