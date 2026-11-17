package com.neiro.aftertone.storage

import android.content.Context
import com.neiro.aftertone.BuildConfig

internal fun isReadOnlyRecoveryDiagnosticBuild(context: Context): Boolean =
    BuildConfig.RECOVERY_DIAGNOSTIC_READ_ONLY &&
        BuildConfig.APPLICATION_ID == ORDINARY_AFTERTONE_APPLICATION_ID &&
        runCatching { context.applicationContext.packageName }.getOrNull() == ORDINARY_AFTERTONE_APPLICATION_ID

private const val ORDINARY_AFTERTONE_APPLICATION_ID = "com.neiro.aftertone"
