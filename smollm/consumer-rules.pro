# Keep native methods
-keepclasseswithmembernames class me.fss.orbal.smollm.** {
    native <methods>;
}
-keep class me.fss.orbal.smollm.SmolLM { *; }
-keep class me.fss.orbal.smollm.GGUFReader { *; }
