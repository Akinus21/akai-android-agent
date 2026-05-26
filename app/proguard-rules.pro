-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.akinus21.akaiagent.TunnelNative { *; }
-keepattributes Signature
-dontwarn javax.crypto.**