# ML Kit (and Firebase-style) component registrars are instantiated by
# reflection at startup via their no-arg constructor. If R8 removes that
# constructor, ML Kit silently fails to register and
# TextRecognition.getClient() crashes with a NullPointerException.
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    <init>();
}

# Optional Doubango evaluation engine: loaded by reflection, and its SWIG
# wrapper classes are called from native code (JNI) by name.
-keep class com.example.card_scanner.doubango.** { *; }
-keep class org.doubango.** { *; }
-dontwarn org.doubango.**
