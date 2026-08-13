package com.betteraudio.util.log

/**
 * Path-scrubbing applied ONLY to Copy/Share output — the on-device log itself always keeps full
 * paths (see [LogEngine.log]'s doc). Shared by [LogEngine.buildShareBundle] (the zip bundle) and
 * `AppLog`'s plain-text Copy path (the clipboard), so both redact identically. A "share raw"
 * escape hatch in the Diagnostics UI can skip this and pass the untouched text/bundle through.
 */
object ShareRedaction {
    private val ABS_PATH_PATTERN = Regex("""/(?:[\w.\- ]+/)+[\w.\-]+""")
    fun scrubPaths(text: String): String = ABS_PATH_PATTERN.replace(text, "[PATH]")
}
