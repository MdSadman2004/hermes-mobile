package com.hermes.mobile.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Softer than Material defaults on purpose: warm neutrals plus square corners
 * read as a spreadsheet. Radii step in even increments so a card inside a
 * sheet inside a dialog still nests cleanly.
 */
val HermesShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)
