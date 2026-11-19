# Wear OS (:app) ProGuard / R8 rules

# Keep Android manifest components & reflective entrypoints
-keep class com.applemusic.lyricstool.wear.WristAmApplication { *; }
-keep class com.applemusic.lyricstool.wear.WearLyricsActivity { *; }
-keep class com.applemusic.lyricstool.wear.WearTokenListenerService { *; }
-keep class com.applemusic.lyricstool.wear.WearMusicService { *; }

# Keep AndroidX Lifecycle ViewModel constructors invoked by ViewModelProvider
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}

# Consumer rules from :shared, Media3, and Play Services Wearable handle library internals
-dontwarn com.google.android.gms.wearable.**
-dontwarn androidx.media3.**
-dontwarn androidx.wear.compose.**
