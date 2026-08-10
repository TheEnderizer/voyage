package com.betteraudio.data.diskstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SettingsDataCodecTest {

    private fun sample() = SettingsDocument(
        writtenAt = 1L,
        libraryFolderAtWrite = "/Audiobooks",
        settings = listOf(
            SettingsDocument.SettingValue("app_theme", "string", "IMMERSIVE"),
            SettingsDocument.SettingValue("pure_black", "boolean", "true")
        )
    )

    @Test
    fun `round-trips settings list`() {
        val original = sample()
        val decoded = SettingsDataCodec.decodeOrNull(SettingsDataCodec.encodeToString(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `an entry missing its value is skipped, not the whole document`() {
        val json = """{"libraryFolderAtWrite":"/x","settings":[
            {"name":"app_theme","type":"string","value":"IMMERSIVE"},
            {"name":"broken","type":"string"}
        ]}"""
        val decoded = SettingsDataCodec.decodeOrNull(json)!!
        assertEquals(1, decoded.settings.size)
        assertEquals("app_theme", decoded.settings.single().name)
    }

    @Test
    fun `corrupt input returns null`() {
        assertNull(SettingsDataCodec.decodeOrNull("not json"))
    }
}
