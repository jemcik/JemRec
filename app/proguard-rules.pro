# What R8 must not touch, and why. Everything not listed here is fair game:
# the libraries ship consumer rules for their own reflection (Conscrypt keeps
# org.conscrypt.** whole for its JNI, WorkManager keeps Worker subclasses by
# name, Compose and coroutines keep theirs), the manifest's components are kept
# by AGP, and BouncyCastle is reached only by direct calls - AdbIdentity never
# registers it as a provider, so nothing looks its classes up by name.

# libadb pairs by reflecting into Conscrypt - Class.forName("org.conscrypt.
# Conscrypt").getMethod("exportKeyingMaterial", ...) for the SPAKE2 channel
# binding, and "org.conscrypt.OpenSSLProvider" for the TLS context. Both are
# already kept by Conscrypt's own rules; this is the record of why they must
# be, in case those rules are ever tightened upstream.
-keep class org.conscrypt.Conscrypt { *; }
-keep class org.conscrypt.OpenSSLProvider { *; }

# WorkManager's database is Room, and Room finds its generated implementation
# by building the class name at runtime - Class.forName(canonicalName +
# "_Impl") - then calling newInstance(). Room 2.5.0, which WorkManager 2.9.1
# pulls in, ships the rule `-keep class * extends androidx.room.RoomDatabase`
# with no member clause, so R8 keeps the class and removes its unreferenced
# no-arg constructor. Measured on the phone: the first launch of the shrunk
# build died inside androidx.startup with "Failed to create an instance of
# WorkDatabase" - an InstantiationException, the class present, its
# constructor gone. Room 2.6 fixed its rule to what this says; until the
# WorkManager in the graph brings that Room, this says it here.
-keep class * extends androidx.room.RoomDatabase { void <init>(); }

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
