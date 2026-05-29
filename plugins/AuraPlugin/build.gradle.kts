version = "1.0.0"
description = "automake it"

android {
    namespace = "dev.mantikafasi.aliucordplugins"
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
