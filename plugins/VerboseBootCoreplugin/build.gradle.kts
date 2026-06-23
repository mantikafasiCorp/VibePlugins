version = "1.0.1"
description = "VerboseBootCoreplugin plugin fix"

android {
    namespace = "dev.autoaliu.generated.verbosebootcoreplugin"
}

tasks.named("extractPluginClass") {
    enabled = false
}

val writePluginClass by tasks.registering {
    val pluginClassNameFile = layout.buildDirectory.file("intermediates/pluginClass.txt")

    outputs.file(pluginClassNameFile)

    doLast {
        val file = pluginClassNameFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText("dev.autoaliu.generated.verbosebootcoreplugin.VerboseBootCoreplugin")
    }
}

tasks.named("package") {
    dependsOn(writePluginClass)
}
