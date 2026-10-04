plugins {
    id("java-library")
}

// Alvo JTE como extensão (13a): depende só do contrato, nunca do compilador.
dependencies {
    api(project(":suko-api"))
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
