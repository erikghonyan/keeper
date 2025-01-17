/*
 * Copyright (C) 2020 Slack Technologies, LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

@file:Suppress("UnstableApiUsage", "DEPRECATION")

package com.slack.keeper

import com.android.build.gradle.AppExtension
import com.android.build.gradle.api.BaseVariant
import com.android.build.gradle.api.TestVariant
import com.android.build.gradle.internal.publishing.AndroidArtifacts
import com.android.build.gradle.internal.publishing.AndroidArtifacts.ArtifactType
import com.android.build.gradle.internal.tasks.L8DexDesugarLibTask
import com.android.build.gradle.internal.tasks.ProguardConfigurableTask
import com.android.build.gradle.internal.tasks.R8Task
import com.android.builder.model.BuildType
import com.android.builder.model.ProductFlavor
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.UnknownTaskException
import org.gradle.api.artifacts.ArtifactCollection
import org.gradle.api.artifacts.Configuration
import org.gradle.api.file.Directory
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.TaskContainer
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.gradle.util.GradleVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.File
import java.io.IOException
import java.util.Locale

internal const val TAG = "Keeper"
internal const val KEEPER_TASK_GROUP = "keeper"

/**
 * A simple Gradle plugin that hooks into Proguard/R8 to add extra keep rules based on what androidTest classes use from
 * the target app's sources. This is necessary because AGP does not factor in androidTest usages of target app sources
 * when running the minification step, which can result in runtime errors if APIs used by tests are removed.
 *
 * This is a workaround until AGP supports this: https://issuetracker.google.com/issues/126429384.
 *
 * This is optionally configurable via the [`keeper`][KeeperExtension] extension. For example:
 *
 * ```kotlin
 * keeper {
 *   automaticR8RepoManagement = false
 *   r8JvmArgs = ["-Xdebug", "-Xrunjdwp:transport=dt_socket,address=5005,server=y,suspend=y"]
 * }
 * ```
 *
 * The general logic flow:
 * - Create a custom `r8` configuration for the R8 dependency.
 * - Register two jar tasks. One for all the classes in its target `testedVariant` and one for all
 *   the classes in the androidTest variant itself. This will use their variant-provided [JavaCompile]
 *   tasks and [KotlinCompile] tasks if available.
 * - Register a [`infer${androidTestVariant}UsageForKeeper`][InferAndroidTestKeepRules] task that
 *   plugs the two aforementioned jars into R8's `PrintUses` or `TraceReferences` CLI and outputs
 *   the inferred proguard rules into a new intermediate .pro file.
 * - Finally - the generated file is wired in to Proguard/R8 via private task APIs and setting
 *   their `configurationFiles` to include our generated one.
 *
 * Appropriate task dependencies (via inputs/outputs, not `dependsOn`) are set up, so this is automatically run as part
 * of the target app variant's full minified APK.
 *
 * The tasks themselves take roughly ~20 seconds total extra work in the Slack android app, with the infer and app jar
 * tasks each taking around 8-10 seconds and the androidTest jar taking around 2 seconds.
 */
public class KeeperPlugin : Plugin<Project> {

  internal companion object {
    const val INTERMEDIATES_DIR = "intermediates/keeper"
    const val PRINTUSES_DEFAULT_VERSION = "1.6.53"
    const val TRACE_REFERENCES_DEFAULT_VERSION = "3.0.9-dev"
    const val CONFIGURATION_NAME = "keeperR8"
    private val MIN_GRADLE_VERSION = GradleVersion.version("6.0")

    fun interpolateR8TaskName(variantName: String): String {
      return "minify${variantName.capitalize(Locale.US)}WithR8"
    }

    fun interpolateL8TaskName(variantName: String): String {
      return "l8DexDesugarLib${variantName.capitalize(Locale.US)}"
    }
  }

