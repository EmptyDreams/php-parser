import org.xbib.gradle.plugin.jflex.JFlexTask

plugins {
    kotlin("jvm") version "2.1.10"
    id("org.xbib.gradle.plugin.jflex") version "3.0.2"
}

group = "top.kmar.php"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

sourceSets {
    main {
        java {
            srcDirs(
                layout.buildDirectory.dir("generated/main/jflex"),
                layout.buildDirectory.dir("generated/main/cup")
            )
        }
    }
}

dependencies {
    implementation("de.jflex:jflex:1.9.1")
    implementation(files("libs/runtime/java-cup-11b-runtime.jar"))

    testImplementation(kotlin("test"))
}

jflex {
    encoding = "UTF-8"
}

tasks.register<JFlexTask>("generateJFlex") {
    source = listOf(file("src/main/resources/php.flex"))
    target = layout.buildDirectory.file("generated/main/jflex").get().asFile
    
    doLast {
        val generatedFile = File(target, "top/kmar/php/PhpLexer.java")
        if (generatedFile.exists()) {
            var content = generatedFile.readText()
            content = content.replace(
                "new java_cup.runtime.Symbol(PhpSymbols.EOF);",
                "factory.newSymbol(PhpSymbols.EOF, ComplexLocation.NO_LOCATION);"
            )
            generatedFile.writeText(content)
        }
    }
}

tasks.register<JavaExec>("generateCup") {
    mainClass.set("java_cup.Main")
    classpath = files("libs/compile/java-cup-11b.jar")
    val outputFile = layout.buildDirectory.dir("generated/main/cup/top/kmar/php").get().asFile
    outputFile.mkdirs()
    args = listOf(
        "-destdir", outputFile.path,
        "-package", "top.kmar.php.parser",
        "-parser", "PhpParser",
        "-symbols", "PhpSymbols",
        "-ast", "Node%s",
        "-compact_red",
        "-nonterms",
        "src/main/resources/php.cup"
    )
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}