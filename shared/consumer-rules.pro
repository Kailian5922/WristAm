# Consumer ProGuard rules for :shared module

# Keep playback service classes (also resolved via Class.forName fallback in PlaybackEngine)
-keep class com.applemusic.android.player.MusicService { *; }
-keep class com.applemusic.lyricstool.wear.WearMusicService { *; }

# Keep core data models & serialized structures
-keep class com.applemusic.android.apple.AppleSong { *; }
-keep class com.applemusic.android.apple.AppleAlbum { *; }
-keep class com.applemusic.android.apple.AppleArtist { *; }
-keep class com.applemusic.android.apple.AppleArtistPage { *; }
-keep class com.applemusic.android.apple.ApplePlaylist { *; }
-keep class com.applemusic.android.apple.AppleAsset { *; }
-keep class com.applemusic.android.apple.WebPlaybackInfo { *; }
-keep class com.applemusic.android.apple.PlayAssetInfo { *; }
-keep class com.applemusic.android.apple.AppleSearchResult { *; }
-keep class com.applemusic.android.apple.PageResult { *; }
-keep class com.applemusic.android.apple.Shelf { *; }
-keep class com.applemusic.android.apple.ShelfItem { *; }
-keep class com.applemusic.android.apple.AppleLibrarySync$SongRow { *; }
-keep class com.applemusic.android.apple.AppleLibrarySync$PlaylistRow { *; }
-keep class com.applemusic.android.apple.AppleLibrarySync$Snapshot { *; }
-keep class com.applemusic.android.apple.AppleCache$Entry { *; }
-keep class com.applemusic.android.lyrics.** { *; }
-keep class com.applemusic.lyricstool.data.SongHit { *; }
-keep class com.applemusic.lyricstool.data.FetchedLyrics { *; }
-keep class com.applemusic.lyricstool.data.AppleMusicUserTokenStorage { *; }

# Suppress optional API 36 / Media3 / ICU warnings on older compile/runtime checks
-dontwarn android.icu.text.Transliterator
-dontwarn androidx.media3.**
