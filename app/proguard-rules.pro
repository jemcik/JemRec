# What R8 must not touch, and why. Everything not listed here is fair game:
# the libraries ship consumer rules for their own reflection (Conscrypt keeps
# org.conscrypt.** whole for its JNI, WorkManager keeps its workers' and input
# mergers' constructors, Room its databases', Compose and coroutines keep
# theirs), the manifest's components are kept by AGP, and BouncyCastle is
# reached only by direct calls - AdbIdentity never registers it as a provider,
# so nothing looks its classes up by name.
#
# A library's rule can still be wrong. `-keep class * extends X` with no member
# clause keeps the class and lets R8 remove its constructor, so the reflection
# it was kept for fails at run time, and only there: Room 2.5's rule for its
# databases did that (the first launch crashed), and WorkManager 2.9.1's for
# its input mergers (every one-time work failed without running, silently, in
# every release from 0.5 to 0.25.2 - issue #69). The emulator checks install
# the shrunk build for that reason: it goes through R8 with these same rules.
# And CI's tools/check_r8_constructors.py fails a release APK in which a class
# made by name - a component, a worker, an input merger, a database - lost
# the constructor it is made with.

# libadb pairs by reflecting into Conscrypt - Class.forName("org.conscrypt.
# Conscrypt").getMethod("exportKeyingMaterial", ...) for the SPAKE2 channel
# binding, and "org.conscrypt.OpenSSLProvider" for the TLS context. Both are
# already kept by Conscrypt's own rules; this is the record of why they must
# be, in case those rules are ever tightened upstream.
-keep class org.conscrypt.Conscrypt { *; }
-keep class org.conscrypt.OpenSSLProvider { *; }

# Conscrypt's compatibility shims for Android 4.x name two platform-private
# classes that no longer exist, and R8 refuses to build on an unresolved
# reference unless told it is expected. These two are the whole list R8
# produced; nothing else in the graph - BouncyCastle included - is unresolved.
-dontwarn com.android.org.conscrypt.SSLParametersImpl
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl

# Shrink and optimise, but keep every name. Measured: renaming saves 0.3 MB on
# a 6.7 MB APK. Against that, a crash line or an exception in the log - and
# this app logs exception class names on purpose, in the self-test and the
# daemon supervisor - would read "a.b: ..." to the person reporting it, and
# to whoever reads the report, unless a mapping file is published with every
# release and applied by hand. Not worth 0.3 MB.
-dontobfuscate