  override fun apply(project: Project) {
    val gradleVersion = GradleVersion.version(project.gradle.gradleVersion)
    check(gradleVersion >= MIN_GRADLE_VERSION) {
      "Keeper requires Gradle 6.0 or later."
    }
    project.pluginManager.withPlugin("com.android.application") {
      val appExtension = project.extensions.getByType<AppExtension>()
      val extension = project.extensions.create<KeeperExtension>("keeper")
      project.configureKeepRulesGeneration(appExtension, extension)
      project.configureL8(appExtension, extension)
    }
  }

  private fun Project.r8TaskFor(variantName: String): TaskProvider<R8Task> {
    return tasks.named<R8Task>(interpolateR8TaskName(variantName))
  }

  /**
   * Configures L8 support via rule sharing and clearing androidTest dex file generation by patching
   * the respective app and test [L8DexDesugarLibTask] tasks.
   *
   * By default, L8 will generate separate rules for test app and androidTest app L8 rules. This
   * can cause problems in minified tests for a couple reasons though! This tries to resolve these
   * via two steps.
   *
   * Issue 1: L8 will try to minify the backported APIs otherwise and can result in conflicting class names
   * between the app and test APKs. This is a little confusing because L8 treats "minified" as
   * "obfuscated" and tries to match. Since we don't care about obfuscating here, we can just
   * disable it.
   *
   * Issue 2: L8 packages `j$` classes into androidTest but doesn't match what's in the target app.
   * This causes confusion when invoking code in the target app from the androidTest classloader
   * and it then can't find some expected `j$` classes. To solve this, we feed the the test app's
   * generated `j$` rules in as inputs to the app L8 task's input rules.
   *
   * More details can be found here: https://issuetracker.google.com/issues/158018485
   *
   * Issue 3: In order for this to work, there needs to only be _one_ dex file generated and it
   * _must_ be the one in the app. This way we avoid classpath conflicts and the one in the app is
   * the source of truth. To force this, we simply clear all the generated output dex files from the
   * androidTest [L8DexDesugarLibTask] task.
   */
  private fun Project.configureL8(
      appExtension: AppExtension,
      extension: KeeperExtension
  ) {
    afterEvaluate {
      if (appExtension.compileOptions.isCoreLibraryDesugaringEnabled) {
        appExtension.onApplicableVariants(project, extension) { testVariant, appVariant ->

          // First merge the L8 rules into the app's L8 task
          val inputFiles = r8TaskFor(testVariant.name)
              .flatMap { it.projectOutputKeepRules }

          tasks
              .named<L8DexDesugarLibTask>(interpolateL8TaskName(appVariant.name))
              .configure {
                val taskName = name
                keepRulesFiles.from(inputFiles)
                keepRulesConfigurations.set(listOf("-dontobfuscate"))
                val diagnosticOutputDir = layout.buildDirectory.dir(
                    "$INTERMEDIATES_DIR/l8-diagnostics/$taskName")
                    .forUseAtConfigurationTime()
                    .get()
                    .asFile

                // We can't actually declare this because AGP's NonIncrementalTask will clear it
                // during the task action
//                  outputs.dir(diagnosticOutputDir)
//                      .withPropertyName("diagnosticsDir")

                if (extension.emitDebugInformation.getOrElse(false)) {
                  doFirst {
                    val mergedFilesContent = keepRulesFiles.files.asSequence()
                        .flatMap { it.walkTopDown() }
                        .filterNot { it.isDirectory }
                        .joinToString("\n") {
                          "# Source: ${it.absolutePath}\n${it.readText()}"
                        }

                    val configurations = keepRulesConfigurations.orNull.orEmpty()
                        .joinToString(
                            "\n",
                            prefix = "# Source: extra configurations\n"
                        )


                    File(diagnosticOutputDir, "patchedL8Rules.pro")
                        .apply {
                          if (exists()) {
                            delete()
                          }
                          parentFile.mkdirs()
                          createNewFile()
                        }
                        .writeText("$mergedFilesContent\n$configurations")
                  }
                }
              }

          // Now clear the outputs from androidTest's L8 task to end with
          tasks
            .named<L8DexDesugarLibTask>(interpolateL8TaskName(testVariant.name))
            .configure {
              doLast {
                clearDir(desugarLibDex.asFile.get())
              }
            }
        }
      }
    }
  }

