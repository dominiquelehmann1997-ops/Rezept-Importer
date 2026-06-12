package de.dml.rezeptimporter.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import de.dml.rezeptimporter.R

val SpaceMono = FontFamily(
    Font(R.font.space_mono_regular, FontWeight.Normal),
    Font(R.font.space_mono_bold, FontWeight.Bold),
)

val PlusJakartaSans = FontFamily(
    Font(R.font.plus_jakarta_sans_regular, FontWeight.Normal),
    Font(R.font.plus_jakarta_sans_semibold, FontWeight.SemiBold),
)

// Headings + Code: Space Mono (Letter-Spacing −0.02em). Body + Labels: Plus Jakarta Sans.
val ArcaneTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = SpaceMono, fontWeight = FontWeight.Bold,
        fontSize = 24.sp, letterSpacing = (-0.02).em,
    ),
    titleLarge = TextStyle(
        fontFamily = SpaceMono, fontWeight = FontWeight.Bold,
        fontSize = 20.sp, letterSpacing = (-0.02).em,
    ),
    titleMedium = TextStyle(
        fontFamily = SpaceMono, fontWeight = FontWeight.Bold,
        fontSize = 16.sp, letterSpacing = (-0.02).em,
    ),
    bodyLarge = TextStyle(fontFamily = PlusJakartaSans, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = PlusJakartaSans, fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = PlusJakartaSans, fontSize = 12.sp),
    labelLarge = TextStyle(
        fontFamily = PlusJakartaSans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = PlusJakartaSans, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
    ),
)
