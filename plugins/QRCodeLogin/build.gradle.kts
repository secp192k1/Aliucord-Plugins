version = "1.1.0"
description = "Attempts to fix login via QR Code"

aliucord {
    // Changelog of your plugin
    changelog.set(
        """
        # 1.1.0
        * Fixed "Cant find this computer" when switching between apps during 2FA code input
        * Added password login for accounts without 2FA
        * Recover the login if the screen reloads while the request is still valid
        * Code cleanup

        # 1.0.0
        * Initial plugin release!
        """.trimIndent(),
    )
    // Image or Gif that will be shown at the top of your changelog page
    // changelogMedia.set("https://cool.png")

    // Add additional authors to this plugin
    // author("Name", 0L, hyperlink = true)
    // author("Name", 0L, hyperlink = true)

    // Excludes this plugin from publishing and global plugin repositories.
    // Set this to false if the plugin is unfinished
    deploy.set(true)

    // Builds and deploys this plugin but excludes it from global plugin repositories.
    // Set this if the plugin has reached EOL but a last update should still occur.
    // deployHidden.set(true)
}
