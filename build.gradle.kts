import net.minecrell.pluginyml.bukkit.BukkitPluginDescription

repositories {
    mavenLocal()
    maven("https://jitpack.io")
    maven("https://erethon.de/repo")
    mavenCentral()
}
plugins {
    `java-library`
    id("io.papermc.paperweight.userdev") version "1.3.6-SNAPSHOT"
    id("xyz.jpenilla.run-paper") version "1.0.6" // Adds runServer and runMojangMappedServer tasks for testing
    id ("com.github.johnrengelman.shadow") version "7.1.2"
    id("net.minecrell.plugin-yml.bukkit") version "0.5.1"
}

group = "cat.maki.makiscreen"
version = "1.0.0-SNAPSHOT"
description = "Quest plugin for erethon"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
}

dependencies {
    paperDevBundle("1.18.2-R0.1-SNAPSHOT")
    implementation("de.erethon:bedrock:1.2.0") { isTransitive = false }
}

tasks {
    assemble {
        dependsOn(reobfJar)
        dependsOn(shadowJar)
    }

    compileJava {
        options.encoding = Charsets.UTF_8.name()
        options.release.set(17)
    }
    javadoc {
        options.encoding = Charsets.UTF_8.name()
    }
    processResources {
        filteringCharset = Charsets.UTF_8.name()
    }

    shadowJar {
        dependencies {
            include(dependency("de.erethon:bedrock:1.2.1"))
        }
        relocate("de.erethon.bedrock", "cat.maki.makiscreen.bedrock")
    }
    bukkit {
        load = BukkitPluginDescription.PluginLoadOrder.STARTUP
        main = "cat.maki.MakiScreen.MakiScreen"
        apiVersion = "1.18"
        authors = listOf("Maki")
        commands {
            register("maki") {
                description = "Does things!"
                aliases = listOf("mscreen")
            }
        }
    }
}