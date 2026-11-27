plugins {
    `java-gradle-plugin`
    id("com.diffplug.spotless") version "8.10.3"
}

group = "com.datadoghq.reggie.build"

// The generator runs on audited JDKs without Gradle dependencies.
val graphemeGenerator = sourceSets.create("graphemeGenerator")

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
    registerFeature("graphemeGenerator") {
        usingSourceSet(graphemeGenerator)
    }
}

gradlePlugin {
    plugins {
        register("graphemeData") {
            id = "reggie.grapheme.data"
            implementationClass = "com.datadoghq.reggie.gradle.GraphemeDataPlugin"
        }
    }
}

testing.suites.named<JvmTestSuite>("test") {
    useJUnitJupiter("6.1.3")
    dependencies {
        implementation(gradleTestKit())
    }
}

val generatorClasses = graphemeGenerator.output.classesDirs
tasks.named<Test>("test") {
    val classpath = generatorClasses
    inputs.files(classpath).withPropertyName("generatorClasses").withPathSensitivity(PathSensitivity.RELATIVE)
    doFirst {
        systemProperty("graphemeGeneratorClasspath", classpath.asPath)
    }
}

spotless {
    java {
        target("src/**/*.java")
        googleJavaFormat("1.35.0")
        licenseHeaderFile(file("../.github/license-header.txt"))
    }
}
