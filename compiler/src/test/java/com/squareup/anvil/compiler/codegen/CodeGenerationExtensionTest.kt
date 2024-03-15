package com.squareup.anvil.compiler.codegen

import com.google.common.truth.Truth.assertThat
import com.rickbusarow.kase.HasTestEnvironmentFactory
import com.squareup.anvil.annotations.ContributesTo
import com.squareup.anvil.compiler.api.createGeneratedFile
import com.squareup.anvil.compiler.compile
import com.squareup.anvil.compiler.contributesToFqName
import com.squareup.anvil.compiler.internal.reference.classAndInnerClassReferences
import com.squareup.anvil.compiler.internal.testing.SimpleCodeGenerator
import com.squareup.anvil.compiler.internal.testing.SimpleSourceFileTrackingBehavior.NO_SOURCE_TRACKING
import com.squareup.anvil.compiler.internal.testing.SimpleSourceFileTrackingBehavior.TRACKING_WITH_NO_SOURCES
import com.squareup.anvil.compiler.internal.testing.SimpleSourceFileTrackingBehavior.TRACK_SOURCE_FILES
import com.squareup.anvil.compiler.internal.testing.simpleCodeGenerator
import com.squareup.anvil.compiler.isError
import com.squareup.anvil.compiler.testing.AnvilEmbeddedCompilationTestEnvironment
import com.tschuchort.compiletesting.KotlinCompilation.ExitCode.COMPILATION_ERROR
import com.tschuchort.compiletesting.KotlinCompilation.ExitCode.OK
import io.kotest.matchers.file.shouldExist
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CodeGenerationExtensionTest : HasTestEnvironmentFactory<AnvilEmbeddedCompilationTestEnvironment.Factory> {

  override val testEnvironmentFactory = AnvilEmbeddedCompilationTestEnvironment.Factory

  @Test fun `generated files with the same path and different content are an error`() = test {
    val codeGenerator = simpleCodeGenerator { clazz ->
      clazz
        .takeIf { it.isInterface() }
        ?.let {
          //language=kotlin
          """
          package generated.com.squareup.test
          
          class Abc
          
          private const val abc = "${clazz.shortName}"
        """
        }
    }

    compile(
      """
      package com.squareup.test

      interface ComponentInterface1
      
      interface ComponentInterface2
      """,
      codeGenerators = listOf(codeGenerator),
    ) {
      assertThat(exitCode).isError()

      val abcPath = outputDirectory
        .resolveSibling("build/anvil/generated/com/squareup/test/Abc.kt")
        .absolutePath

      assertThat(messages).contains(
        """
        There were duplicate generated files. Generating and overwriting the same file leads to unexpected results.

        The file was generated by: ${SimpleCodeGenerator::class}
        The file is: $abcPath
        """.trimIndent(),
      )
    }
  }

  @Test fun `generated files with the same path and same content are allowed`() = test {
    val codeGenerator = simpleCodeGenerator { clazz ->
      clazz
        .takeIf { it.isInterface() }
        ?.let {
          //language=kotlin
          """
          package generated.com.squareup.test
          
          class Abc
        """
        }
    }

    compile(
      """
      package com.squareup.test

      interface ComponentInterface1
      
      interface ComponentInterface2
      """,
      codeGenerators = listOf(codeGenerator),
    ) {
      assertThat(exitCode).isEqualTo(OK)
    }
  }

  @Test fun `a code generator that is not applicable is never called`() = test {

    val codeGenerator = simpleCodeGenerator(applicable = { false }) { null }

    //language=kotlin
    val componentInterface = """
      package com.squareup.test

      interface ComponentInterface
    """.trimIndent()

    compile(
      componentInterface,
      codeGenerators = listOf(codeGenerator),
    ) {
      assertThat(exitCode).isEqualTo(OK)
      assertThat(codeGenerator.isApplicableCalls).isEqualTo(1)
      assertThat(codeGenerator.getGenerateCallsForInputFileContent(componentInterface)).isEqualTo(0)
    }
  }

  @Test fun `compiling with no source tracking and trackSourceFiles enabled throws an exception`() = test {

    val codeGenerator = simpleCodeGenerator(NO_SOURCE_TRACKING) { clazz ->

      if (clazz.shortName == "Abc") {
        return@simpleCodeGenerator null
      }

      """
        package com.squareup.test
          
        class Abc
      """.trimIndent()
    }

    //language=kotlin
    val componentInterface = """
      package com.squareup.test
  
      interface ComponentInterface
    """.trimIndent()

    compile(
      componentInterface,
      codeGenerators = listOf(codeGenerator),
      trackSourceFiles = true,
    ) {
      assertThat(codeGenerator.isApplicableCalls).isEqualTo(1)
      assertThat(codeGenerator.getGenerateCallsForInputFileContent(componentInterface)).isEqualTo(1)

      assertThat(exitCode).isEqualTo(COMPILATION_ERROR)

      val abcPath = outputDirectory
        .resolveSibling("build/anvil/com/squareup/test/Abc.kt")
        .absolutePath

      assertThat(messages).contains(
        """
        |Source file tracking is enabled but this generated file is not tracking them.
        |Please report this issue to the code generator's maintainers.
        |
        |The file was generated by: ${SimpleCodeGenerator::class}
        |The file is: $abcPath
        |
        |To stop this error, disable the `trackSourceFiles` property in the Anvil Gradle extension:
        |
        |   // build.gradle(.kts)
        |   anvil {
        |     trackSourceFiles = false
        |   }
        |
        |or disable the property in `gradle.properties`:
        |
        |   # gradle.properties
        |   com.squareup.anvil.trackSourceFiles=false
        |
        """.trimMargin(),

      )
    }
  }

  @Test fun `a code generator that tracks an empty source list can use trackSourceFiles`() = test {

    val codeGenerator = simpleCodeGenerator(TRACKING_WITH_NO_SOURCES) { clazz ->

      if (clazz.shortName == "Abc") {
        return@simpleCodeGenerator null
      }

      """
        package com.squareup.test
          
        class Abc
      """.trimIndent()
    }

    //language=kotlin
    val componentInterface = """
      package com.squareup.test

      interface ComponentInterface
    """.trimIndent()

    compile(
      componentInterface,
      codeGenerators = listOf(codeGenerator),
      trackSourceFiles = true,
    ) {
      assertThat(codeGenerator.isApplicableCalls).isEqualTo(1)
      assertThat(codeGenerator.getGenerateCallsForInputFileContent(componentInterface)).isEqualTo(1)

      assertThat(exitCode).isEqualTo(OK)
      assertThat(classLoader.loadClass("com.squareup.test.Abc")).isNotNull()
    }
  }

  @Test
  fun `a code generator that tracks a single source per generated file can use trackSourceFiles`() = test {

    val codeGenerator = simpleCodeGenerator(TRACK_SOURCE_FILES) { clazz ->

      if (clazz.shortName == "Abc") {
        return@simpleCodeGenerator null
      }

      """
        package com.squareup.test
          
        class Abc
      """.trimIndent()
    }

    //language=kotlin
    val componentInterface = """
      package com.squareup.test

      interface ComponentInterface
    """.trimIndent()

    compile(
      componentInterface,
      codeGenerators = listOf(codeGenerator),
      trackSourceFiles = true,
    ) {
      assertThat(codeGenerator.isApplicableCalls).isEqualTo(1)
      assertThat(codeGenerator.getGenerateCallsForInputFileContent(componentInterface)).isEqualTo(1)

      assertThat(exitCode).isEqualTo(OK)
      assertThat(classLoader.loadClass("com.squareup.test.Abc")).isNotNull()
    }
  }

  @Test
  fun `a code generator that does not track sources can compile with trackSourceFiles disabled`() = test {
    val codeGenerator = simpleCodeGenerator(NO_SOURCE_TRACKING) { clazz ->

      if (clazz.shortName == "Abc") {
        return@simpleCodeGenerator null
      }

      """
        package com.squareup.test
          
        class Abc
      """.trimIndent()
    }

    //language=kotlin
    val componentInterface = """
      package com.squareup.test
  
      interface ComponentInterface
    """.trimIndent()

    compile(
      componentInterface,
      codeGenerators = listOf(codeGenerator),
      trackSourceFiles = false,
    ) {
      assertThat(codeGenerator.isApplicableCalls).isEqualTo(1)
      assertThat(codeGenerator.getGenerateCallsForInputFileContent(componentInterface)).isEqualTo(1)

      assertThat(exitCode).isEqualTo(OK)
      assertThat(classLoader.loadClass("com.squareup.test.Abc")).isNotNull()
    }
  }

  @Test
  fun `a generated file can reference another generated file in an incremental build`() = test {

    var replaceArgResolved = false

    val codeGenerator = simpleCodeGenerator { codeGenDir, module, files ->

      files.classAndInnerClassReferences(module)
        .mapNotNull { clazz ->

          if (clazz.shortName == "DebugTypeAExtraModule") {
            val contributesToAnnotation = clazz.annotations
              .single { it.fqName == contributesToFqName }

            val replacedName = contributesToAnnotation.replaces().single().fqName.asString()

            replacedName shouldBe "extra.anvil.RealTypeAExtraModule"
            replaceArgResolved = true
          }

          clazz.annotations
            .singleOrNull { it.fqName.asString() == "anvil.Trigger" }
            ?.let { annotation -> clazz to annotation }
        }
        .map { (clazz, annotation) ->
          val packageFqName = clazz.packageFqName
          val shortName = clazz.shortName

          val superRef = clazz.directSuperTypeReferences()
            .single()
            .asClassReference()

          val packagePrefix = "extra"

          val supPackage = superRef.packageFqName
          val supSimpleName = superRef.shortName

          val scope = annotation.scope().fqName
          val replaces = annotation.replaces(1)

          val replacesString = if (replaces.isEmpty()) {
            ""
          } else {
            ", replaces = [${replaces.joinToString { "${it.shortName}ExtraModule::class" }}]"
          }

          //language=kotlin
          val content = """
            package $packagePrefix.$packageFqName
              
            import $packageFqName.$shortName
            import $scope
            import $supPackage.$supSimpleName
            import com.squareup.anvil.annotations.ContributesTo
            import dagger.Binds
            import dagger.Module
              
            @Module
            @ContributesTo(${scope.shortName()}::class$replacesString)
            interface ${shortName}ExtraModule {
              @Binds
              fun bind(b: $shortName): $supSimpleName
            }
          """.trimIndent()
          createGeneratedFile(
            codeGenDir = codeGenDir,
            packageName = "$packagePrefix.${packageFqName.asString()}",
            fileName = "${shortName}ExtraModule",
            content = content,
            sourceFile = clazz.containingFileAsJavaFile,
          )
        }.toList()
    }

    //language=kotlin
    val trigger = """
      package anvil

      import kotlin.reflect.KClass

      annotation class Trigger(
        val scope: KClass<*>,
        val replaces: Array<KClass<*>> = []
      ) 

      interface TypeA
    """.trimIndent()

    //language=kotlin
    val realTypeA = """
      package anvil

      import javax.inject.Inject
    
      @Trigger(Any::class)
      class RealTypeA @Inject constructor() : TypeA
    """.trimIndent()

    val result1 = compile(
      """
        package anvil
  
        import javax.inject.Inject
  
        @Trigger(Any::class, replaces = [RealTypeA::class]) 
        class DebugTypeA @Inject constructor() : TypeA
      """,
      trigger,
      realTypeA,
      codeGenerators = listOf(codeGenerator),
      trackSourceFiles = true,
    ) {

      exitCode shouldBe OK
      replaceArgResolved shouldBe true
    }

    val realExtraModule = workingDir.resolve("build/anvil")
      .resolve("extra/anvil/RealTypeAExtraModule.kt")

    realExtraModule.shouldExist()
    realExtraModule.delete() shouldBe true

    replaceArgResolved = false

    // Build a second time, with a change to the DebugTypeA file.
    // This is as close as we can get to an incremental build using KCT:
    // - The earlier build result is included, so the .class files are in the classpath.
    // - The other source files are unchanged, and not added to this compilation.
    // - The unchanged files still exist, so Anvil's incremental logic will see them and restore
    //   their associated generated files accordingly.
    compile(
      """
        package anvil
  
        import javax.inject.Inject
  
        @Trigger(Any::class, replaces = [RealTypeA::class]) 
        class DebugTypeA @Inject constructor() : TypeA {
          fun noOp() = Unit
        }
      """.trimIndent(),
      previousCompilationResult = result1,
      codeGenerators = listOf(codeGenerator),
      trackSourceFiles = true,
    ) {
      exitCode shouldBe OK
      replaceArgResolved shouldBe true

      classLoader.loadClass("extra.anvil.DebugTypeAExtraModule")
        .getAnnotation(ContributesTo::class.java)
        .replaces
        .single()
        .qualifiedName shouldBe "extra.anvil.RealTypeAExtraModule"
    }
  }

  @Test fun `errors that require an opt-in annotation are suppressed in generated code`() {
    compile(
      """
      package com.squareup.test

      import com.squareup.anvil.annotations.ContributesBinding
      import javax.inject.Inject

      @Retention(AnnotationRetention.BINARY)
      @RequiresOptIn(
          message = "",
          level = RequiresOptIn.Level.ERROR
      )
      @MustBeDocumented
      annotation class InternalApi

      interface Type

      @InternalApi
      @ContributesBinding(Unit::class)
      class SomeClass @Inject constructor() : Type 
      """,
    ) {
      assertThat(exitCode).isEqualTo(OK)
    }
  }
}
