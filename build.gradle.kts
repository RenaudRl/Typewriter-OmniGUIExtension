plugins {
    kotlin("jvm") version "2.3.20"
    id("com.typewritermc.module-plugin") version "2.1.0"
    `maven-publish`
}

group = "btcrenaud"
version = "0.13"

repositories {
    mavenCentral()
    maven("https://maven.typewritermc.com/beta/")
    maven("https://jitpack.io/")
}

dependencies {
    // Provided at runtime by the PacketEvents plugin, which Typewriter itself requires.
    // Used to read the held-item-change packet the client sends when the wheel is scrolled.
    compileOnly("com.github.retrooper:packetevents-spigot:2.11.0")
    testImplementation(kotlin("test"))
    testImplementation("com.google.code.gson:gson:2.13.1")
}

typewriter {
    namespace = "renaud"

    extension {
        name = "GuiAndDialogs"
        shortDescription = "Advanced GUI system with layout engines and MiniMessage support."
        description = """Typewriter extension providing a complete menu system with layout engines (Simple, Scrollable, Frame, Paginated, Composite, Book, Merchant), 23 inventory types, persistent item storage with per-player or group-based scoping, configurable click actions, and full MiniMessage formatting. Supports Paper and Folia server platforms. Foundation for Typewriter extensions requiring menus."""
        engineVersion = "0.9.0-beta-175"
        channel = com.typewritermc.moduleplugin.ReleaseChannel.BETA
        
        paper()
    }
}

    

kotlin {
    jvmToolchain(21)
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            groupId = project.group.toString()
            artifactId = "Typewriter-OmniGUIExtension"
            // QuestCodex resolves released OmniGUI artifacts with the tag-style
            // version (v0.10), while the distributable jar keeps the semantic
            // filename version (0.10).
            version = "v${project.version}"
        }
    }
}

