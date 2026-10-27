plugins {
    java
    id("org.springframework.boot") version "4.1.2"
    id("com.google.cloud.tools.jib") version "3.5.4"
}

group = "com.vocabler"
version = "0.0.1-SNAPSHOT"
description = "Family vocabulary learning"

springBoot {
    mainClass = "com.vocabler.VocablerApplication"
}

repositories {
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.2"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.apache.commons:commons-csv:1.14.1")

    runtimeOnly("com.h2database:h2")
    runtimeOnly("org.postgresql:postgresql")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.security:spring-security-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// The deliverable is the executable Spring Boot JAR.
tasks.named<Jar>("jar") {
    enabled = false
}

tasks.wrapper {
    gradleVersion = "9.1.0"
    distributionType = Wrapper.DistributionType.BIN
    distributionSha256Sum = "a17ddd85a26b6a7f5ddb71ff8b05fc5104c0202c6e64782429790c933686c806"
}

// Keep the default build self-contained; CI also runs this suite against PostgreSQL.
tasks.named<Test>("test") {
    environment("TEST_DB_URL", "jdbc:h2:mem:vocabler-test;DB_CLOSE_DELAY=-1")
    environment("TEST_DB_USER", "sa")
    environment("TEST_DB_PASSWORD", "")
    exclude("**/PostgresMigrationTest.class")
}

tasks.register<Test>("postgresTest") {
    description = "Runs application and migration tests against a dedicated PostgreSQL test database."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/ApplicationIntegrationTest.class", "**/PostgresMigrationTest.class")
    environment("TEST_DB_URL", providers.environmentVariable("TEST_DB_URL").getOrElse("jdbc:postgresql://localhost:5432/vocabler_test"))
    environment("TEST_DB_USER", providers.environmentVariable("TEST_DB_USER").getOrElse("vocabler"))
    environment("TEST_DB_PASSWORD", providers.environmentVariable("TEST_DB_PASSWORD").getOrElse(""))
    shouldRunAfter(tasks.test)
    // Database state is external; this task must actually execute on every invocation.
    outputs.upToDateWhen { false }
}

jib {
    from {
        image = "eclipse-temurin:21-jre-jammy"
    }
    to {
        image = "vocabler:local"
    }
    container {
        mainClass = "com.vocabler.VocablerApplication"
        user = "10001:10001"
        workingDirectory = "/app"
        ports = listOf("8080")
        jvmFlags = listOf("-XX:MaxRAMPercentage=75.0")
        labels = buildMap {
            put("org.opencontainers.image.title", "Vocabler")
            providers.environmentVariable("GITHUB_REPOSITORY").orNull?.let {
                val server = providers.environmentVariable("GITHUB_SERVER_URL").getOrElse("https://github.com")
                put("org.opencontainers.image.source", "$server/$it")
            }
            providers.environmentVariable("GITHUB_SHA").orNull?.let {
                put("org.opencontainers.image.revision", it)
            }
        }
    }
}
