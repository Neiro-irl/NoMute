package com.neiro.aftertone.ui.screens

import kotlin.math.abs

internal const val SEEK_POSITION_ACK_TOLERANCE_MS = 1_500L
internal const val SEEK_PREVIEW_TIMEOUT_MS = 2_500L

internal fun isSeekPositionAcknowledged(
    currentPositionMs: Long,
    requestedPositionMs: Long,
): Boolean = abs(currentPositionMs - requestedPositionMs) <= SEEK_POSITION_ACK_TOLERANCE_MS

internal fun playbackPositionMsForDisplay(
    currentPositionMs: Long,
    pendingSeekPositionMs: Long?,
): Long = pendingSeekPositionMs
    ?.takeUnless { isSeekPositionAcknowledged(currentPositionMs, it) }
    ?: currentPositionMs
