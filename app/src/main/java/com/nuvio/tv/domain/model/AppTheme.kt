package com.nuvio.tv.domain.model

enum class AppTheme(val displayName: String) {
    // HoneyFlix (HoneyStream) themes, matching HoneyLive.
    HONEY("Honey"),
    HALLOWEEN("Halloween"),
    AUTUMN("Autumn"),
    HOLIDAYS("Holidays"),
    FROST("Frost"),
    SPRING("Spring"),
    SUMMER("Summer"),
    CUSTOM("Custom"),
    GOLD("Gold"),
    JADE("Jade"),
    ROSE_GOLD("Rose Gold"),
    ARCTIC_BLUE("Arctic Blue"),
    GRAPHITE("Graphite"),
    CRIMSON("Crimson"),
    OCEAN("Ocean"),
    VIOLET("Violet"),
    EMERALD("Emerald"),
    AMBER("Amber"),
    ROSE("Rose"),
    WHITE("White")
}

/** The theme used when nothing is chosen. HoneyFlix: Honey (Nuvio: White). */
val DefaultAppTheme: AppTheme = AppTheme.HONEY
