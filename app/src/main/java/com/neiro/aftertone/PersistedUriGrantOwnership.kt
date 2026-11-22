package com.neiro.aftertone

import android.net.Uri

internal class PersistedUriGrantOwnership {
    private var activeBatchClaims: Set<Uri>? = null
    private var activeExternalAudioQueueUris: Set<Uri>? = null
    private val incomingExternalAudioClaims = linkedSetOf<IncomingExternalAudioGrantClaim>()
    private val deferredReleaseRequests = linkedSetOf<Uri>()

    fun updateBatchImportUriClaims(claims: Set<Uri>): Set<Uri> {
        activeBatchClaims = claims
        return drainReleasableUris()
    }

    fun updateExternalAudioQueueUris(uris: Set<Uri>): Set<Uri> {
        activeExternalAudioQueueUris = uris
        return drainReleasableUris()
    }

    fun claimIncomingExternalAudioRequest(uri: Uri): IncomingExternalAudioGrantClaim =
        IncomingExternalAudioGrantClaim(uri).also(incomingExternalAudioClaims::add)

    fun finishIncomingExternalAudioRequest(claim: IncomingExternalAudioGrantClaim): Set<Uri> {
        if (!incomingExternalAudioClaims.remove(claim)) return emptySet()
        deferredReleaseRequests += claim.uri
        return drainReleasableUris()
    }

    fun requestRelease(uri: Uri): Set<Uri> {
        deferredReleaseRequests += uri
        return drainReleasableUris()
    }

    private fun drainReleasableUris(): Set<Uri> {
        val batchClaims = activeBatchClaims ?: return emptySet()
        val externalAudioQueueUris = activeExternalAudioQueueUris ?: return emptySet()
        val releasable = deferredReleaseRequests.filterTo(linkedSetOf()) { uri ->
            uri !in externalAudioQueueUris &&
                uri !in batchClaims &&
                incomingExternalAudioClaims.none { claim -> claim.uri == uri }
        }
        deferredReleaseRequests.removeAll(releasable)
        return releasable
    }
}

internal class IncomingExternalAudioGrantClaim internal constructor(internal val uri: Uri)
