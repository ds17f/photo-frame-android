# kotlinx.serialization — keep @Serializable classes and their generated serializers.
-keepattributes *Annotation*, InnerClasses
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
