import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import de.skyrising.mc.scanner.gen.generateRandomTicksKt
import org.jetbrains.kotlin.config.JvmTarget

plugins {
    kotlin("jvm") version "2.1.0"
    kotlin("plugin.serialization") version "2.1.0"
    id("com.gradleup.shadow") version "9.3.0"
    application
}


repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("reflect"))
    implementation("it.unimi.dsi:fastutil:8.5.13")
    implementation("net.sf.jopt-simple:jopt-simple:6.0-alpha-3")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    implementation("org.jetbrains.kotlin:kotlin-scripting-common")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm")
    //implementation("org.jetbrains.kotlin:kotlin-scripting-dependencies")
    //implementation("org.jetbrains.kotlin:kotlin-scripting-dependencies-maven")
    implementation("org.jetbrains.kotlin:kotlin-scripting-jvm-host")

    testImplementation(kotlin("test"))
}

application {
    mainClass.set("de.skyrising.mc.scanner.ScannerKt")
}

tasks {
    named<ShadowJar>("shadowJar") {
        archiveClassifier.set("")
        mergeServiceFiles()
        minimize {
            exclude(dependency("org.jetbrains.kotlin:.*"))
        }
        dependsOn(distTar, distZip)
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

tasks.withType<JavaCompile> {
    sourceCompatibility = JavaVersion.VERSION_1_8.toString()
    targetCompatibility = JavaVersion.VERSION_1_8.toString()
}


val generatedKotlinDir = project.buildDir.resolve("generated/kotlin")

tasks.create("generateSources") {
    doFirst {
        generateRandomTicksKt().writeTo(generatedKotlinDir)
    }
}

tasks.compileKotlin {
    dependsOn("generateSources")
}

sourceSets {
    main {
        java {
            srcDir(generatedKotlinDir)
        }
    }
}