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
        "-ast_flatten", "list=_LIST&namelessInline=+_nl_inline&inline=+_inline",
        "src/main/resources/php.cup"
    )
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}