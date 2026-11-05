# R8 rules for the release build.
#
# The app is deliberately reflection-free: the UI is Compose, state is a ViewModel reachable through
# the default AndroidViewModelFactory, and the QEMU side talks to a real subprocess over sockets and
# files. Only two things need explicit keeps.

# 1) The ViewModel is instantiated by name through AndroidViewModelFactory.
-keep class com.android.gudao.iphoneos.state.AppViewModel { <init>(android.app.Application); }

# 2) Compose runtime keeps lambdas inside synthetic classes; AGP already ships the consumer rules,
#    but restarting the runtime's composer requires the debug-name attributes for readable traces.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 3) The engine talks to `java.lang.ProcessBuilder` and `RandomAccessFile` only; no keep needed.
#    Keep the failure-kind enum names so diagnostics output stays human readable in crash reports.
-keepclassmembers enum com.android.gudao.iphoneos.engine.QemuFailureKind {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
