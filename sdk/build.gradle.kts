plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.vanniktech.maven.publish")
}
group = "com.usesonny"
version = "1.0.0"

android {
    namespace = "com.usesonny.sdk"
    compileSdk = 36
    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SDK_VERSION", "\"${project.version}\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation("androidx.webkit:webkit:1.16.0")
    implementation("androidx.activity:activity-ktx:1.11.0")
    implementation("androidx.lifecycle:lifecycle-process:2.9.3")
    implementation("androidx.security:security-crypto:1.1.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core-ktx:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

mavenPublishing {
    coordinates("com.usesonny", "sonny-sdk", project.version.toString())
    publishToMavenCentral()
    signAllPublications()
    pom {
        name.set("Sonny SDK")
        description.set("Visitor support chat for Android apps")
        url.set("https://www.usesonny.com/docs/widget-mobile")
        licenses { license { name.set("MIT"); url.set("https://opensource.org/licenses/MIT") } }
        developers { developer { id.set("sonny"); name.set("Sonny"); url.set("https://www.usesonny.com") } }
        scm { url.set("https://github.com/usesonny/sonny-android-sdk"); connection.set("scm:git:https://github.com/usesonny/sonny-android-sdk.git") }
    }
}
