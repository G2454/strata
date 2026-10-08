# Strata uses no reflection; library consumer rules cover Media3 and Coil.
-dontwarn org.jspecify.annotations.**
# Optional TLS providers referenced by OkHttp (pulled in by Coil) and annotation-only deps.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
-dontwarn org.checkerframework.**
