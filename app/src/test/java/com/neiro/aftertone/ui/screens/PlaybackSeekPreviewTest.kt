package com.neiro.aftertone.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSeekPreviewTest {
    @Test
    fun selectedSeekPositionRemainsVisibleWhilePlayerPositionIsStale() {
        assertEquals(
            60_000L,
            playbackPositionMsForDisplay(
                currentPositionMs = 3_000L,
                pendingSeekPositionMs = 60_000L,
            ),
        )
    }

    @Test
    fun playerPositionReplacesPreviewWhenSeekIsAcknowledged() {
        assertEquals(
            60_500L,
            playbackPositionMsForDisplay(
                currentPositionMs = 60_500L,
                pendingSeekPositionMs = 60_000L,
            ),
        )
    }

    @Test
    fun selectedPositionRemainsVisibleUntilPlayerGetsWithinAcknowledgementTolerance() {
        assertEquals(
            60_000L,
            playbackPositionMsForDisplay(
                currentPositionMs = 58_499L,
                pendingSeekPositionMs = 60_000L,
            ),
        )
    }

    @Test
    fun currentPlayerPositionIsUsedWhenNoSeekIsPending() {
        assertEquals(
            3_000L,
            playbackPositionMsForDisplay(
                currentPositionMs = 3_000L,
                pendingSeekPositionMs = null,
            ),
        )
    }
}
