# Keep data models
-keep class com.applemusic.android.apple.** { *; }
-keep class com.applemusic.lyrics.core.** { *; }
-keep class com.applemusic.shared.** { *; }

# Keep Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# Keep Wearable Data Client and Services
-keep class com.google.android.gms.wearable.** { *; }
-keep class com.applemusic.lyricstool.wear.WearTokenListenerService { *; }
-keep class com.applemusic.lyricstool.wear.WearMusicService { *; }
