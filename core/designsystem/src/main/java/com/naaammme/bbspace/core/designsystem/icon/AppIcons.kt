package com.naaammme.bbspace.core.designsystem.icon

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import com.naaammme.bbspace.core.designsystem.R

object AppIcons {
    val Pause: Painter
        @Composable get() = painterResource(R.drawable.ic_pause)

    val PlayArrow: Painter
        @Composable get() = rememberVectorPainter(Icons.Default.PlayArrow)
}
