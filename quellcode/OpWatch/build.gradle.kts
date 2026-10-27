plugins {
    java
}

group = "dev.opwatch"
version = "1.2.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Paper 26.3 – genaue Build-Nummer ggf. auf https://docs.papermc.io nachschauen
    compileOnly("io.papermc.paper:paper-api:26.3.build.+")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
}

// Gemeinsames Design: design/fonts.css + design/theme.css aus dem Repo werden an <!--THEME--> in die Webseiten eingesetzt
val fonts = file("../../design/fonts.css")
val theme = file("../../design/theme.css")

tasks.processResources {
    filteringCharset = "UTF-8"
    inputs.files(fonts, theme)
    filesMatching("web/*.html") {
        filter { line -> if (line.trim() == "<!--THEME-->") "<style>\n" + fonts.readText(Charsets.UTF_8) + theme.readText(Charsets.UTF_8) + "\n</style>" else line }
    }
}
