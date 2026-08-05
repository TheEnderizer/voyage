package com.betteraudio.playback

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs the production scanner against a real download-damaged audiobook and prints what it finds,
 * so the Kotlin implementation can be checked against the reference scan that diagnosed the bug.
 *
 * Skips itself unless `-Dvoyage.damagedFile=<path>` is supplied, so it never affects normal runs:
 *
 *   :app:testDebugUnitTest -Dvoyage.damagedFile="H:/path/to/file.mp3"
 */
class RealDamagedFileScanTest {

    @Test
    fun scanRealFileAndReport() {
        val path = System.getProperty("voyage.damagedFile")
        assumeTrue("set -Dvoyage.damagedFile to run", !path.isNullOrBlank())
        val f = File(path!!)
        assumeTrue("file not found: $path", f.isFile)

        val started = System.currentTimeMillis()
        val gaps = Mp3DamageScanner.scan(path)
        val tookMs = System.currentTimeMillis() - started

        val bad = gaps.sumOf { it.size }
        println("=== ${f.name}")
        println("    size      = ${f.length()} bytes")
        println("    scan time = ${tookMs} ms")
        println("    gaps      = ${gaps.size}, $bad bytes damaged (~${bad / 32_000} s at 256 kbps)")
        gaps.forEach { println("      ${it.start}..${it.end}  (${it.size} bytes)") }
        println("    encoded   = ${Mp3DamageScanner.encode(gaps)}")
    }
}
