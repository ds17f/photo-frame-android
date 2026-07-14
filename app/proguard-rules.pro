# kotlinx.serialization — keep @Serializable classes and their generated serializers.
-keepattributes *Annotation*, InnerClasses

# Fire OS 7 (API 28) has no java.lang.ClassValue (added API 33). kotlinx.serialization guards
# this at runtime (Class.forName → ConcurrentHashMapCache fallback), but R8 full mode inlines/
# merges ClassValueCache into the deserialization hot path, baking a raw ClassValue reference
# into a class ART then fails to verify → NoClassDefFoundError every JSON parse. Keep these
# classes intact so the reference stays isolated and the runtime guard actually works.
-keep class kotlinx.serialization.internal.ClassValue** { *; }
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.photoframe.**$$serializer { *; }
-keepclassmembers class com.photoframe.** {
    *** Companion;
}
-keepclasseswithmembers class com.photoframe.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Retrofit / OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keepattributes Signature, Exceptions

# AppAuth
-keep class net.openid.appauth.** { *; }

# security-crypto → Tink references compile-only errorprone annotations that aren't on the
# runtime classpath. Safe to ignore (annotations only).
-dontwarn com.google.errorprone.annotations.**

# ViewModels are instantiated reflectively by ViewModelProvider (it looks up the
# (Application) constructor for AndroidViewModel). Keep those constructors so the minified
# release build doesn't crash on launch.
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keepclassmembers class * extends androidx.lifecycle.AndroidViewModel {
    <init>(android.app.Application);
}

# Room generated implementations are referenced reflectively by name.
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-dontwarn androidx.room.paging.**
