version = "1.0.2"
description = "put quick message action buttons next to messages"

android {
    namespace = "dev.mantikafasi.aliucordplugins"
}

aliucord {
    changelog.set(
        """
        # 1.0.2
        * Changed the setting to hide only reply, edit, and delete message actions.
        * Quick reaction buttons now keep working while message actions are hidden.

        # 1.0.1
        * Fixed the enable setting so message actions can be toggled without restarting.
        * Avoid stale QuickMessageActions click listeners after plugin reloads.
        """.trimIndent(),
    )
}
