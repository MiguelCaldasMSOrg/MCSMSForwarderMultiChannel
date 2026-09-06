# ShortcutBadger stores vendor implementation classes in a Class list and creates them through
# Class.newInstance(). Keep their public no-argument constructors available after optimization.
-keepclassmembers class me.leolin.shortcutbadger.impl.** {
    public <init>();
}

# Firebase/ML Kit discovers ComponentRegistrar implementations from manifest metadata and creates
# them reflectively. Code Scanner 16.1.0 still brings firebase-components 16.1.0, whose consumer
# rule predates R8 full mode. Use Firebase's current rule so registrar wiring is not optimized away.
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    void <init>();
}
-keep,allowshrinking interface com.google.firebase.components.ComponentRegistrar