  private fun clearDir(path: File) {
    if (!path.isDirectory) {
      if (path.exists()) {
        path.deleteRecursively()
      }
      if (!path.mkdirs()) {
        throw IOException(String.format("Could not create empty folder %s", path))
      }
      return
    }

    path.listFiles()?.forEach(File::deleteRecursively)
  }

  private fun Project.configureKeepRulesGeneration(
      appExtension: AppExtension,
      extension: KeeperExtension
  ) {
    // Set up r8 configuration
    val r8Configuration = configurations.create(CONFIGURATION_NAME) {
      description = "R8 dependencies for Keeper. This is used solely for the PrintUses CLI"
      isVisible = false
      isCanBeConsumed = false
      isCanBeResolved = true
      defaultDependencies {
        val version = when (extension.traceReferences.enabled.get()) {
          false -> PRINTUSES_DEFAULT_VERSION
          true -> TRACE_REFERENCES_DEFAULT_VERSION
        }
        logger.debug("keeper r8 default version: $version")
        add(project.dependencies.create("com.android.tools:r8:$version"))
      }
    }

    val androidJarRegularFileProvider = layout.file(provider {
      resolveAndroidEmbeddedJar(appExtension, "android.jar", checkIfExisting = true)
    })
    val androidTestJarRegularFileProvider = layout.file(provider {
      resolveAndroidEmbeddedJar(appExtension, "optional/android.test.base.jar",
          checkIfExisting = false)
    })

    appExtension.testVariants.configureEach {
      val appVariant = testedVariant
      val extensionFilter = extension._variantFilter
      val ignoredVariant = extensionFilter?.let {
        logger.debug(
            "$TAG Resolving ignored status for android variant ${appVariant.name}")
        val filter = VariantFilterImpl(appVariant)
        it.execute(filter)
        logger.debug("$TAG Variant '${appVariant.name}' ignored? ${filter._ignored}")
        filter._ignored
      } ?: !appVariant.buildType.isMinifyEnabled
      if (ignoredVariant) {
        return@configureEach
      }
      if (!appVariant.buildType.isMinifyEnabled) {
        logger.error("""
            Keeper is configured to generate keep rules for the "${appVariant.name}" build variant, but the variant doesn't 
            have minification enabled, so the keep rules will have no effect. To fix this warning, either avoid applying 
            the Keeper plugin when android.testBuildType = ${appVariant.buildType.name}, or use the variant filter feature 
            of the DSL to exclude "${appVariant.name}" from keeper:
              keeper {
                variantFilter {
                  setIgnore(name != <the variant to test>)
                }
              }
            """.trimIndent())
        return@configureEach
      }
    }

    appExtension.onApplicableVariants(project, extension) { testVariant, appVariant ->
      val intermediateAppJar = createIntermediateAppJar(
          appVariant = appVariant,
          emitDebugInfo = extension.emitDebugInformation
      )
      val intermediateAndroidTestJar = createIntermediateAndroidTestJar(
          emitDebugInfo = extension.emitDebugInformation,
          testVariant = testVariant,
          appJarsProvider = intermediateAppJar.flatMap { it.appJarsFile }
      )
      val inferAndroidTestUsageProvider = tasks.register(
          "infer${testVariant.name.capitalize(Locale.US)}KeepRulesForKeeper",
          InferAndroidTestKeepRules(
              variantName = testVariant.name,
              androidTestJarProvider = intermediateAndroidTestJar,
              releaseClassesJarProvider = intermediateAppJar,
              androidJar = androidJarRegularFileProvider,
              androidTestJar = androidTestJarRegularFileProvider,
              automaticallyAddR8Repo = extension.automaticR8RepoManagement,
              enableAssertions = extension.enableAssertions,
              extensionJvmArgs = extension.r8JvmArgs,
              traceReferencesEnabled = extension.traceReferences.enabled,
              traceReferencesArgs = extension.traceReferences.arguments,
              r8Configuration = r8Configuration
          )
      )

      val prop = layout.dir(
          inferAndroidTestUsageProvider.flatMap { it.outputProguardRules.asFile })
      val testProguardFiles = testVariant.runtimeConfiguration
          .proguardFiles()
      applyGeneratedRules(appVariant.name, prop, testProguardFiles)
    }
  }

