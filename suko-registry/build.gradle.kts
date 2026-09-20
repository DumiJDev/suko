plugins {
    id("java-library")
}

dependencies {
    // `api`, não `implementation`: o gerador expõe tipos de suko-core
    // (ComponentDecl, SukoFile, ProjectIndex) na sua própria assinatura.
    api(project(":suko-core"))

    // Gson em vez de Jackson: um único jar, sem transitivas — relevante
    // para o fat-jar da CLI do subprojeto 8. NUNCA em suko-core (ver
    // Global Constraints).
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
