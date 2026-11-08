package org.kde.kdeconnect.plugins.clipboard

import android.Manifest
import android.app.Application
import android.content.Intent
import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.kde.kdeconnect.helpers.ThreadHelper
import org.kde.kdeconnect_tp.BuildConfig
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.InputStream
import java.time.Duration

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, sdk = [33])
class ClipboardListenerTest {
    private lateinit var application: Application
    private lateinit var owner: LifecycleOwner
    private lateinit var lifecycle: LifecycleRegistry
    private val workers = ArrayDeque<Runnable>()
    private var top = false
    private var launches = 0
    private var destroyed = 0

    @Before
    fun setup() {
        application = ApplicationProvider.getApplicationContext()
        shadowOf(application).grantPermissions(Manifest.permission.READ_LOGS)
        owner = object : LifecycleOwner {
            override val lifecycle: Lifecycle
                get() = this@ClipboardListenerTest.lifecycle
        }
        lifecycle = LifecycleRegistry(owner)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        mockkObject(ThreadHelper)
        every { ThreadHelper.runOnNewThread(any()) } answers {
            workers.addLast(firstArg())
        }
    }

    @After
    fun cleanup() {
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        workers.clear()
        unmockkObject(ThreadHelper)
    }

    private fun reader(stream: InputStream = denial().byteInputStream()): Process = mockk {
        every { inputStream } returns stream
        every { destroy() } answers { destroyed++ }
    }

    private fun denial() = "ClipboardService: Denying clipboard access to ${BuildConfig.APPLICATION_ID}\n"

    // Each step runs on one read call and returns the bytes to deliver, or null for end of stream.
    private fun scripted(vararg steps: () -> ByteArray?): InputStream = object : InputStream() {
        private val pending = ArrayDeque(steps.toList())

        override fun read(): Int = throw UnsupportedOperationException()

        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            val chunk = pending.removeFirstOrNull()?.invoke() ?: return -1
            chunk.copyInto(bytes, offset)
            return chunk.size
        }
    }

    private fun listen(start: () -> Process = { reader() }) {
        ClipboardListener(application, lifecycle, { launches++; start() }, { top })
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun runWorkers() {
        // A broken ownership guard must fail here rather than enqueue work forever.
        repeat(10) {
            if (workers.isEmpty()) return
            workers.removeFirst().run()
        }
        assertEquals("Reader workers did not settle", 0, workers.size)
    }

    private fun resume() {
        top = true
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    private fun pause() {
        top = false
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
    }

    private fun assertClipboardReadRequested() {
        assertEquals(
            ClipboardFloatingActivity::class.java.name,
            shadowOf(application).nextStartedActivity?.component?.className,
        )
    }

    @Test
    fun backgroundAndStartedAppDoNotRequestLogAccessUntilResumed() {
        listen()
        runWorkers()
        assertEquals(0, launches)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        runWorkers()
        assertEquals(0, launches)

        resume()
        runWorkers()
        assertEquals(1, launches)
        assertClipboardReadRequested()
    }

    @Test
    fun resumedBehindLockScreenDoesNotRequestLogAccess() {
        listen()
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        runWorkers()
        assertEquals(0, launches)
    }

    @Test
    fun queuedReaderDoesNotRequestLogAccessAfterAppLeavesForeground() {
        listen()
        resume()
        pause()
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        runWorkers()
        assertEquals(0, launches)

        resume()
        runWorkers()
        assertEquals(1, launches)
        assertClipboardReadRequested()
    }

    @Test
    fun foregroundReentryDoesNotDuplicateAReaderThatIsStillRunning() {
        listen {
            reader(scripted(
                { pause(); resume(); denial().toByteArray() },
                { denial().toByteArray() },
            ))
        }
        resume()
        runWorkers()
        assertEquals(1, launches)
        assertClipboardReadRequested()
        assertEquals(1, destroyed)
    }

    @Test
    fun stoppedReaderIsReplacedOnNextResume() {
        listen { if (launches == 1) reader("".byteInputStream()) else reader() }
        resume()
        runWorkers()
        assertNull(shadowOf(application).nextStartedActivity)
        assertEquals(1, destroyed)

        pause()
        resume()
        runWorkers()
        assertEquals(2, launches)
        assertClipboardReadRequested()
        assertEquals(2, destroyed)
    }

    @Test
    fun probeAnsweredByReaderIsNotTreatedAsACopy() {
        listen {
            reader(scripted(
                { pause(); idle(); denial().toByteArray() },
                { denial().toByteArray() },
            ))
        }
        resume()
        runWorkers()
        // First denial answers the probe, second is a real clipboard change.
        assertClipboardReadRequested()
        assertNull(shadowOf(application).nextStartedActivity)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertEquals(1, destroyed)
    }

    @Test
    fun blindReaderIsStoppedAfterProbeTimeoutAndReplacedOnNextResume() {
        listen {
            if (launches == 1) reader(scripted({
                pause()
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
                assertEquals("Blind reader should be destroyed by the probe timeout", 1, destroyed)
                null
            })) else reader()
        }
        resume()
        runWorkers()
        assertNull(shadowOf(application).nextStartedActivity)

        resume()
        runWorkers()
        assertEquals(2, launches)
        assertClipboardReadRequested()
    }

    @Test
    fun unlockWithoutReaderRequestsLogAccessThroughInvisibleActivity() {
        listen()
        application.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        idle()
        val started = shadowOf(application).nextStartedActivity
        assertEquals(ClipboardFloatingActivity::class.java.name, started?.component?.className)
        assertTrue(started!!.getBooleanExtra(ClipboardFloatingActivity.KEY_REQUEST_LOG_ACCESS, false))
    }

    @Test
    fun unlockWithRunningReaderDoesNothing() {
        listen()
        resume()
        application.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        idle()
        assertNull(shadowOf(application).nextStartedActivity)
    }

    @Test
    fun missingPermissionDoesNotStartReaderAndGrantCanTakeEffectOnResume() {
        shadowOf(application).denyPermissions(Manifest.permission.READ_LOGS)
        listen()
        resume()
        runWorkers()
        assertEquals(0, launches)

        pause()
        shadowOf(application).grantPermissions(Manifest.permission.READ_LOGS)
        resume()
        runWorkers()
        assertEquals(1, launches)
        assertClipboardReadRequested()
    }

    @Test
    @Config(sdk = [29])
    fun androidBeforeConsentGateKeepsBackgroundDetection() {
        listen()
        runWorkers()
        assertEquals(1, launches)
        assertClipboardReadRequested()
    }
}
