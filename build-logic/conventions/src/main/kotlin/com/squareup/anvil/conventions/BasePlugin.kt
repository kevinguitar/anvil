package com.squareup.anvil.conventions

import com.rickbusarow.kgx.buildDir
import com.rickbusarow.kgx.extras
import com.rickbusarow.kgx.fromInt
import com.rickbusarow.kgx.getValue
import com.rickbusarow.kgx.javaExtension
import com.rickbusarow.kgx.provideDelegate
import com.squareup.anvil.conventions.utils.isInAnvilBuild
import com.squareup.anvil.conventions.utils.isInAnvilIncludedBuild
import com.squareup.anvil.conventions.utils.isInAnvilRootBuild
import com.squareup.anvil.conventions.utils.libs
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.plugin.KotlinBasePluginWrapper
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.Properties

abstract class BasePlugin : Plugin<Project> {

  open fun beforeApply(target: Project) = Unit
  open fun afterApply(target: Project) = Unit

  abstract fun Project.jvmTargetInt(): Int

  final override fun apply(target: Project) {

    val extension = target.extensions.create("conventions", ConventionsExtension::class.java)

    if (!target.isInAnvilBuild()) {
      target.copyRootProjectGradleProperties()
    }

    beforeApply(target)

    target.plugins.apply("base")

    target.plugins.apply(KtlintConventionPlugin::class.java)

    configureGradleProperties(target)

    configureBuildDirs(target)

    configureJava(target)

    target.plugins.withType(KotlinBasePluginWrapper::class.java) {
      configureKotlin(target, extension)
    }

    configureTests(target)

    target.configurations.configureEach { config ->
      config.resolutionStrategy {
        it.force(target.libs.kotlin.metadata)
      }
    }

    afterApply(target)
  }

  /**
   * Included builds need the `GROUP` and `VERSION_NAME` values from the main build's
   * `gradle.properties`. We can't just use a symlink because Windows exists.
   * See https://github.com/square/anvil/pull/763#discussion_r1379563691
   */
  private fun Project.copyRootProjectGradleProperties() {
    rootProject.file("../../gradle.properties")
      .inputStream()
      .use { Properties().apply { load(it) } }
      .forEach { key, value ->
        extras.set(key.toString(), value.toString())
      }
  }

  private fun configureGradleProperties(target: Project) {
    target.version = target.property("VERSION_NAME") as String
    target.group = target.property("GROUP") as String
  }

  private fun configureKotlin(
    target: Project,
    extension: ConventionsExtension,
  ) {

    target.tasks.withType(KotlinCompile::class.java).configureEach { task ->
      task.compilerOptions {
        allWarningsAsErrors.set(
          target.libs.versions.config.warningsAsErrors.get().toBoolean() ||
            extension.warningsAsErrors.get(),
        )

        val sourceSetName = task.sourceSetName.getOrElse(
          task.name.substringAfter("compile")
            .substringBefore("Kotlin")
            .replaceFirstChar(Char::lowercase),
        )

        freeCompilerArgs.addAll(extension.kotlinCompilerArgs.get())

        fun isTestSourceSet(): Boolean {
          val regex = """(?:gradle|Unit|[aA]ndroid)Test""".toRegex()
          return sourceSetName == "test" || sourceSetName.matches(regex)
        }

        if (extension.explicitApi.get() && !isTestSourceSet()) {
          freeCompilerArgs.add("-Xexplicit-api=strict")
        }

        jvmTarget.set(JvmTarget.fromInt(target.jvmTargetInt()))

        freeCompilerArgs.add("-Xjvm-default=all-compatibility")
      }
    }
  }

  /**
   * The "anvil" projects exist in two different builds, they need two different build directories
   * so that there is no shared mutable state.
   */
  private fun configureBuildDirs(target: Project) {

    when {
      !target.isInAnvilBuild() -> return

      target.isInAnvilRootBuild() -> {
        target.layout.buildDirectory.set(target.file("build/root-build"))
      }

      target.isInAnvilIncludedBuild() -> {
        target.layout.buildDirectory.set(target.file("build/included-build"))
      }
    }

    // Set the kase working directory ('<build directory>/kase/<test|gradleTest>') as a System property,
    // so that it's in the right place for projects with relocated directories.
    // https://github.com/rickbusarow/kase/blob/255db67f40d5ec83e31755bc9ce81b1a2b08cf11/kase/src/main/kotlin/com/rickbusarow/kase/files/HasWorkingDir.kt#L93-L96
    target.tasks.withType(Test::class.java).configureEach { task ->
      task.systemProperty(
        "kase.baseWorkingDir",
        target.buildDir().resolve("kase/${task.name}"),
      )
    }
  }

  private fun configureJava(target: Project) {
    // Sets the toolchain and target versions for java compilation. This waits for the 'java-base'
    // plugin instead of just 'java' for the sake of the KMP integration test project.
    target.plugins.withId("java-base") {
      target.javaExtension.toolchain {
        it.languageVersion.set(JavaLanguageVersion.of(target.libs.versions.jvm.toolchain.get()))
      }
      target.javaExtension.targetCompatibility = JavaVersion.toVersion(target.jvmTargetInt())
      target.tasks.withType(JavaCompile::class.java).configureEach { task ->
        task.options.release.set(target.jvmTargetInt())
      }
    }
  }

  private fun configureTests(target: Project) {
    target.tasks.withType(Test::class.java).configureEach { task ->

      task.maxParallelForks = Runtime.getRuntime().availableProcessors()

      task.useJUnitPlatform {
        it.includeEngines("junit-jupiter", "junit-vintage")
      }

      val testImplementation by target.configurations

      testImplementation.dependencies.addLater(target.libs.junit.jupiter.engine)
      testImplementation.dependencies.addLater(target.libs.junit.vintage.engine)

      task.systemProperties.putAll(
        mapOf(
          // remove parentheses from test display names
          "junit.jupiter.displayname.generator.default" to
            "org.junit.jupiter.api.DisplayNameGenerator\$Simple",

          // Allow unit tests to run in parallel
          // https://junit.org/junit5/docs/snapshot/user-guide/#writing-tests-parallel-execution-config-properties
          "junit.jupiter.execution.parallel.enabled" to true,
          "junit.jupiter.execution.parallel.mode.default" to "concurrent",
          "junit.jupiter.execution.parallel.mode.classes.default" to "concurrent",
        ),
      )

      task.jvmArgs(
        // Fixes illegal reflective operation warnings during tests. It's a Kotlin issue.
        // https://github.com/pinterest/ktlint/issues/1618
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        // Fixes IllegalAccessError: class org.jetbrains.kotlin.kapt3.base.KaptContext [...] in KCT tests
        // https://youtrack.jetbrains.com/issue/KT-45545/Kapt-is-not-compatible-with-JDK-16
        "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.jvm=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
      )

      task.testLogging { logging ->
        logging.events("skipped", "failed")
        logging.exceptionFormat = FULL
        logging.showCauses = true
        logging.showExceptions = true
        logging.showStackTraces = true
        logging.showStandardStreams = false
      }
    }
  }
}
