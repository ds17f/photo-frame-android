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
