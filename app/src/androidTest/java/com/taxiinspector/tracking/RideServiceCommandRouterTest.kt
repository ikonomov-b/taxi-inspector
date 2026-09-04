package com.taxiinspector.tracking

import android.app.Service
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RideServiceCommandRouterTest {
    @Test
    fun foregroundStartFailureCreatesNoCommandAndStopsCleanly() {
        val foreground = FakeForegroundSession(failStart = true)
        val sink = FakeCommandSink()
        val stopCount = AtomicInteger()
        val router = RideServiceCommandRouter(foreground, sink) { stopCount.incrementAndGet() }

        val result = router.onStartCommand(RideCommand.Start.action)

        assertEquals(Service.START_NOT_STICKY, result)
        assertTrue(sink.commands.isEmpty())
        assertEquals(1, sink.foregroundFailures.get())
        assertEquals(1, foreground.stopCount.get())
        assertEquals(1, stopCount.get())
    }

    @Test
    fun notificationPauseUsesTheSameSerializedCommandPathWithoutStartingForegroundAgain() {
        val foreground = FakeForegroundSession()
        val sink = FakeCommandSink()
        val router = RideServiceCommandRouter(foreground, sink) {}

        val result = router.onStartCommand(RideCommand.Pause.action)

        assertEquals(Service.START_NOT_STICKY, result)
        assertEquals(listOf(RideCommand.Pause), sink.commands)
        assertEquals(0, foreground.startCount.get())
    }

    @Test
    fun everyCommandAndUnknownIntentRemainNotSticky() {
        val foreground = FakeForegroundSession()
        val sink = FakeCommandSink()
        val router = RideServiceCommandRouter(foreground, sink) {}

        RideCommand.entries.forEach { command ->
            assertEquals(Service.START_NOT_STICKY, router.onStartCommand(command.action))
        }
        assertEquals(Service.START_NOT_STICKY, router.onStartCommand("unknown"))
        assertEquals(RideCommand.entries, sink.commands)
        assertEquals(2, foreground.startCount.get())
    }

    private class FakeForegroundSession(
        private val failStart: Boolean = false,
    ) : ForegroundSession {
        val startCount = AtomicInteger()
        val stopCount = AtomicInteger()

        override fun startPreparing() {
            startCount.incrementAndGet()
            if (failStart) error("foreground rejected")
        }

        override fun stop() {
            stopCount.incrementAndGet()
        }
    }

    private class FakeCommandSink : RideCommandSink {
        val commands = mutableListOf<RideCommand>()
        val foregroundFailures = AtomicInteger()

        override fun dispatch(command: RideCommand) {
            commands += command
        }

        override fun rejectForegroundStart() {
            foregroundFailures.incrementAndGet()
        }
    }
}
