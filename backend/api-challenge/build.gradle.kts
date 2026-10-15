plugins {
	java
	jacoco
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
	id("com.diffplug.spotless") version "8.10.4"
}

group = "com.olx"
version = "0.0.1-SNAPSHOT"
description = "Ads API with async moderation"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

repositories {
	mavenCentral()
}

val resilience4jVersion = "2.4.0"
val springdocVersion = "3.1.1"
val wiremockVersion = "3.13.2"
val archunitVersion = "1.5.1"

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.flywaydb:flyway-database-postgresql")
	implementation("io.github.resilience4j:resilience4j-spring-boot4:$resilience4jVersion")
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:$springdocVersion")
	runtimeOnly("org.postgresql:postgresql")
	compileOnly("org.projectlombok:lombok")
	annotationProcessor("org.projectlombok:lombok")

	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-jdbc-test")
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testImplementation("org.wiremock:wiremock-standalone:$wiremockVersion")
	testImplementation("org.awaitility:awaitility")
	testImplementation("com.tngtech.archunit:archunit-junit5:$archunitVersion")
	testCompileOnly("org.projectlombok:lombok")
	testAnnotationProcessor("org.projectlombok:lombok")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
	useJUnitPlatform()
	finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
	dependsOn(tasks.test)
	reports {
		html.required = true
		xml.required = true
	}
}

spotless {
	java {
		googleJavaFormat()
		removeUnusedImports()
	}
}
