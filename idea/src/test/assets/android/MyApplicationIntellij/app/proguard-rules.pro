# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ============================================================================
# Minify Test Cases - Keep Rules for Unit Testing
# ============================================================================

# Test Case 1: Keep class name only (members can be obfuscated)
-keep class com.sickworm.jugg.demo.testcase.minify.KeepClassName

# Test Case 2: Keep specific class members (class name can be obfuscated)
-keepclassmembers class com.sickworm.jugg.demo.testcase.minify.KeepClassMembers {
    java.lang.String keptField;
    java.lang.String keptMethod();
}

# Test Case 3: FullyObfuscated - no keep rules (fully obfuscated)
# (intentionally no rules for this class)

# Test Case 4: UnreferencedClass - no keep rules and not referenced (should be removed)
# (intentionally no rules for this class)

# Test Case 5: Keep specific method name
-keepclassmembers class com.sickworm.jugg.demo.testcase.minify.KeepMethodName {
    void keptMethod();
}

# Test Case 6: KeepAnnotated - uses @Keep annotation
# The default Android rules already handle @Keep annotation

# Test Case 7: Interface and implementation
-keep interface com.sickworm.jugg.demo.testcase.minify.MinifyTestInterface { *; }

# Test Case 8: Serializable class - keep serialized fields
-keepclassmembers class com.sickworm.jugg.demo.testcase.minify.SerializableClass {
    java.lang.String serializedField;
    static final long serialVersionUID;
}

# Test Case 9: Enum class - keep enum values
-keepclassmembers enum com.sickworm.jugg.demo.testcase.minify.MinifyTestEnum {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Test Case 10: Inner classes - no special rules, test default behavior
# (intentionally no rules for InnerClassHolder)

# Test Case 11: Native methods - keep native method names
-keepclasseswithmembernames class com.sickworm.jugg.demo.testcase.minify.NativeMethodClass {
    native <methods>;
}

# Test Case 12: Wildcard keep rules - keep members with prefix
-keepclassmembers class com.sickworm.jugg.demo.testcase.minify.WildcardKeepClass {
    *** prefix*;
}

# Test Case 13: Keep class name and all members
-keep class com.sickworm.jugg.demo.testcase.minify.KeepClassAndMembers {
    *;
}
