plugins {
    `java-library`
}

group = "io.suko"
version = "0.1.0-SNAPSHOT"

description = "Maven plugin for Suko language compilation"

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation(platform("org.apache.maven:maven-bom:3.9.6"))
    implementation("org.apache.maven:maven-plugin-api")
    implementation("org.apache.maven.plugin-tools:maven-plugin-annotations:3.11.0")
    implementation("org.codehaus.plexus:plexus-utils:3.6.0")
    
    // Suko core
    implementation(project(":suko-core"))
    
    // ANTLR and JTE (same as core)
    implementation("org.antlr:antlr4:4.13.1")
    implementation("gg.jte:jte:3.1.12")
    
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Java version
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

// Jar configuration for Maven plugin
jar {
    manifest {
        attributes(
            "Implementation-Title": "Suko Maven Plugin",
            "Implementation-Version": version,
            "Generated-By": "Gradle"
        )
    }
}

// Configure the Maven plugin descriptor generation
tasks.named("processResources") {
    dependsOn(generateMavenPluginDescriptor)
}

val generateMavenPluginDescriptor = tasks.register<org.apache.maven.plugin.tools.ant.GenerateMojoDescriptor>("generateMavenPluginDescriptor") {
    group = "build"
    description = "Generates Maven plugin descriptor"
    
    // Configure the mojo descriptor generation
    classpath = sourceSets.main.get().runtimeClasspath
    outputDir = layout.buildDirectory.dir("generated-resources/plugin")
    implementationVersion = version
    pluginDescriptors = listOf(layout.buildDirectory.dir("META-INF/maven").get().asFile)
    
    // Only process classes in our plugin package
    excludes = ["**/AbstractMojo.class"]
    includes = ["io/suko/lang/maven/**/*.class"]
}

tasks.named("processResources") {
    dependsOn(generateMavenPluginDescriptor)
    from(generateMavenPluginDescriptor.map { it.get().outputDir })
}

// Configure the Javadoc
tasks.withType<Javadoc> {
    options {
        encoding = "UTF-8"
        charSet = "UTF-8"
        author = true
        version = true
    }
}

// Test configuration
tasks.test {
    useJUnitPlatform()
}

// Publishing configuration
publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])

            pom {
                name.set("Suko Maven Plugin")
                description.set("Maven plugin for compiling Suko (.sk) files to JTE (.jte) templates")
                url.set("https://github.com/DumiJDev/suko")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("dumijdev")
                        name.set("Dumi JDev")
                        email.set("dumi@example.com")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/DumiJDev/suko.git")
                    developerConnection.set("scm:git:ssh://github.com/DumiJDev/suko.git")
                    url.set("https://github.com/DumiJDev/suko")
                }
            }
        }
    }
    
    repositories {
        maven {
            // This would typically point to your repository
            // For now, we'll leave it empty - configure as needed
            url = uri("https://repo.example.com/releases") // Change as needed
            credentials {
                username = findProperty("repoUsername") ?: ""
                password = findProperty("repoPassword") ?: ""
            }
        }
    }
}