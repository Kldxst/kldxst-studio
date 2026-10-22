# 穿戴 SDK 的回调是反射调用的，类名不能混淆
-keep class com.xiaomi.xms.wearable.** { *; }
-keep class org.zxor.oronbox.** { *; }
-keepclassmembers class * implements com.xiaomi.xms.wearable.tasks.OnSuccessListener { *; }
-keepclassmembers class * implements com.xiaomi.xms.wearable.tasks.OnFailureListener { *; }
-keepclassmembers class * implements com.xiaomi.xms.wearable.message.OnMessageReceivedListener { *; }
-keepclassmembers class * implements com.xiaomi.xms.wearable.node.OnDataChangedListener { *; }

# WebView 的 JS 接口靠注解反射暴露，方法名必须保留
-keepclassmembers class com.kldxst.studio.console.ConsoleBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.kldxst.studio.console.ConsoleBridge { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
