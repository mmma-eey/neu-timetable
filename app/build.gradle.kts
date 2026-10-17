plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "cn.kejian.timetable"
    compileSdk = 36
    defaultConfig {
        applicationId = "cn.kejian.timetable"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "0.1.7"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.all { it.systemProperty("courseFixture", providers.gradleProperty("courseFixture").orElse(providers.environmentVariable("COURSE_FIXTURE")).orElse(rootProject.file(".local/private/neu-timetable.xlsx").absolutePath).get()) }
    }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.08.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.3")
    implementation("androidx.room:room-runtime:2.8.0")
    annotationProcessor("androidx.room:room-compiler:2.8.0")
    implementation("androidx.webkit:webkit:1.14.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("net.sf.kxml:kxml2:2.3.0")
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

// Windows test workers can fail to load classes from non-ASCII project paths.
// Keep sources in the user's workspace; stage only generated runtime entries in a local ASCII temp directory.
tasks.withType<Test>().configureEach {
    if (System.getProperty("os.name").startsWith("Windows") && rootProject.projectDir.path.any { it.code > 127 }) {
        doFirst {
            val staging = File(System.getProperty("java.io.tmpdir"), "kejian-jvm-test-${rootProject.projectDir.path.hashCode()}")
            val original = classpath.files.toList()
            classpath = files(original.map { entry ->
                if (entry.exists() && entry.path.any { it.code > 127 }) {
                    val target = File(staging, entry.path.hashCode().toString())
                    if (entry.isDirectory) { project.copy { from(entry); into(target) }; target }
                    else { target.mkdirs(); val copy = File(target, entry.name); entry.copyTo(copy, overwrite = true); copy }
                } else entry
            })
        }
    }
}
