import org.gradle.api.plugins.quality.Pmd

plugins {
    id("java")
    id("pmd")
    alias(libs.plugins.kotlin)
    alias(libs.plugins.intellijPlatform)
}

group = "org.eljhoset"
version = "1.0.0-SNAPSHOT"

// Set the JVM language level used to build the project.
kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

pmd {
    toolVersion = "7.18.0"
    isConsoleOutput = true
    ruleSetFiles = files(layout.projectDirectory.file("config/pmd/ruleset.xml"))
    ruleSets = emptyList()
}

// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    testImplementation("junit:junit:4.13.2")

    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Add plugin dependencies for compilation here:
        bundledPlugin("com.intellij.java")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
        }

        changeNotes = """
            Initial version
        """.trimIndent()
    }
}

tasks {
    withType<Pmd>().configureEach {
        reports {
            xml.required = true
            html.required = true
        }
    }

    test {
        useJUnit()
    }
    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }
}
