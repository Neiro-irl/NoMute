package com.neiro.aftertone

internal fun canShowRecoveryRetry(
    isRecoveryDiagnosticsReadOnly: Boolean,
    hasPendingRecoveryCleanup: Boolean,
): Boolean = canShowRecoveryRetry(
    isRecoveryDiagnosticsReadOnly = isRecoveryDiagnosticsReadOnly,
    hasPendingRecoveryCleanup = hasPendingRecoveryCleanup,
    recoveryRetryAuthorizationConsumed = false,
)

internal fun canShowRecoveryRetry(
    isRecoveryDiagnosticsReadOnly: Boolean,
    hasPendingRecoveryCleanup: Boolean,
    recoveryRetryAuthorizationConsumed: Boolean,
): Boolean = !isRecoveryDiagnosticsReadOnly && hasPendingRecoveryCleanup && !recoveryRetryAuthorizationConsumed

internal fun canShowExternalAudioRetry(isRecoveryDiagnosticsReadOnly: Boolean): Boolean =
    !isRecoveryDiagnosticsReadOnly

internal fun canProcessExternalAudioQueue(isRecoveryDiagnosticsReadOnly: Boolean): Boolean =
    !isRecoveryDiagnosticsReadOnly

internal fun canShowExternalAudioFailureActions(isRecoveryDiagnosticsReadOnly: Boolean): Boolean =
    !isRecoveryDiagnosticsReadOnly
