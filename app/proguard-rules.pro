# R8 shrinks and optimizes the sideload build but does not obfuscate, so crash notes keep raw stack traces.
# Serialization keep rules come from the kotlinx-serialization consumer rules. -printusage lists removed code.
-dontobfuscate
-printusage build/outputs/mapping/r8-usage.txt

-keepattributes RuntimeVisibleAnnotations, AnnotationDefault, InnerClasses, Signature, Exception, EnclosingMethod, SourceFile, LineNumberTable

-dontwarn kotlinx.serialization.**
-dontwarn androidx.compose.**
