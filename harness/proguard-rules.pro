# ThermalBench app ProGuard/R8 rules.

-keepattributes Signature,*Annotation*,SourceFile,LineNumberTable

# --- kotlinx.serialization ----------------------------------------------------
# The result document schema is a published contract; its generated serializers
# must survive shrinking or exported documents lose their field names.
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * { @kotlinx.serialization.Serializable <fields>; }
-keep,includedescriptorclasses class com.thermalbench.**$$serializer { *; }
-keepclassmembers class com.thermalbench.** { *** Companion; }

# --- MediaPipe Tasks GenAI ----------------------------------------------------
# MediaPipe's option classes are generated with AutoValue, whose annotations are
# compile-time only and are deliberately absent at runtime. Without this R8
# stops on "Missing class com.google.auto.value.AutoValue".
-dontwarn com.google.auto.value.**
-dontwarn javax.lang.model.element.**

# MediaPipe's GenAI task declares image entry points that live in the vision
# artifact. ThermalBench runs a text-only workload and does not depend on it,
# so these references are unreachable rather than broken.
-dontwarn com.google.mediapipe.framework.image.**

# The inference runtimes reach their Java classes from native code, so shrinking
# them away produces a UnsatisfiedLinkError only at run time, an hour into a run.
-keep class com.google.mediapipe.** { *; }
-keep class com.google.ai.edge.litertlm.** { *; }

# --- llama.cpp JNI ------------------------------------------------------------
# The native library resolves these by exact name.
-keep class com.thermalbench.backend.llamacpp.LlamaCppNative { *; }

# --- Coroutines / OkHttp ------------------------------------------------------
-dontwarn kotlinx.coroutines.**
-dontwarn okhttp3.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
