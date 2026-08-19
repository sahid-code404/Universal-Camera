package com.omnicam.feature.camera

import kotlin.math.round

/** Package-local numeric helper used by the lightweight camera controls. */
internal fun Float.roundToInt(): Int = round(this).toInt()
