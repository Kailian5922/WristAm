# Phone Companion (:phone) ProGuard / R8 rules

# Keep Android manifest components
-keep class com.applemusic.lyricstool.phone.PhoneMainActivity { *; }
-keep class com.applemusic.lyricstool.phone.AppleMusicLoginActivity { *; }

# Consumer rules from :shared and Play Services Wearable handle library internals
-dontwarn com.google.android.gms.wearable.**
-dontwarn androidx.webkit.**
