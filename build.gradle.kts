import net.minecrell.pluginyml.bukkit.BukkitPluginDescription

repositories {
    mavenLocal()
    maven("https://jitpack.io")
    maven("https://repo.erethon.de/snapshots/")
    mavenCentral()
}
plugins {
    `java-library`
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.17"
    id("xyz.jpenilla.run-paper") version "2.3.1"
    id("com.gradleup.shadow") version "9.3.1"
    id("net.minecrell.plugin-yml.bukkit") version "0.5.1"
}

group = "cat.maki.makiscreen"
version = "2.0.0"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

dependencies {
    paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT")
    implementation("de.erethon:bedrock:1.5.18") { isTransitive = false }

    // JavaCV core library with FFmpeg
    implementation("org.bytedeco:javacv:1.5.10")
    implementation("org.bytedeco:ffmpeg:6.1.1-1.5.10:windows-x86_64")
    implementation("org.bytedeco:ffmpeg:6.1.1-1.5.10:linux-x86_64")
    implementation("org.bytedeco:javacpp:1.5.10")

    // JSON library for yt-dlp output parsing
    implementation("com.alibaba:fastjson:1.2.83")
}

paperweight {
    javaLauncher = javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks {
    assemble {
        dependsOn(shadowJar)
    }

    compileJava {
        options.encoding = "UTF-8"
        options.release.set(25)
        options.compilerArgs.addAll(listOf("--enable-preview"))
    }

    runServer {
        minecraftVersion("1.21.11")
    }

    reobfJar {
        outputJar.set(layout.buildDirectory.file("libs/MakiScreen-${project.version}.jar"))
    }

    shadowJar {
        archiveClassifier.set("all")

        dependencies {
            include(dependency("de.erethon:bedrock:1.5.18"))
            include(dependency("org.bytedeco:javacv:.*"))
            include(dependency("org.bytedeco:ffmpeg:.*"))
            include(dependency("org.bytedeco:javacpp:.*"))
            include(dependency("com.alibaba:fastjson:.*"))
        }

        relocate("de.erethon.bedrock", "de.erethon.mccinema.bedrock")
        mergeServiceFiles()
    }

    bukkit {
        load = BukkitPluginDescription.PluginLoadOrder.STARTUP
        main = "de.erethon.mcinema.MCCinema"
        apiVersion = "1.21"
        authors = listOf("Maki", "Malfrador")

        permissions {
            register("mccinema.create") {
                description = "Allows creating screens"
                default = BukkitPluginDescription.Permission.Default.OP
            }
            register("mccinema.delete") {
                description = "Allows deleting screens"
                default = BukkitPluginDescription.Permission.Default.OP
            }
            register("mccinema.play") {
                description = "Allows playing videos"
                default = BukkitPluginDescription.Permission.Default.OP
            }
            register("mccinema.control") {
                description = "Allows pause/resume/stop/seek"
                default = BukkitPluginDescription.Permission.Default.OP
            }
            register("mccinema.download") {
                description = "Allows downloading videos from YouTube"
                default = BukkitPluginDescription.Permission.Default.OP
            }
            register("mccinema.list") {
                description = "Allows listing screens and videos"
                default = BukkitPluginDescription.Permission.Default.TRUE
            }
            register("mccinema.info") {
                description = "Allows viewing screen info"
                default = BukkitPluginDescription.Permission.Default.TRUE
            }
            register("mccinema.help") {
                description = "Allows viewing help"
                default = BukkitPluginDescription.Permission.Default.TRUE
            }
        }
    }
}