plugins {
    id("java-gradle-plugin")
}

// D11: ID descobrível (`plugins { id("io.suko.lang") }`) para uso via
// includeBuild/composite e para o TestKit (withPluginClasspath() só produz
// plugin-under-test-metadata.properties com este plugin aplicado). Este
// módulo NÃO é publicado no Gradle Plugin Portal — o ID serve apenas
// composite builds e TestKit; publicação real depende da questão deixada
// em aberto por D1 do subprojeto 7.
gradlePlugin {
    plugins {
        create("suko") {
            id = "io.suko.lang"
            implementationClass = "io.suko.lang.gradle.SukoGradlePlugin"
        }
    }
}

dependencies {
    implementation(project(":suko-core"))

    // compileOnly(gradleApi()) deixou de ser necessário: java-gradle-plugin
    // já fornece a Gradle API ao classpath de compilação.

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation(gradleApi())
    testImplementation(gradleTestKit())
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
