package com.taxiinspector.data.trace

import android.content.Context
import com.taxiinspector.trace.TraceGpx
import java.io.File

/**
 * Where a debug ride trace lives on disk, whether tracing is switched on at all, and the only
 * thing that removes one.
 *
 * The directory is the app's own external files directory, not internal storage, so a captured
 * trip can be pulled off the phone with a plain `adb pull` and analysed on a computer. It is
 * still app-scoped: no permission is needed for it, nothing else can write it, and uninstalling
 * the app deletes every trace with it.
 */
class RideTraceStore internal constructor(private val baseDirectory: File) {
    constructor(context: Context) : this(
        // getExternalFilesDir creates this directory, owned by the app. That matters: a
        // directory created instead by `adb shell` belongs to shell, and the app then cannot
        // even stat what is inside it, so the switch below would be invisible to the very
        // process that reads it. Measured on an API 35 emulator, not theorised.
        context.getExternalFilesDir(null) ?: context.filesDir,
    )

    private val root: File get() = File(baseDirectory, DIRECTORY)

    /**
     * The switch lives beside the trace directory, not inside it, so that it sits in a directory
     * the app created and can therefore read a shell-written file from, and so that pruning
     * traces can never remove it.
     */
    private val switchFile: File get() = File(baseDirectory, SWITCH_FILE)

    /** The directory to pull traces from, so a script and a log line can name the same path. */
    val rootDirectory: File get() = root

    /** Where the switch is expected, so the log line that reports it off can say where to look. */
    val switchPath: File get() = switchFile

    /**
     * Whether a ride started now will be traced.
     *
     * Recording a route is off until it is deliberately switched on, even on a debug build: a
     * trace holds coordinates, and nothing should collect them by default. The switch is a
     * marker file rather than a stored setting so it can be flipped over adb without opening
     * the app, and so it survives a reboot -- a field trip silently going unrecorded because a
     * toggle reset itself is the one failure this facility cannot afford.
     *
     * `scripts/trace-toggle.sh on|off|status` manages it.
     */
    fun isTracingEnabled(): Boolean = switchFile.isFile

    fun setTracingEnabled(enabled: Boolean) {
        if (enabled) {
            baseDirectory.mkdirs()
            if (!switchFile.isFile) switchFile.writeText(SWITCH_CONTENT)
        } else {
            switchFile.delete()
        }
    }

    fun directoryFor(rideId: String): File = File(root, rideId.sanitised())

    fun hasTrace(rideId: String): Boolean = filesFor(rideId).isNotEmpty()

    /**
     * The trace's files, newest content first written. A ride whose process died mid-write left
     * its GPX unterminated, so it is closed here rather than being shared as invalid XML.
     */
    fun filesFor(rideId: String): List<File> {
        val directory = directoryFor(rideId)
        terminateTrackIfUnfinished(directory)
        return listOf(TRACK_FILE, DECISIONS_FILE, META_FILE)
            .map { File(directory, it) }
            .filter { it.isFile && it.length() > 0 }
    }

    fun delete(rideId: String) {
        directoryFor(rideId).deleteRecursively()
    }

    /** Bounds the disk a long-lived debug install can accumulate. Runs at ride start. */
    fun prune(keepNewest: Int = KEEP_NEWEST) {
        val directories = root.listFiles()?.filter { it.isDirectory } ?: return
        directories
            .sortedByDescending { it.lastModified() }
            .drop(keepNewest)
            .forEach { it.deleteRecursively() }
    }

    private fun terminateTrackIfUnfinished(directory: File) {
        val track = File(directory, TRACK_FILE)
        if (!track.isFile || track.length() == 0L) return
        runCatching {
            if (!track.readText().trimEnd().endsWith("</gpx>")) {
                track.appendText(TraceGpx.footer())
            }
        }
    }

    /** A ride id is a UUID, but a path must never be able to escape the trace directory. */
    private fun String.sanitised(): String =
        filter { it.isLetterOrDigit() || it == '-' || it == '_' }.take(64).ifEmpty { "unnamed" }

    internal companion object {
        const val DIRECTORY = "traces"
        const val TRACK_FILE = "track.gpx"
        const val DECISIONS_FILE = "decisions.csv"
        const val META_FILE = "meta.json"
        const val KEEP_NEWEST = 30
        const val SWITCH_FILE = ".tracing-enabled"
        const val SWITCH_CONTENT =
            "Delete this file to stop recording ride traces. See docs/field-validation.md.\n"
    }
}
