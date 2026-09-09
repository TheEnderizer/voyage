package com.betteraudio.util.log

/**
 * Categories replacing the ~24 free-form string tags the old logger used (Widget/Player/Scan/
 * Damage/DB/EbookScan/DiskExport/Settings/DiskStore/Update/Restructure/Backup/History/Aligner/
 * Service/Mp4Probe/Home/Nav/LargeFileMediaSourceFactory/DiskMirror/Decode/Chapters).
 * Phase 3 migrated every `AppLog.*(String, String)` call site to one of these directly and
 * deleted the legacy overloads (and the tag→category mapping that bridged them) once that
 * migration was complete — a straggler would fail to compile rather than silently keep working.
 */
enum class LogCat {
    PLAYBACK, SLEEP, LIBRARY, SCAN, DISK, DB, SETTINGS, WIDGET, NET, BACKUP, THEME, UI, SYSTEM
}
