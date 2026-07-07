@file:Suppress("unused")

package com.github.secp192k1

// https://docs.discord.food/interactions/receiving-and-responding#interaction-type
internal enum class InteractionType(val value: Int) {
    PING(1),
    APPLICATION_COMMAND(2),
    MESSAGE_COMPONENT(3),
    APPLICATION_COMMAND_AUTOCOMPLETE(4),
    MODAL_SUBMIT(5),
    SOCIAL_LAYER_SKU_PURCHASE_ELIGIBILITY(6),
}

// https://docs.discord.food/interactions/application-commands#application-command-type
internal enum class ApplicationCommandType(val value: Int) {
    CHAT_INPUT(1),
    USER(2),
    MESSAGE(3),
    PRIMARY_ENTRY_POINT(4),
}

// https://docs.discord.food/resources/application#application-integration-type
internal enum class ApplicationIntegrationType(val value: Int) {
    GUILD_INSTALL(0),
    USER_INSTALL(1),
}

// https://docs.discord.food/resources/presence#activity-type
internal enum class ActivityType(val value: Int) {
    PLAYING(0),
    STREAMING(1),
    LISTENING(2),
    WATCHING(3),
    CUSTOM(4),
    COMPETING(5),
    HANG(6),
}

// https://docs.discord.food/resources/channel#channel-type
internal enum class ChannelType(val value: Int) {
    GUILD_TEXT(0),
    DM(1),
    GUILD_VOICE(2),
    GROUP_DM(3),
    PUBLIC_THREAD(11),
    PRIVATE_THREAD(12),
    GUILD_STAGE_VOICE(13),
}

// Embedded frame opcodes exchanged with the WebView
internal enum class RpcOpcode(val value: Int) {
    HANDSHAKE(0),
    FRAME(1);

    companion object {
        fun from(value: Int) = values().firstOrNull { it.value == value }
    }
}

// Embedded app RPC error codes used when rejecting commands
internal enum class RpcErrorCode(val value: Int) {
    AUTHENTICATE_FAILED(4009),
    AUTHORIZE_FAILED(4011),
}

// Embedded app RPC commands handled by [ActivityRpc]
internal enum class RpcCommand {
    AUTHORIZE,
    AUTHENTICATE,
    GET_CHANNEL,
    GET_CHANNEL_PERMISSIONS,
    ENCOURAGE_HW_ACCELERATION,
    SUBSCRIBE,
    UNSUBSCRIBE,
    SET_ORIENTATION_LOCK_STATE,
    SET_ACTIVITY,
    CAPTURE_LOG,
    SEND_ANALYTICS_EVENT,
    GET_PLATFORM_BEHAVIORS;

    companion object {
        fun from(name: String) = values().firstOrNull { it.name == name }
    }
}

// https://docs.discord.food/gateway/gateway-events#interaction-failure-reason
internal enum class InteractionFailureReason(val code: Int, val message: String) {
    UNKNOWN(1, "Unknown"),
    TIMEOUT(2, "The interaction timed out"),
    ACTIVITY_LAUNCH_UNKNOWN_APPLICATION(3, "Unknown application"),
    ACTIVITY_LAUNCH_UNKNOWN_CHANNEL(4, "Unknown channel"),
    ACTIVITY_LAUNCH_UNKNOWN_GUILD(5, "Unknown guild"),
    ACTIVITY_LAUNCH_INVALID_PLATFORM(6, "Invalid platform"),
    ACTIVITY_LAUNCH_NOT_IN_EXPERIMENT(7, "The guild/user is not eligible for a required experiment"),
    ACTIVITY_LAUNCH_INVALID_CHANNEL_TYPE(8, "Invalid channel type"),
    ACTIVITY_LAUNCH_INVALID_CHANNEL_NO_AFK(9, "Cannot launch activity in an AFK channel"),
    ACTIVITY_LAUNCH_INVALID_DEV_PREVIEW_GUILD_SIZE(10, "Guild is too large for the beta test of this feature"),
    ACTIVITY_LAUNCH_INVALID_USER_AGE_GATE(11, "Cannot use NSFW interaction"),
    ACTIVITY_LAUNCH_INVALID_USER_VERIFICATION_LEVEL(12, "User does not meet the guild's verification level"),
    ACTIVITY_LAUNCH_INVALID_USER_PERMISSIONS(13, "User has insufficient permissions for this interaction"),
    ACTIVITY_LAUNCH_INVALID_CONFIGURATION_NOT_EMBEDDED(14, "The application is not an embedded activity"),
    ACTIVITY_LAUNCH_INVALID_CONFIGURATION_PLATFORM_NOT_SUPPORTED(15, "The embedded activity does not support the current platform"),
    ACTIVITY_LAUNCH_INVALID_CONFIGURATION_PLATFORM_NOT_RELEASED(16, "The embedded activity is not released for the current platform"),
    ACTIVITY_LAUNCH_FAILED_TO_LAUNCH(17, "Failed to launch the activity"),
    ACTIVITY_LAUNCH_INVALID_USER_NO_ACCESS_TO_ACTIVITY(18, "The user does not have permissions to launch the activity"),
    ACTIVITY_LAUNCH_INVALID_LOCATION_TYPE(19, "Failed to launch the activity"),
    ACTIVITY_LAUNCH_INVALID_USER_REGION_FOR_APPLICATION(20, "The embedded activity is not supported in the current region");

    companion object {
        fun messageFor(code: Int) = values().firstOrNull { it.code == code }?.message
            ?: "Failed to launch ($code)"
    }
}
