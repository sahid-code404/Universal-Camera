package com.omnicam.feature.camera

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput as composePointerInput

/** Keeps gesture plumbing local to the camera UI package without coupling camera logic to Compose. */
internal fun Modifier.pointerInput(
    vararg keys: Any?,
    block: suspend PointerInputScope.() -> Unit,
): Modifier = composePointerInput(*keys, block = block)
