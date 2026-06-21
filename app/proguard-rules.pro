-keep class com.betteraudio.data.db.entities.** { *; }
-keep class com.betteraudio.widget.** { *; }

# Media3
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Hilt
-keep class dagger.hilt.** { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @dagger.hilt.InstallIn class * { *; }
-keep @javax.inject.** class * { *; }
