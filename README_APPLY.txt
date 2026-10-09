Nirmalam Dhanam - Modern Gradle build-logic overlay
===================================================

Purpose
-------
Replace the old root-script verification approach with a modern included
build containing a precompiled Kotlin convention plugin.

This package DOES NOT contain application source code. It only changes Gradle
build configuration.

Files included
--------------
build.gradle.kts
settings.gradle.kts
app/build.gradle.kts
build-logic/build.gradle.kts
build-logic/settings.gradle.kts
build-logic/src/main/kotlin/dhanam.verification.gradle.kts

Apply
-----
1. Close/stop any running Gradle build.
2. Back up these existing files:
     build.gradle.kts
     settings.gradle.kts
     app/build.gradle.kts
3. Extract this ZIP into the project root and allow the three Gradle files to
   be replaced. The build-logic directory is new.
4. In Android Studio choose Sync Project with Gradle Files.
5. From PowerShell at the project root run:

     .\gradlew.bat clean
     .\gradlew.bat :app:assembleDebug --warning-mode all
     .\gradlew.bat test

Expected structure
------------------
nirmalam-dhanam/
  build.gradle.kts
  settings.gradle.kts
  app/
    build.gradle.kts
  build-logic/
    build.gradle.kts
    settings.gradle.kts
    src/main/kotlin/
      dhanam.verification.gradle.kts

Notes
-----
- No apply(from = ...) script plugin is used.
- The root build applies id("dhanam.verification") through pluginManagement
  includeBuild("build-logic").
- app:preBuild depends only on the aggregate root task verifyDhanam.
- Version remains 1.7.0 / versionCode 17.
- Room integrity guard expects schema 15 and migrations 12->13->14->15.