  private fun resolveAndroidEmbeddedJar(
      appExtension: AppExtension,
      path: String,
      checkIfExisting: Boolean
  ): File {
    val compileSdkVersion = appExtension.compileSdkVersion
        ?: error("No compileSdkVersion found")
    val file = File("${appExtension.sdkDirectory}/platforms/${compileSdkVersion}/${path}")
    check(!checkIfExisting || file.exists()) {
      "No $path found! Expected to find it at: ${file.absolutePath}"
    }
    return file
  }

  private fun AppExtension.onApplicableVariants(
      project: Project,
      extension: KeeperExtension,
      body: (TestVariant, BaseVariant) -> Unit
  ) {
    testVariants.configureEach {
      val testVariant = this
      val appVariant = testedVariant
      val extensionFilter = extension._variantFilter
      val ignoredVariant = extensionFilter?.let {
        project.logger.debug(
            "$TAG Resolving ignored status for android variant ${appVariant.name}")
        val filter = VariantFilterImpl(appVariant)
        it.execute(filter)
        project.logger.debug("$TAG Variant '${appVariant.name}' ignored? ${filter._ignored}")
        filter._ignored
      } ?: false
      if (ignoredVariant) {
        return@configureEach
      }
      if (!appVariant.buildType.isMinifyEnabled) {
        project.logger.error("""
              Keeper is configured to generate keep rules for the "${appVariant.name}" build variant, but the variant doesn't 
              have minification enabled, so the keep rules will have no effect. To fix this warning, either avoid applying 
              the Keeper plugin when android.testBuildType = ${appVariant.buildType.name}, or use the variant filter feature 
              of the DSL to exclude "${appVariant.name}" from keeper:
                keeper {
                  variantFilter {
                    setIgnore(name != <the variant to test>)
                  }
                }
              """.trimIndent())
        return@configureEach
      }

      body(testVariant, appVariant)
    }
  }

  private fun Project.applyGeneratedRules(
      appVariant: String,
      prop: Provider<Directory>,
      testProguardFiles: ArtifactCollection
  ) {
    val targetName = interpolateR8TaskName(appVariant)

    tasks.withType<ProguardConfigurableTask>()
        .matching { it.name == targetName }
        .configureEach {
          logger.debug(
              "$TAG: Patching task '$name' with inferred androidTest proguard rules")
          configurationFiles.from(prop)
          configurationFiles.from(testProguardFiles.artifactFiles)
        }
  }

  /**
   * Creates an intermediate androidTest.jar consisting of all the classes compiled for the androidTest source set.
   * This output is used in the inferAndroidTestUsage task.
   */
  private fun Project.createIntermediateAndroidTestJar(
      emitDebugInfo: Provider<Boolean>,
      testVariant: TestVariant,
      appJarsProvider: Provider<RegularFile>
  ): TaskProvider<out AndroidTestVariantClasspathJar> {
    return tasks.register<AndroidTestVariantClasspathJar>(
        "jar${testVariant.name.capitalize(Locale.US)}ClassesForKeeper") {
      group = KEEPER_TASK_GROUP
      this.emitDebugInfo.value(emitDebugInfo)
      this.appJarsFile.set(appJarsProvider)

      with(testVariant) {
        from(layout.dir(javaCompileProvider.map { it.destinationDir }))
        setArtifacts(runtimeConfiguration.classesJars())
        tasks.providerWithNameOrNull<KotlinCompile>(
            "compile${name.capitalize(Locale.US)}Kotlin")
            ?.let { kotlinCompileTask ->
              from(layout.dir(kotlinCompileTask.map { it.destinationDir }))
            }
      }

      val outputDir = layout.buildDirectory.dir("$INTERMEDIATES_DIR/${testVariant.name}")
      val diagnosticsDir = layout.buildDirectory.dir(
          "$INTERMEDIATES_DIR/${testVariant.name}/diagnostics")
      this.diagnosticsOutputDir.set(diagnosticsDir)
      archiveFile.set(outputDir.map {
        it.file("classes.jar")
      })
    }
  }

