import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    disableAutoTargetJvm() // see core-crypto: lazysodium-java (tests only) declares Java 21
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    api(project(":core-crypto"))
    compileOnly(libs.lazysodium.java)
    compileOnly(libs.jna)

    testImplementation(libs.lazysodium.java)
    testImplementation(libs.jna)
    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
