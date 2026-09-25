# Keep JNI entry points (called by name from native code).
-keepclasseswithmembernames class com.ntscrs.android.NtscBridge$Companion {
    native <methods>;
}
-keep class com.ntscrs.android.NtscBridge { *; }
