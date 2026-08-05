-keepclasseswithmembernames class * {
    native <methods>;
}
-keep class com.thecryptosteer.xmrminer.nativebridge.** { *; }
-keep class com.thecryptosteer.xmrminer.service.NativeCallbacks { *; }
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
