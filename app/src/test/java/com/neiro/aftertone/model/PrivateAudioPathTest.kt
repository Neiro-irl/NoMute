package com.neiro.aftertone.model

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SecureDirectoryStream
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileAttributeView
import java.nio.file.attribute.FileTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateAudioPathTest {
    @Test
    fun rejectsDirectChildWhenPrivateRootIsRedirectedThroughSymlink() {
        val root = Files.createTempDirectory("aftertone-private-root-").toFile()
        try {
            val redirectedTarget = File(root, "redirected-target").apply { mkdir() }
            val privateRootLink = File(root, "app-private-audio-link")
            Files.createSymbolicLink(privateRootLink.toPath(), redirectedTarget.toPath())
            val redirectedAudio = File(privateRootLink, "track-01.mp3").apply { writeBytes(byteArrayOf(1, 2, 3)) }

            assertFalse(PrivateAudioPath.isDirectChild(privateRootLink, redirectedAudio))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun restoreOwnerMarkerValidationRejectsSymlinkMarkers() {
        val restoreId = "00000000-0000-0000-0000-000000000091"
        val root = Files.createTempDirectory("aftertone-owner-marker-").toFile()
        try {
            val audioDirectory = File(root, "audio").apply { mkdir() }
            val generation = File(audioDirectory, "restore-$restoreId").apply { mkdir() }
            val marker = File(generation, PrivateAudioPath.restoreOwnerMarkerName()).apply { writeText(restoreId) }
            assertTrue(PrivateAudioPath.isOwnedRestoreDirectory(audioDirectory, generation, restoreId))
            assertTrue(marker.delete())

            val outsideMarker = File(root, "outside-owner-marker").apply { writeText(restoreId) }
            Files.createSymbolicLink(marker.toPath(), outsideMarker.toPath())

            assertFalse(PrivateAudioPath.hasExpectedRestoreOwnerMarker(generation, restoreId))
            assertFalse(PrivateAudioPath.isOwnedRestoreDirectory(audioDirectory, generation, restoreId))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun restoreOwnerMarkerReadStaysBoundToOpenedDirectoryWhenPathIsReplaced() {
        val restoreId = "00000000-0000-0000-0000-000000000092"
        val root = Files.createTempDirectory("aftertone-owner-directory-race-").toFile()
        try {
            val audioDirectory = File(root, "audio").apply { mkdir() }
            val generation = File(audioDirectory, "restore-$restoreId").apply { mkdir() }
            val marker = File(generation, PrivateAudioPath.restoreOwnerMarkerName()).apply { writeText(restoreId) }
            val movedGeneration = File(audioDirectory, "original-restore-generation")
            val outsideGeneration = File(root, "outside-generation").apply { mkdir() }
            File(outsideGeneration, marker.name).writeText("outside-content")
            val swappingMarker = object : File(marker.path) {
                private var swapped = false

                override fun getCanonicalFile(): File {
                    val canonical = super.getCanonicalFile()
                    if (!swapped) {
                        swapped = true
                        Files.move(generation.toPath(), movedGeneration.toPath())
                        Files.createSymbolicLink(generation.toPath(), outsideGeneration.toPath())
                    }
                    return canonical
                }
            }

            assertTrue(
                "Marker validation must read from the opened generation directory, not a replacement at its old path.",
                PrivateAudioPath.hasExpectedDirectChildContentsNoFollow(
                    generation,
                    swappingMarker,
                    restoreId,
                ),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun regularFileDeletionStaysBoundToOpenedParentAfterPathBecomesSymlink() {
        val root = Files.createTempDirectory("aftertone-private-delete-race-").toFile()
        try {
            val directory = File(root, "private").apply { assertTrue(mkdir()) }
            val movedDirectory = File(root, "opened-private")
            val outside = File(root, "outside").apply { assertTrue(mkdir()) }
            val name = "track-01.mp3"
            File(directory, name).writeBytes(byteArrayOf(1, 2, 3))
            val outsideFile = File(outside, name).apply { writeText("preserve") }
            val openedDirectory = PrivateAudioPath.openDirectoryNoFollow(directory)

            openedDirectory.use { handle ->
                Files.move(directory.toPath(), movedDirectory.toPath())
                Files.createSymbolicLink(directory.toPath(), outside.toPath())

                assertTrue(PrivateAudioPath.deleteRegularFileNoFollow(handle, directory.toPath().fileSystem.getPath(name)))
            }

            assertFalse("Deletion must target the original directory held open by the handle.", File(movedDirectory, name).exists())
            assertEquals("preserve", outsideFile.readText(Charsets.UTF_8))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun regularFileDeletionDoesNotUnlinkReplacementAfterIdentityRead() {
        val root = Files.createTempDirectory("aftertone-private-child-replacement-").toFile()
        try {
            val directory = File(root, "private").apply { assertTrue(mkdir()) }
            val name = "track-01.mp3"
            val file = File(directory, name).apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val movedOriginal = File(root, "original-track.mp3")
            val opened = PrivateAudioPath.openDirectoryNoFollow(directory)
            var swapped = false
            var attributeReads = 0
            val swappingHandle = object : SecureDirectoryStream<Path> by opened {
                @Suppress("UNCHECKED_CAST")
                override fun <V : FileAttributeView> getFileAttributeView(
                    path: Path,
                    type: Class<V>,
                    vararg options: LinkOption,
                ): V? {
                    val view = opened.getFileAttributeView(path, type, *options) ?: return null
                    if (type != BasicFileAttributeView::class.java) return view
                    val basic = view as BasicFileAttributeView
                    val swappingView = object : BasicFileAttributeView {
                        override fun name(): String = basic.name()

                        override fun readAttributes(): BasicFileAttributes {
                            val attributes = basic.readAttributes()
                            attributeReads += 1
                            if (attributeReads == 2 && !swapped) {
                                swapped = true
                                Files.move(file.toPath(), movedOriginal.toPath())
                                file.writeBytes(byteArrayOf(9, 8, 7))
                            }
                            return attributes
                        }

                        override fun setTimes(
                            lastModifiedTime: FileTime?,
                            lastAccessTime: FileTime?,
                            createTime: FileTime?,
                        ) = basic.setTimes(lastModifiedTime, lastAccessTime, createTime)
                    }
                    return swappingView as V
                }

                override fun move(srcpath: Path, targetDirectory: SecureDirectoryStream<Path>, targetpath: Path) {
                    val actualTarget = if (targetDirectory === this) opened else targetDirectory
                    opened.move(srcpath, actualTarget, targetpath)
                }
            }
            try {
                assertFalse(
                    "A same-directory replacement must not be unlinked as though it were the inspected original.",
                    PrivateAudioPath.deleteRegularFileNoFollow(
                        swappingHandle,
                        directory.toPath().fileSystem.getPath(name),
                    ),
                )
                assertTrue("The replacement at the original name must remain intact.", file.isFile)
                assertEquals(listOf(9, 8, 7), file.readBytes().map(Byte::toInt))
                assertEquals(listOf(1, 2, 3), movedOriginal.readBytes().map(Byte::toInt))
            } finally {
                opened.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun retryingMissingFileCleansAbandonedDeleteQuarantineEntry() {
        val root = Files.createTempDirectory("aftertone-abandoned-delete-").toFile()
        try {
            val directory = File(root, "private").apply { assertTrue(mkdir()) }
            val targetName = directory.toPath().fileSystem.getPath("missing-track.mp3")
            val quarantineName = requireNotNull(PrivateAudioPath.deletionQuarantineName(targetName))
            val abandoned = File(directory, quarantineName.toString())
                .apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val opened = PrivateAudioPath.openDirectoryNoFollow(directory)

            val deleted = opened.use {
                PrivateAudioPath.deleteRegularFileNoFollow(it, targetName)
            }

            assertTrue("Retry should finish deletion left in the reserved quarantine name.", deleted)
            assertFalse("The abandoned private quarantine entry must not be orphaned.", abandoned.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun acceptsCanonicalChildPathWhenAudioDirectoryUsesSymlinkedParentAlias() {
        val root = Files.createTempDirectory("aftertone-private-parent-alias-").toFile()
        try {
            val realParent = File(root, "real-parent").apply { mkdir() }
            val parentAlias = File(root, "parent-alias")
            Files.createSymbolicLink(parentAlias.toPath(), realParent.toPath())
            val aliasedAudioDirectory = File(parentAlias, "audio")
            assertTrue(aliasedAudioDirectory.mkdir())

            val importedAudio = File(aliasedAudioDirectory, "a1b2c3d4-e5f6-4789-8123-abcdef012345.mp3")
                .apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val persistedCanonicalPath = File(importedAudio.canonicalPath)

            assertTrue(
                "A canonical imported-file path must remain owned when the app-private parent has an equivalent alias.",
                PrivateAudioPath.isDirectChild(aliasedAudioDirectory, persistedCanonicalPath),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun rejectsDirectChildWhenCandidateIsSymlinkToOutsideFile() {
        val root = Files.createTempDirectory("aftertone-private-child-link-").toFile()
        try {
            val audioDirectory = File(root, "audio").apply { mkdir() }
            val outsideAudio = File(root, "outside.mp3").apply { writeBytes(byteArrayOf(4, 5, 6)) }
            val linkedAudio = File(audioDirectory, "inside.mp3")
            Files.createSymbolicLink(linkedAudio.toPath(), outsideAudio.toPath())

            assertFalse(PrivateAudioPath.isDirectChild(audioDirectory, linkedAudio))
        } finally {
            root.deleteRecursively()
        }
    }
}
