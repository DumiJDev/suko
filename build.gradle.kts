plugins {
    id("base")
}

// The `base` plugin's `clean` task only deletes `buildDir`. A stale
// root-level `jte-classes/` directory can also be left over from
// pre-migration checkouts (JTE writes generated classes there at test
// time); make sure `gradle clean` removes that too.
tasks.named<Delete>("clean") {
    delete(layout.projectDirectory.dir("jte-classes"))
}

subprojects {
    group = "io.suko"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
        gradlePluginPortal()
    }

    // Java 21 é o piso declarado (D12). `options.release`, não toolchain:
    // não obriga a ter um JDK 21 instalado nem a acrescentar o
    // foojay-resolver ao settings.gradle.kts, e garante o que interessa ao
    // fat jar do suko-cli — bytecode major 65 e recusa, em tempo de
    // compilação, de qualquer API posterior ao 21.
    //
    // UNIFORMEMENTE em todos os módulos, de propósito: o conflito de
    // resolução de variante documentado em suko-maven-plugin/build.gradle.kts
    // veio de UM módulo declarar uma versão enquanto os outros não
    // declaravam nenhuma. Uniformidade é o que o evita.
    tasks.withType<JavaCompile>().configureEach {
        options.release.set(21)
    }
}
