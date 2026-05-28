version = "1.0.0"
description = "automake it"

android {
    namespace = "dev.autoaliu.generated.auraplugin"
}

aliucord {
    changelog.set(
        """
        # 1.0.0
        * Initial plugin release.
        """.trimIndent(),
    )

    deploy.set(false)
}
