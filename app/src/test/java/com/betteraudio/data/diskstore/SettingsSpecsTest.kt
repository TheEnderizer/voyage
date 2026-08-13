package com.betteraudio.data.diskstore

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the tri-state logging-settings migration (Phase 0 of the logging redesign): `.voyage/
 * settings.json` must never carry `enable_file_logging`, `log_level`, or `log_budget_mb`, because
 * mirroring any of them would mean a "clean" reinstall silently turns logging back on (or
 * restores VERBOSE + a large budget) instead of actually starting clean — see SettingsSpecs' own
 * exclusion-list doc comment. `coreSpecs()`/`pathSpecs()` build their list without touching a real
 * SettingsStore (the closures inside each [SettingSpec] are never invoked just by listing them),
 * so this is a plain, fast unit test — no Android/DataStore dependency needed.
 */
class SettingsSpecsTest {

    private fun allSpecNames(): List<String> =
        (SettingsSpecs.coreSpecs() + SettingsSpecs.pathSpecs()).map { it.name }

    @Test
    fun `logging settings are never mirrored`() {
        val names = allSpecNames()
        assertFalse("enable_file_logging must stay excluded from the mirror", names.contains("enable_file_logging"))
        assertFalse("log_level must stay excluded from the mirror", names.contains("log_level"))
        assertFalse("log_budget_mb must stay excluded from the mirror", names.contains("log_budget_mb"))
    }

    @Test
    fun `an old settings json entry for the removed enable_file_logging spec is silently ignored, not applied`() {
        // Mirrors LibraryBootstrapper.applySettingsDoc's real lookup: coreByName[sv.name]?.set(...)
        // — a name with no matching spec is a no-op via the safe call, not a crash or a fallback
        // to some other type. This is the exact case an old (pre-tri-state) settings.json produces
        // on a reinstall.
        val coreByName = SettingsSpecs.coreSpecs().associateBy { it.name }
        assertFalse(coreByName.containsKey("enable_file_logging"))
    }

    @Test
    fun `every spec name is unique`() {
        val names = allSpecNames()
        assertEqualsSize(names, names.distinct())
    }

    private fun assertEqualsSize(a: List<String>, b: List<String>) {
        assertTrue("duplicate setting spec name(s) found: ${a.groupingBy { it }.eachCount().filter { it.value > 1 }}", a.size == b.size)
    }
}
