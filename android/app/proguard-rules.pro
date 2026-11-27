# R8 only drops library code nothing uses. Everything that is found by name at run time is kept:

# KALKI's own classes: activities, services, receivers, and methods the web page calls (@JavascriptInterface).
-keep class com.growthos.jarvis.** { *; }
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }

# Wake word engine (ONNX Runtime) and "my voice" engine (sherpa-onnx): their native code looks up
# Java classes, fields and methods by name.
-keep class ai.onnxruntime.** { *; }
-keep class com.k2fsa.sherpa.onnx.** { *; }

# Keep names readable in crash reports; renaming saves almost nothing here.
-dontobfuscate
-dontwarn **
