package com.neiro.aftertone.ui.screens

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackQueueDialogLifetimeTest {
    @Test
    fun outgoingTransitionKeepsDialogComposedUntilItFinishes() {
        assertTrue(
            playbackQueueDialogShouldBeComposed(
                currentState = true,
                targetState = false,
            ),
        )
        assertFalse(
            playbackQueueDialogShouldBeComposed(
                currentState = false,
                targetState = false,
            ),
        )
    }

    @Test
    fun queueDialogHostIsSharedAcrossEmptyAndPlayingBranches() {
        val source = findNowPlayingScreenSource()
        val screenStart = source.indexOf("internal fun NowPlayingScreen(")
        val dialogDeclaration = source.indexOf("\n@Composable\nprivate fun PlaybackQueueDialog(", screenStart)
        assertTrue("NowPlayingScreen source block must be present", screenStart >= 0)
        assertTrue("PlaybackQueueDialog declaration must follow NowPlayingScreen", dialogDeclaration > screenStart)

        val screenBody = source.substring(screenStart, dialogDeclaration)
        val hostCallSites = Regex("(?m)^\\s*PlaybackQueueDialog\\(")
            .findAll(screenBody)
            .map { it.range.first }
            .toList()
        val trackBranch = screenBody.indexOf("if (track == null)")

        assertEquals("one shared dialog host must be rendered by NowPlayingScreen", 1, hostCallSites.size)
        assertTrue("the dialog host must precede the empty/playing track branch", trackBranch >= 0 && hostCallSites.single() < trackBranch)
    }

    @Test
    fun openingTransitionIsComposedAsSoonAsItsTargetBecomesVisible() {
        assertTrue(
            playbackQueueDialogShouldBeComposed(
                currentState = false,
                targetState = true,
            ),
        )
    }

    private fun findNowPlayingScreenSource(): String {
        val relativePaths = listOf(
            "src/main/java/com/neiro/aftertone/ui/screens/NowPlayingScreen.kt",
            "app/src/main/java/com/neiro/aftertone/ui/screens/NowPlayingScreen.kt",
        )
        val workingDirectory = File(System.getProperty("user.dir") ?: ".").absoluteFile
        val sourceFile = generateSequence(workingDirectory) { it.parentFile }
            .flatMap { directory -> relativePaths.asSequence().map(directory::resolve) }
            .firstOrNull { it.isFile }
        return sourceFile?.readText() ?: error("Could not locate NowPlayingScreen.kt from ${System.getProperty("user.dir")}")
    }
}
