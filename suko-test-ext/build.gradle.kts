plugins {
    id("java")
}

// Extensão mínima que prova a API do 13a nos três pontos de entrada.
// Não é publicada nem usada fora de testes.
dependencies {
    compileOnly(project(":suko-api"))
}
