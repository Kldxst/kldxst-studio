# WebView 的 JS 接口靠注解反射暴露，方法名必须保留
-keepclassmembers class com.kldxst.studio.console.ConsoleBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.kldxst.studio.console.ConsoleBridge { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**

# Protobuf lite generated messages / BouncyCastle CCM
-keep class com.kldxst.studio.proto.** { *; }
-keep class com.google.protobuf.** { *; }
-dontwarn org.bouncycastle.**
-keep class org.bouncycastle.crypto.** { *; }
