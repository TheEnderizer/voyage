# Keep line numbers (and the real, unobfuscated source file name) in stack traces so a crash
# captured by AppLog's release-build handler — and shared by a beta user — can actually be
# resolved against app/build/outputs/mapping/release/mapping.txt via `retrace`, instead of
# reading "at a.b.c.d(SourceFile)" forever. See CLAUDE.md's release flow for archiving mapping.txt
# per versionCode.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-keep class com.betteraudio.data.db.entities.** { *; }

# Widget package: only the classes the launcher/AppWidgetManager reflectively instantiate by name
# (from AndroidManifest.xml <receiver>/<activity> and widget_info*.xml's android:configure) need
# keeping. Everything else under widget/ (WidgetUpdater, WidgetStateStore, render/*, model/*, ...)
# is reached only through ordinary calls/Hilt DI and shrinks normally.
-keep class com.betteraudio.widget.VoyageWidgetProvider { *; }
-keep class com.betteraudio.widget.VoyageWidgetProviderCoverControls { *; }
-keep class com.betteraudio.widget.VoyageWidgetProviderMinimalBar { *; }
-keep class com.betteraudio.widget.WidgetConfigureActivity { *; }

# Media3 ships its own consumer proguard rules (auto-merged from its AARs), and PlaybackService
# itself is auto-kept as a manifest-declared component - no blanket keep needed here.
-dontwarn androidx.media3.**

# Vosk (org.vosk.Model/Recognizer) is JNA-backed: JNA reflects into these classes' methods to
# bind them to the native Kaldi recognizer, so they must survive shrinking/renaming intact.
-keep class org.vosk.** { *; }
-dontwarn org.vosk.**

# Hilt
-keep class dagger.hilt.** { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @dagger.hilt.InstallIn class * { *; }
-keep @javax.inject.** class * { *; }