  /**
   * Creates an intermediate app.jar consisting of all the classes compiled for the target app variant. This
   * output is used in the inferAndroidTestUsage task.
   */
  private fun Project.createIntermediateAppJar(
      appVariant: BaseVariant,
      emitDebugInfo: Provider<Boolean>
  ): TaskProvider<out VariantClasspathJar> {
    return tasks.register<VariantClasspathJar>(
        "jar${appVariant.name.capitalize(Locale.US)}ClassesForKeeper") {
      group = KEEPER_TASK_GROUP
      this.emitDebugInfo.set(emitDebugInfo)
      with(appVariant) {
        from(layout.dir(javaCompileProvider.map { it.destinationDir }))
        setArtifacts(runtimeConfiguration.classesJars())

        tasks.providerWithNameOrNull<KotlinCompile>(
            "compile${name.capitalize(Locale.US)}Kotlin")
            ?.let { kotlinCompileTask ->
              from(layout.dir(kotlinCompileTask.map { it.destinationDir }))
            }
      }

      val outputDir = layout.buildDirectory.dir("$INTERMEDIATES_DIR/${appVariant.name}")
      val diagnosticsDir = layout.buildDirectory.dir(
          "$INTERMEDIATES_DIR/${appVariant.name}/diagnostics")
      diagnosticsOutputDir.set(diagnosticsDir)
      archiveFile.set(outputDir.map { it.file("classes.jar") })
      appJarsFile.set(outputDir.map { it.file("jars.txt") })
    }
  }
}

private fun Configuration.classesJars(): ArtifactCollection {
  return artifactView(ArtifactType.CLASSES_JAR)
}

private fun Configuration.proguardFiles(): ArtifactCollection {
  return artifactView(ArtifactType.FILTERED_PROGUARD_RULES)
}

private fun Configuration.artifactView(artifactType: ArtifactType): ArtifactCollection {
  return incoming
      .artifactView {
        attributes {
          attribute(
              AndroidArtifacts.ARTIFACT_TYPE,
              artifactType.type
          )
        }
      }
      .artifacts
}

private inline fun <reified T : Task> TaskContainer.providerWithNameOrNull(
    name: String
): TaskProvider<T>? {
  return try {
    named<T>(name)
  } catch (e: UnknownTaskException) {
    null
  }
}

/** Copy of the stdlib version until it's stable. */
internal fun String.capitalize(locale: Locale): String {
  if (isNotEmpty()) {
    val firstChar = this[0]
    if (firstChar.isLowerCase()) {
      return buildString {
        val titleChar = firstChar.toTitleCase()
        if (titleChar != firstChar.toUpperCase()) {
          append(titleChar)
        } else {
          append(this@capitalize.substring(0, 1).toUpperCase(locale))
        }
        append(this@capitalize.substring(1))
      }
    }
  }
  return this
}

private class VariantFilterImpl(variant: BaseVariant) : VariantFilter {
  @Suppress("PropertyName")
  var _ignored: Boolean = false

  override fun setIgnore(ignore: Boolean) {
    _ignored = ignore
  }

  override val buildType: BuildType = variant.buildType
  override val flavors: List<ProductFlavor> = variant.productFlavors
  override val name: String = variant.name
}
