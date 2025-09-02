plugins {
    kotlin("kapt") version "1.9.21"
    kotlin("jvm") version embeddedKotlinVersion
    application
    id("com.gradleup.shadow") version "8.3.6" apply false
    java
}

group = "org.mastodon"
version = "0.2"

repositories {
    mavenCentral()
    maven("https://maven.scijava.org/content/groups/public")
    maven("https://jitpack.io")
    mavenLocal()
}

dependencies {
    implementation(platform("org.scijava:pom-scijava:37.0.0"))

    implementation("net.imagej:imagej")

    //api("sc.iview:sciview")
    implementation("sc.iview:sciview")

    implementation("org.slf4j:slf4j-simple:2.0.16")

    implementation("org.elephant:elephant:0.7.0")

    implementation("com.fasterxml.jackson.core:jackson-databind:2.16.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.1")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml:2.16.1")

    testImplementation(platform("org.junit:junit-bom:5.9.1"))
    testImplementation("org.junit.jupiter:junit-jupiter")

    val scijavaCommonVersion = "2.97.1"                 //TODO: shouldn't be updated?? look how this is done in scenery/sciview
    kapt("org.scijava:scijava-common:$scijavaCommonVersion") {
        exclude("org.lwjgl")
    }

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
}

tasks.register("copyDependencies", Copy::class) {
    from(configurations.runtimeClasspath); into("deps")
}

tasks.test {
    useJUnitPlatform()
}

tasks {
    if(project.properties["buildFatJAR"] == true) {
        apply(plugin = "com.gradleup.shadow")
        jar { isZip64 = true }
    }
}

application {
    mainClass = "org.mastodon.mamut.plugins.StartMastodon"
    applicationDefaultJvmArgs = listOf("--add-opens=java.base/java.lang=ALL-UNNAMED")
}