import java.util.Properties

/*
 * Dhanam verification convention plugin.
 *
 * Important: resolve project paths and file trees during configuration so task
 * actions do not call Task.project at execution time. This keeps the checks
 * compatible with Gradle 10's direction and avoids the Task.project warning.
 */

val rootDirectory = layout.projectDirectory.asFile
val appBuildGradleFile = rootDirectory.resolve("app/build.gradle.kts")
val gradlePropertiesFile = rootDirectory.resolve("gradle.properties")
val manifestFile = rootDirectory.resolve("app/src/main/AndroidManifest.xml")
val resourceDirectory = rootDirectory.resolve("app/src/main/res")
val packageRoot = rootDirectory.resolve(
    "app/src/main/java/com/nirmalamgroup/nirmalamdhanam"
)
val databaseFile = packageRoot.resolve("data/local/NirmalamDatabase.kt")

val dhanamSourceTree = fileTree(rootDirectory.resolve("app/src/main/java")) {
    include("**/*.kt")
}

fun readDhanamSources(): String =
    dhanamSourceTree.files
        .sortedBy { it.absolutePath }
        .joinToString("\n") { it.readText() }

val verifyModernAgpConfiguration =
    tasks.register("verifyModernAgpConfiguration") {
        group = "verification"
        description = "Verify Dhanam modern AGP/Kotlin configuration."

        doLast {
            val appBuild =
                if (appBuildGradleFile.isFile) appBuildGradleFile.readText() else ""

            val gradleProperties = Properties().apply {
                if (gradlePropertiesFile.isFile) {
                    gradlePropertiesFile.inputStream().use(::load)
                }
            }

            check(!appBuild.contains("""id("org.jetbrains.kotlin.android")""")) {
                "Legacy org.jetbrains.kotlin.android plugin detected."
            }

            check(!appBuild.contains("kotlinOptions")) {
                "Legacy kotlinOptions configuration detected."
            }

            check(
                gradleProperties.getProperty("android.builtInKotlin")
                    ?.equals("false", ignoreCase = true) != true
            ) {
                "Remove android.builtInKotlin=false from gradle.properties."
            }

            check(
                gradleProperties.getProperty("android.newDsl")
                    ?.equals("false", ignoreCase = true) != true
            ) {
                "Remove android.newDsl=false from gradle.properties."
            }

            println("Modern AGP/Kotlin configuration verified.")
        }
    }

val verifyNoDeadSmsIngestion =
    tasks.register("verifyNoDeadSmsIngestion") {
        group = "verification"
        description = "Verify removed SMS ingestion does not return."

        doLast {
            val sources = readDhanamSources()
            val manifest = if (manifestFile.isFile) manifestFile.readText() else ""

            check(!manifest.contains("android.permission.RECEIVE_SMS")) {
                "RECEIVE_SMS permission must not be present."
            }

            check(!manifest.contains("android.permission.READ_SMS")) {
                "READ_SMS permission must not be present."
            }

            check(!manifest.contains("android.provider.Telephony.SMS_RECEIVED")) {
                "SMS_RECEIVED receiver must not be present."
            }

            check(!sources.contains("SmsReceiver")) {
                "Dead SmsReceiver code detected."
            }

            check(!sources.contains("SmsDispatcher")) {
                "Dead SmsDispatcher code detected."
            }
        }
    }

val verifyNoPackagedStoreArtwork =
    tasks.register("verifyNoPackagedStoreArtwork") {
        group = "verification"
        description = "Verify Play Store artwork is not packaged in the app."

        doLast {
            if (!resourceDirectory.exists()) return@doLast

            val forbiddenMarkers = listOf(
                "feature_graphic",
                "feature-graphic",
                "store_graphic",
                "store-graphic",
                "play_store",
                "play-store",
                "promo_graphic",
                "promo-graphic",
            )

            val offending =
                resourceDirectory
                    .walkTopDown()
                    .filter { it.isFile }
                    .filter { candidate ->
                        forbiddenMarkers.any { marker ->
                            candidate.name.lowercase().contains(marker)
                        }
                    }
                    .toList()

            check(offending.isEmpty()) {
                buildString {
                    appendLine("Play Store artwork must remain outside app/src/main/res.")
                    offending.forEach { file ->
                        appendLine(" - ${file.relativeTo(rootDirectory)}")
                    }
                }
            }
        }
    }

