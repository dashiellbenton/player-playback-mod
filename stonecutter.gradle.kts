stonecutter active "26.3"

// See https://stonecutter.kikugie.dev/wiki/config/params
stonecutter parameters {
    swaps["mod_version"] = "\"${properties["mod.version"]}\";"
    swaps["minecraft"] = "\"${current.version}\";"
}

// ---------------------------------------------------------------------------------------------
// Universal jar: one file for every supported Minecraft version. It contains each version's jar
// as a nested (jar-in-jar) mod; Fabric Loader only loads the nested jar whose "minecraft"
// dependency matches the running game.
// ---------------------------------------------------------------------------------------------
val universalVersions = listOf("1.21.11", "26.1.2", "26.2", "26.3")
val modVersion: String = Regex("""mod\.version\s*=\s*"([^"]+)"""")
    .find(file("stonecutter.properties.toml").readText())!!.groupValues[1]

val universalManifest = tasks.register("universalManifest") {
    val out = layout.buildDirectory.file("universal/fabric.mod.json").get().asFile
    val template = file("universal/fabric.mod.json")
    val version = modVersion
    val jars = universalVersions.joinToString(", ") {
        "{\"file\": \"META-INF/jars/playerplayback-$version+$it.jar\"}"
    }
    inputs.file(template)
    inputs.property("version", version)
    inputs.property("jars", jars)
    outputs.file(out)
    doLast {
        val text = template.readText().replace("\${version}", version).replace("\${jars}", jars)
        out.parentFile.mkdirs()
        out.writeText(text)
    }
}

tasks.register<Jar>("universalJar") {
    group = "build"
    description = "Builds one jar that works on every supported Minecraft version"
    universalVersions.forEach { dependsOn(":$it:buildAndCollect") }
    dependsOn(universalManifest)
    archiveFileName.set("playerplayback-$modVersion-universal.jar")
    destinationDirectory.set(layout.buildDirectory.dir("libs/$modVersion"))
    from(layout.buildDirectory.file("universal/fabric.mod.json"))
    from("src/main/resources/assets/playerplayback/icon.png") { into("assets/playerplayback") }
    from("LICENSE")
    universalVersions.forEach { v ->
        from(layout.buildDirectory.file("libs/$modVersion/playerplayback-$modVersion+$v.jar")) { into("META-INF/jars") }
    }
}
