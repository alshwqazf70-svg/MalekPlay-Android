package com.malekplay.core.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource

/** Resolves a string by its resource *name*, used for data-driven labels. */
@Composable
@ReadOnlyComposable
fun stringResourceByName(name: String): String {
    val context = LocalContext.current
    if (name.isBlank()) return ""
    val id = context.resources.getIdentifier(name, "string", context.packageName)
    return if (id != 0) stringResource(id) else name
}
