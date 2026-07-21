plugins {
    kotlin("jvm") version "2.3.20"
    id("com.typewritermc.module-plugin") version "2.1.0"
}

group = "btcrenaud"
version = "0.3.4"

repositories {
    mavenCentral()
    maven("https://maven.typewritermc.com/beta/")
    maven("https://jitpack.io/")
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

