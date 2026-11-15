// Copyright (C) 2026 Chris Anderson
// SPDX-License-Identifier: Apache-2.0

package org.aohp.driver.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Dark = darkColorScheme(
    primary = Color(0xFF7FD1AE),
    secondary = Color(0xFF9FB8C9),
    background = Color(0xFF101418),
    surface = Color(0xFF161B21),
)
private val Light = lightColorScheme(
    primary = Color(0xFF136B4E),
    secondary = Color(0xFF3F5A6B),
)

@Composable
fun AohpDriverTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
