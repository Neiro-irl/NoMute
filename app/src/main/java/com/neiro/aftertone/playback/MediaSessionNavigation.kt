package com.neiro.aftertone.playback

internal const val ACTION_OPEN_NOW_PLAYING = "com.neiro.nomute.action.OPEN_NOW_PLAYING"

internal fun nextNowPlayingLaunchRequestId(currentRequestId: Int, action: String?): Int =
    if (action == ACTION_OPEN_NOW_PLAYING) currentRequestId + 1 else currentRequestId
