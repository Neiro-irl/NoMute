package com.neiro.aftertone.ui.screens

internal fun libraryOrderEditingEnabled(query: String): Boolean = query.isBlank()

internal fun nextLibraryReorderIndex(
    currentIndex: Int,
    accumulatedOffsetPx: Float,
    rowStepPx: Float,
    itemCount: Int,
): Int? {
    if (itemCount < 2 || currentIndex !in 0 until itemCount || !rowStepPx.isFinite() || rowStepPx <= 0f) {
        return null
    }
    return when {
        accumulatedOffsetPx >= rowStepPx && currentIndex < itemCount - 1 -> currentIndex + 1
        accumulatedOffsetPx <= -rowStepPx && currentIndex > 0 -> currentIndex - 1
        else -> null
    }
}

internal fun clearOutOfBoundsLibraryReorderOffset(
    currentIndex: Int,
    accumulatedOffsetPx: Float,
    itemCount: Int,
): Float {
    if (itemCount < 2 || currentIndex !in 0 until itemCount || !accumulatedOffsetPx.isFinite()) return 0f
    return when {
        currentIndex == 0 && accumulatedOffsetPx < 0f -> 0f
        currentIndex == itemCount - 1 && accumulatedOffsetPx > 0f -> 0f
        else -> accumulatedOffsetPx
    }
}
