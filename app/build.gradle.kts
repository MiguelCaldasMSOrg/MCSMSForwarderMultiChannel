import java.io.ByteArrayOutputStream
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

@CacheableTask
abstract class GenerateBuildMetadataTask: DefaultTask() {
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:Internal
    abstract val repositoryDirectory: DirectoryProperty

    @get:Input
    @get:Optional
    abstract val timestampOverride: Property<String>

    @get:Input
    @get:Optional
    abstract val revisionOverride: Property<String>

    @get:Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun generate() {
        val timestamp = timestampOverride.orNull?.toLongOrNull()
            ?: if (timestampOverride.isPresent) {
                throw GradleException("BUILD_TIMESTAMP_EPOCH_MILLIS must be a non-negative integer")
            } else {
                System.currentTimeMillis()
            }
        if (timestamp < 0L) {
            throw GradleException("BUILD_TIMESTAMP_EPOCH_MILLIS must be a non-negative integer")
        }

        val revision = revisionOverride.orNull ?: run {
            val head = gitOutput("rev-parse", "--short=8", "HEAD").ifEmpty { "unknown" }
            val dirty = gitOutput("status", "--porcelain").isNotEmpty()
            head + if (dirty) "-dirty" else ""
        }
        if (!revision.matches(Regex("[0-9A-Za-z._-]{1,32}"))) {
            throw GradleException("BUILD_SOURCE_REVISION must contain only letters, digits, '.', '_', or '-'")
        }

        val packageDirectory = outputDirectory
            .get()
            .dir("com/miguelcaldas/mcsmsforwardermultichannel")
            .asFile
        packageDirectory.mkdirs()
        packageDirectory.resolve("GeneratedBuildMetadata.java").writeText(
            """
            package com.miguelcaldas.mcsmsforwardermultichannel;

            public final class GeneratedBuildMetadata {
                public static final long BUILD_TIME_EPOCH_MILLIS = ${timestamp}L;
                public static final String SOURCE_REVISION = "$revision";

                private GeneratedBuildMetadata() {}
            }
            """.trimIndent() + "\n",
        )
    }

    private fun gitOutput(vararg arguments: String): String {
        val output = ByteArrayOutputStream()
        val result = execOperations.exec {
            workingDir(repositoryDirectory)
            commandLine("git", *arguments)
            standardOutput = output
            errorOutput = ByteArrayOutputStream()
            isIgnoreExitValue = true
        }
        return if (result.exitValue == 0) output.toString(Charsets.UTF_8).trim() else ""
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.miguelcaldas.mcsmsforwardermultichannel"
    compileSdk = libs.versions.compileSdk.get().toInt()

    // Release signing is opt-in. Define these in ~/.gradle/gradle.properties (or pass via
    // -P flags / env vars) to produce a signed release APK; otherwise assembleRelease
    // still works but emits an unsigned APK that cannot be installed without resigning.
    val keystorePath = providers.gradleProperty("RELEASE_KEYSTORE_PATH").orNull
    val keystorePassword = providers.gradleProperty("RELEASE_KEYSTORE_PASSWORD").orNull
    val keyAlias = providers.gradleProperty("RELEASE_KEY_ALIAS").orNull
    val keyPassword = providers.gradleProperty("RELEASE_KEY_PASSWORD").orNull
    val keystoreFile = keystorePath?.let { rootProject.file(it) }
    val hasReleaseSigning = keystoreFile?.exists() == true &&
        !keystorePassword.isNullOrEmpty() &&
        !keyAlias.isNullOrEmpty() &&
        !keyPassword.isNullOrEmpty()

    defaultConfig {
        applicationId = "com.miguelcaldas.mcsmsforwardermultichannel"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 9
        versionName = "1.0.9"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = keystoreFile
                storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                logger.warn(
                    "[MCSMSForwarder] No release signing config detected. " +
                        "assembleRelease will produce an unsigned APK. " +
                        "Set RELEASE_KEYSTORE_PATH/_PASSWORD/RELEASE_KEY_ALIAS/_PASSWORD " +
                        "in ~/.gradle/gradle.properties to enable signing."
                )
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }
}

androidComponents {
    onVariants { variant ->
        val capitalizedVariant = variant.name.replaceFirstChar { it.uppercase() }
        val generateBuildMetadata = tasks.register<GenerateBuildMetadataTask>(
            "generate${capitalizedVariant}BuildMetadata",
        ) {
            outputDirectory.set(layout.buildDirectory.dir("generated/source/buildMetadata/${variant.name}"))
            repositoryDirectory.set(rootProject.layout.projectDirectory)

            providers.gradleProperty("BUILD_TIMESTAMP_EPOCH_MILLIS")
                .orElse(providers.environmentVariable("BUILD_TIMESTAMP_EPOCH_MILLIS"))
                .orNull
                ?.let(timestampOverride::set)

            val explicitRevision = providers.gradleProperty("BUILD_SOURCE_REVISION")
                .orElse(providers.environmentVariable("BUILD_SOURCE_REVISION"))
                .orNull
            val githubRevision = providers.environmentVariable("GITHUB_SHA").orNull?.take(8)
            (explicitRevision ?: githubRevision)?.let(revisionOverride::set)

            outputs.upToDateWhen {
                timestampOverride.isPresent && revisionOverride.isPresent
            }
            outputs.cacheIf {
                timestampOverride.isPresent && revisionOverride.isPresent
            }
        }
        variant.sources.java?.addGeneratedSourceDirectory(
            generateBuildMetadata,
            GenerateBuildMetadataTask::outputDirectory,
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    implementation(libs.androidx.core)

    // Jetpack Compose
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.play.services.code.scanner)
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
    testImplementation(libs.org.json)
}
