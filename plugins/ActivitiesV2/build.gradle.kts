version = "1.1.2"
description = "Lets you play activities like Wordle"

aliucord {
    // Changelog of your plugin
    changelog.set(
        """
        # 1.1.2
        * Fixed activites not working at all while in a voice chat
        * Fixed authentication (Chess in the Park, Putt Party, etc) which resulted in refusal to load
        * Support for activities in more channel types
        * Code refactor and cleanup

        # 1.1.1
        * Show participant avatars in activity header

        # 1.1.0
        * Added an activity picker to the chat attachments bar, showing a grid of the server's apps and the activity shelf
        * Activities in voice chat are now supported
        * Launch failures now show the exact reason (age restriction, missing permission, etc)
        * Fixed activities failing to authenticate after launch
        * Silenced some gateway logs (spams...)
        * Code refactor and cleanup

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
