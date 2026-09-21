# Keep the JNI bridge class so R8 does not rewrite the name expected by
# libcoulomb_jni.so's Java_com_coulombpainter_CoulombNative_* symbols.
-keep class com.coulombpainter.CoulombNative { *; }
