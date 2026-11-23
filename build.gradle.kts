plugins {
    java
    id("org.springframework.boot") version "4.1.3"
}

group = "kr.gongchae"
version = "0.1.0-SNAPSHOT"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(21) }
}

repositories { mavenCentral() }

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.3"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-thymeleaf")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Boot 4.1.3's BOM still manages Tomcat 11.0.27; 11.0.29 has the fixes of 2026-11-13 (tomcat.apache.org/security-11.html).
// With Gradle's native BOM support this is Boot's documented override (a resolution strategy, not a BOM property).
// shortcut: pinned by hand, remove once the Boot BOM manages Tomcat 11.0.29 or later.
configurations.all {
    resolutionStrategy.eachDependency { if (requested.group == "org.apache.tomcat.embed") useVersion("11.0.29") }
}

tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
tasks.withType<Test>().configureEach { useJUnitPlatform() }
tasks.bootJar { archiveFileName = "gongchae-bell.jar" }
