plugins {
    id("java")
}

group = "de.sirywell"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.10.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

tasks.test {
    jvmArgs = listOf("--add-modules=jdk.incubator.vector")
    useJUnitPlatform()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(22)
    }
}

tasks.withType(JavaExec::class) {
    jvmArgs("--add-modules=jdk.incubator.vector")
}