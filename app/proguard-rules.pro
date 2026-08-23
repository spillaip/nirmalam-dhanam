# Compose, Room, and Kotlin serialization provide their own consumer rules.
# Keep Room's reflective database implementation reachable in optimized release builds.
-keep class * extends androidx.room.RoomDatabase { *; }

# SQLCipher is loaded by the Android runtime/native linker.
-keep class net.zetetic.database.sqlcipher.** { *; }
