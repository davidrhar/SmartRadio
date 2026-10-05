# Skipadoodle release shrinking and obfuscation (R8).
#
# Why R8 is on: Google Play asks for DEX obfuscation/optimisation, and it shrinks the app.
# What is NOT enabled, deliberately: isShrinkResources. It buys little here and is a second
# independent way for something to vanish silently at runtime.
#
# The things that break under R8 are the ones that fail at runtime rather than at build time:
#   1. Native code calling back into Java by name (LiteRT / TensorFlow Lite JNI).
#   2. Reflective serialisation (stations are persisted as JSON by kotlinx.serialization; its own
#      consumer rules keep the generated serializers, and property names are string constants).
#   3. Media3 / OkHttp / Coil / Compose ship their own consumer rules.
# Anything added here should be justified by a failure, not by superstition.

# Crash reports from Play must stay readable (the mapping file is embedded in the AAB by AGP and
# picked up by Play automatically).
-keepattributes SourceFile,LineNumberTable,Signature,InnerClasses,EnclosingMethod,*Annotation*
-renamesourcefileattribute SF

# LiteRT / TensorFlow Lite: the JNI layer looks classes and methods up by name.
-keep class org.tensorflow.lite.** { *; }
-keep class com.google.ai.edge.litert.** { *; }
-dontwarn org.tensorflow.lite.**
-dontwarn com.google.ai.edge.litert.**

# Optional OkHttp TLS providers that are referenced but not present on Android.
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
