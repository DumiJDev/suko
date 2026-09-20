plugins {
    id("java-library")
}

dependencies {
    // `api` nos dois: o gerador expõe tipos de suko-core (ComponentDecl,
    // SukoFile, ProjectIndex) E tipos do modelo (GeneratedRegistry,
    // ComponentManifest) na sua própria assinatura pública.
    api(project(":suko-registry"))
    api(project(":suko-core"))

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
