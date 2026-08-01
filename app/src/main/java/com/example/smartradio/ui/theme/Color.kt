package com.example.smartradio.ui.theme

import androidx.compose.ui.graphics.Color

// Core brand colors — the deep teal/mint pairing from the app icon.
val TealPrimary = Color(0xFF12938E)
val MintAccent = Color(0xFF4DE8D4)
val NavyDark = Color(0xFF1B1F33)

// "Muted, waiting for music" state — deliberately distinct from teal/mint
// (which mean "playing") so the two states can never be confused for each other.
val MutedAmber = Color(0xFFB8720A)

// Light theme surfaces
val BackgroundLight = Color(0xFFF7F8FA)
val SurfaceLight = Color(0xFFFFFFFF)
val TextPrimary = Color(0xFF14161C)
val TextSecondary = Color(0xFF6B7280)

// Dark pill badges (Digital / FM simulcast tags)
val PillBackground = Color(0xFF20242F)
val PillText = Color(0xFFFFFFFF)

// Cycled palette for per-station avatar squares — deterministic by list index,
// not tied to any specific station identity.
val AvatarPalette = listOf(
    Color(0xFF12938E), // teal
    Color(0xFF22C3B6), // mint
    Color(0xFF1B1F33), // navy
    Color(0xFF2E86AB), // sky blue
    Color(0xFF3D8361), // muted green
)
