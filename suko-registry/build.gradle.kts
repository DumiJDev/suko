plugins {
    id("java-library")
}

dependencies {
    // Gson em vez de Jackson: um único jar, sem transitivas — relevante
    // para o fat-jar da CLI do subprojeto 8. NUNCA em suko-core (ver
    // Global Constraints). Até à Tarefa 2 do subprojeto 8 este módulo
    // também dependia de suko-core (via RegistryGenerator, entretanto
    // movido para suko-registry-generator), o que anulava o benefício
    // desta escolha; a partir daqui é verdade: suko-registry só traz
    // Gson para o classpath de runtime.
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
