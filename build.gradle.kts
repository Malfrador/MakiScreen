import net.minecrell.pluginyml.bukkit.BukkitPluginDescription

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.erethon.de/snapshots/")
    maven("https://jitpack.io")
    mavenCentral()
}
plugins {
    `java-library`
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
    id("xyz.jpenilla.run-paper") version "3.0.2"
    id("com.gradleup.shadow") version "9.3.1"
    id("net.minecrell.plugin-yml.bukkit") version "0.5.1"
}

group = "cat.maki.makiscreen"
version = "2.2.3"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

dependencies {
    paperweight.paperDevBundle("26.1.2.build.+")
    implementation("de.erethon:bedrock:1.5.18") { isTransitive = false }
    // FFmpeg
    implementation("org.bytedeco:javacv:1.5.10")
    implementation("org.bytedeco:ffmpeg:6.1.1-1.5.10:windows-x86_64")
    implementation("org.bytedeco:ffmpeg:6.1.1-1.5.10:linux-x86_64")
    implementation("org.bytedeco:javacpp:1.5.10")
    //  yt-dlp output parsing
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
    }

    runServer {
        minecraftVersion("26.1.2")
    }

    reobfJar {
        outputJar.set(layout.buildDirectory.file("libs/MCCinema-${project.version}.jar"))
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
        main = "de.erethon.mccinema.MCCinema"
        apiVersion = "26.1"
        authors = listOf("Maki", "Malfrador")
        name = "MCCinema"
        version = project.version.toString()

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