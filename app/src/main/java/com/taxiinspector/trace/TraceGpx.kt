package com.taxiinspector.trace

import java.util.Locale

/**
 * GPX 1.1: one track, one segment, every raw fix as a track point, in time order, each with a
 * UTC `<time>`. That is what makes a saved ride comparable with a Locus Map recording of the
 * same trip: Locus imports this file directly for a visual overlay, and the two tracks align on
 * wall-clock time.
 *
 * The engine's verdict on each fix rides along in `<extensions>` under this app's own namespace,
 * so a refused or held point is still visible on the map rather than silently absent, and a
 * strict GPX parser -- Locus included -- ignores what it does not recognise instead of
 * rejecting the file.
 */
object TraceGpx {
    private const val EXTENSION_NAMESPACE = "urn:taxi-inspector:gpx:1"
    private const val EXTENSION_PREFIX = "ti"
    fun header(meta: TraceMeta): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine(
            """<gpx version="1.1" creator="Taxi Inspector ${meta.appVersionName.xml()}" """ +
                """xmlns="http://www.topografix.com/GPX/1/1" """ +
                """xmlns:$EXTENSION_PREFIX="$EXTENSION_NAMESPACE">""",
        )
        appendLine("  <metadata>")
        appendLine("    <name>${"Ride ${meta.rideId}".xml()}</name>")
        appendLine("    <time>${isoUtcMillis(meta.startedUtcMillis)}</time>")
        appendLine("  </metadata>")
        appendLine("  <trk>")
        appendLine("    <name>${(meta.companyName ?: "Ride ${meta.rideId}").xml()}</name>")
        appendLine("    <trkseg>")
    }

    /** Null for a row with no fix: a tick or a command is not a place. */
    fun trackPoint(row: TraceRow): String? {
        val sample = row.sample ?: return null
        return buildString {
            appendLine(
                String.format(
                    Locale.ROOT,
                    """      <trkpt lat="%.7f" lon="%.7f">""",
                    sample.latitude,
                    sample.longitude,
                ),
            )
            sample.altitudeMeters?.let {
                appendLine(String.format(Locale.ROOT, "        <ele>%.2f</ele>", it))
            }
            sample.utcMillis?.let { appendLine("        <time>${isoUtcMillis(it)}</time>") }
            appendLine("        <extensions>")
            appendLine(element("seq", row.sequence.toString()))
            appendLine(element("accuracyM", String.format(Locale.ROOT, "%.3f", sample.accuracyMeters)))
            sample.speedMetersPerSecond?.let {
                appendLine(element("speedMps", String.format(Locale.ROOT, "%.3f", it)))
            }
            sample.speedAccuracyMetersPerSecond?.let {
                appendLine(element("speedAccMps", String.format(Locale.ROOT, "%.3f", it)))
            }
            appendLine(element("band", sample.band.name))
            sample.signal?.let { signal ->
                appendLine(element("l5", signal.l5SignalCount.toString()))
                appendLine(element("usedInFix", signal.satellitesUsedInFix.toString()))
                appendLine(element("inView", signal.satellitesInView.toString()))
                signal.medianCn0UsedDbHz?.let {
                    appendLine(element("cn0Used", String.format(Locale.ROOT, "%.1f", it)))
                }
                signal.medianCn0InViewDbHz?.let {
                    appendLine(element("cn0View", String.format(Locale.ROOT, "%.1f", it)))
                }
            }
            appendLine(element("mock", sample.isMock.toString()))
            row.decision?.let {
                appendLine(element("reason", it.reason.name))
                appendLine(element("billedAs", it.billedAs.name))
            }
            appendLine(element("distanceM", row.distanceMeters.toPlainString()))
            appendLine(element("timeMs", row.billedTimeMillis.toString()))
            appendLine(element("total", row.total))
            appendLine("        </extensions>")
            appendLine("      </trkpt>")
        }
    }

    fun footer(): String = buildString {
        appendLine("    </trkseg>")
        appendLine("  </trk>")
        appendLine("</gpx>")
    }

    private fun element(name: String, value: String): String =
        "          <$EXTENSION_PREFIX:$name>${value.xml()}</$EXTENSION_PREFIX:$name>"

    private fun String.xml(): String = this
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
