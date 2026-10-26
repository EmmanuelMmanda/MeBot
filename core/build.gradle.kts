plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    // CI uses 17. A local JDK 21 is also supported; bytecode stays at 17.
    jvmToolchain(providers.gradleProperty("mebot.buildJdk").orElse("17").get().toInt())
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
    testLogging {
        events("failed", "skipped")
    }
}

tasks.withType<JavaCompile>().configureEach { sourceCompatibility = "17"; targetCompatibility = "17" }

// Reproducible isolated host timing, never presented as phone latency.
tasks.register<JavaExec>("rankingBenchmark") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set(providers.gradleProperty("benchmarkMain").getOrElse("mebot.nguli.com.core.RankingBenchmark"))
    args(providers.gradleProperty("benchmarkOutput").getOrElse("../benchmarks/results/run.csv"), providers.gradleProperty("benchmarkFork").getOrElse("0"))
    maxHeapSize = "512m"
}
