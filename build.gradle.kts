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
}
