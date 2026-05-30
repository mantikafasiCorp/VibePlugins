version = "1.0.0"
description = "compressed videos and images before sending"

android {
    namespace = "dev.autoaliu.generated.sendcompressed"
}

dependencies {
    implementation("com.otaliastudios:transcoder:0.11.2") {
        exclude(group = "org.jetbrains.kotlin")
    }
}
