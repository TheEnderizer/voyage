package com.betteraudio.data.diskstore

import java.util.concurrent.atomic.AtomicInteger

/**
 * The nesting counter behind [DiskMirror.suppressed] — extracted from [DiskMirror] so the one
 * property the whole bulk-apply design rests on is unit-testable without a database (same reason
 * `JumpClassifier`, `GapMap` and `AnchorResolver` are their own pure types).
 *
 * **It is a counter, not a flag, and that distinction is load-bearing.** Suppressed blocks nest
 * (the scanner's `importBook` opens one inside a scan that may already be inside another) *and*
 * can now genuinely overlap across coroutines — a WorkManager companion-pack import running
 * `AudioFileScanner.importSingleFolder` while a library scan is walking the tree. With a Boolean,
 * whichever block finished first would clear it and un-suppress the one still running, which is
 * exactly the failure the counter prevents: suppression lifts only when the LAST block exits.
 * (Swapping this for an `AtomicBoolean` fails four of `SuppressionGateTest`'s six cases — that
 * was verified, not assumed.)
 *
 * [exit] reporting the outermost exit is what lets [DiskMirror] drain exactly once per outermost
 * block rather than relying on every caller remembering to flush afterwards.
 */
internal class SuppressionGate {

    private val depth = AtomicInteger(0)

    /** True while any block is active — the signal every flush path checks before writing. */
    val isSuppressed: Boolean get() = depth.get() > 0

    /** @return true when this is the OUTERMOST enter (suppression just began). */
    fun enter(): Boolean = depth.incrementAndGet() == 1

    /** @return true when this is the OUTERMOST exit — suppression has lifted and the caller owes
     *  a drain. Inner/overlapping exits return false; they must not drain, because another block
     *  is still mutating. */
    fun exit(): Boolean = depth.decrementAndGet() == 0

    /** Test-only view of the raw depth. */
    internal fun depth(): Int = depth.get()
}
