import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    // lazysodium-java 5.2 declares Java 21; it is only used to compile against and to run tests on
    // a computer (JDK 21). The module itself still emits Java 17 bytecode for Android.
    disableAutoTargetJvm()
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    // The crypto core is written against LazySodium's shared API. At runtime the app supplies the
    // Android build (lazysodium-android); tests on a computer use the desktop build (lazysodium-java).
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