val verifySourceArchitecture =
    tasks.register("verifySourceArchitecture") {
        group = "verification"
        description = "Verify Dhanam source architecture."

        doLast {
            check(packageRoot.isDirectory) {
                "Dhanam source package directory is missing."
            }

            val requiredFiles = listOf(
                "MainActivity.kt",
                "DhanamApp.kt",
                "FinanceState.kt",
                "NirmalamMvpViewModel.kt",
                "DhanamNavigation.kt",
                "HomeScreen.kt",
                "HomeCharts.kt",
                "UiSupport.kt",
                "TransactionScreens.kt",
                "InvestmentScreens.kt",
                "InsightsScreen.kt",
                "SettingsScreens.kt",
            )

            val missing = requiredFiles.filterNot { filename ->
                packageRoot.resolve(filename).isFile
            }

            check(missing.isEmpty()) {
                "Missing Dhanam architecture files: ${missing.joinToString()}"
            }
        }
    }

val verifyCumulativeIntegrity =
    tasks.register("verifyCumulativeIntegrity") {
        group = "verification"
        description = "Verify cumulative Dhanam data-model integrity."

        doLast {
            val sources = readDhanamSources()

            check(databaseFile.isFile) {
                "NirmalamDatabase.kt is missing."
            }

            val databaseSource = databaseFile.readText()

            val schemaVersionIs15 =
                Regex("""const\s+val\s+SCHEMA_VERSION\s*=\s*15\b""")
                    .containsMatchIn(databaseSource) ||
                    Regex("""version\s*=\s*15\b""")
                        .containsMatchIn(databaseSource)

            val databaseUsesSchemaVersion =
                Regex(
                    """version\s*=\s*(?:NirmalamDatabase\.)?SCHEMA_VERSION\b"""
                ).containsMatchIn(databaseSource) ||
                    Regex("""version\s*=\s*15\b""")
                        .containsMatchIn(databaseSource)

            check(schemaVersionIs15 && databaseUsesSchemaVersion) {
                "Room database must remain at schema version 15."
            }

            check(
                databaseSource.contains("MIGRATION_12_13") ||
                    Regex("""Migration\s*\(\s*12\s*,\s*13\s*\)""")
                        .containsMatchIn(databaseSource)
            ) {
                "Room migration 12 -> 13 is missing."
            }

            check(
                databaseSource.contains("MIGRATION_13_14") ||
                    Regex("""Migration\s*\(\s*13\s*,\s*14\s*\)""")
                        .containsMatchIn(databaseSource)
            ) {
                "Room migration 13 -> 14 is missing."
            }

            check(
                databaseSource.contains("MIGRATION_14_15") ||
                    Regex("""Migration\s*\(\s*14\s*,\s*15\s*\)""")
                        .containsMatchIn(databaseSource)
            ) {
                "Room migration 14 -> 15 is missing."
            }

            listOf(
                "sourceFingerprint",
                "sourceKind",
                "reconciledAtEpochMs",
            ).forEach { required ->
                check(sources.contains(required)) {
                    "Smart reconciliation field '$required' is missing."
                }
            }

            check(sources.contains("CategoryPriority")) {
                "CategoryPriority support is missing."
            }

            check(sources.contains("CategoryNature")) {
                "CategoryNature support is missing."
            }

            check(sources.contains("AccountRolePolicy")) {
                "AccountRolePolicy is missing."
            }

            check(sources.contains("InvestmentDeltaEngine")) {
                "InvestmentDeltaEngine is missing."
            }

            check(sources.contains("FinancialTimeline")) {
                "FinancialTimeline is missing."
            }

            println("Dhanam integrity verified: Room 12 -> 13 -> 14 -> 15.")
        }
    }

val verifyDhanam =
    tasks.register("verifyDhanam") {
        group = "verification"
        description = "Run all Dhanam project integrity checks."

        dependsOn(
            verifyModernAgpConfiguration,
            verifyNoDeadSmsIngestion,
            verifyNoPackagedStoreArtwork,
            verifySourceArchitecture,
            verifyCumulativeIntegrity,
        )
    }
