package com.neiro.aftertone.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.neiro.aftertone.R

@Composable
internal fun LicenseNoticeDialog(onDismiss: () -> Unit) {
    val resources = LocalResources.current
    var showThirdPartyNotices by remember { mutableStateOf(false) }
    val licenseText = remember(resources) {
        resources.openRawResource(R.raw.gpl_3_0).bufferedReader().use { it.readText() }
    }
    val thirdPartyNoticeText = remember(resources) {
        resources.openRawResource(R.raw.third_party_notices).bufferedReader().use { it.readText() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (showThirdPartyNotices) R.string.third_party_licenses_title else R.string.license_dialog_title,
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (showThirdPartyNotices) {
                    Text(thirdPartyNoticeText, style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(stringResource(R.string.license_copyright))
                    Text(stringResource(R.string.license_no_warranty))
                    Text(stringResource(R.string.license_conveyance))
                    TextButton(onClick = { showThirdPartyNotices = true }) {
                        Text(stringResource(R.string.third_party_licenses_action))
                    }
                    Text(
                        text = stringResource(R.string.license_full_text),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(licenseText, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (showThirdPartyNotices) showThirdPartyNotices = false else onDismiss() }) {
                Text(stringResource(if (showThirdPartyNotices) R.string.license_back else R.string.license_close))
            }
        },
    )
}
