plugins {
	// Downloads the Java toolchain declared in build.gradle.kts if it isn't installed locally.
	id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "ads-api"
