package dev.contactguard.dialer

import android.app.KeyguardManager
import android.content.Context
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Deleting goes through the phone's fingerprint prompt (screen-lock PIN as fallback).
 * No screen lock at all = no deleting from here, so a borrowed phone can't wipe contacts.
 */
fun confirmDelete(ctx: Context, name: String, onConfirmed: () -> Unit) {
    if (!ctx.getSystemService(KeyguardManager::class.java).isDeviceSecure) {
        Toast.makeText(ctx, "Set up a fingerprint or screen lock in Settings to delete contacts.", Toast.LENGTH_LONG).show()
        return
    }
    val b = BiometricPrompt.Builder(ctx).setTitle("Delete ${name.ifBlank { "contact" }}?").setSubtitle("Confirm with your fingerprint")
    if (Build.VERSION.SDK_INT >= 30) b.setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
    else @Suppress("DEPRECATION") b.setDeviceCredentialAllowed(true)
    b.build().authenticate(CancellationSignal(), ctx.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onConfirmed()
        override fun onAuthenticationError(code: Int, msg: CharSequence) {
            if (code != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED && code != BiometricPrompt.BIOMETRIC_ERROR_CANCELED)
                Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
        }
    })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactDetail(d: Detail, onBack: () -> Unit, onDelete: () -> Unit) {
    val ctx = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    // SIM records have no raw contacts to delete here.
                    if (d.rawIds.isNotEmpty()) IconButton(onClick = { confirmDelete(ctx, d.contact.name, onDelete) }) {
                        Icon(Icons.Outlined.Delete, "Delete contact")
                    }
                },
            )
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = pad) {
            item {
                Column(Modifier.fillMaxWidth().padding(vertical = Dimens.gutter), horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(d.contact.name, d.contact.photo, Dimens.avatarLarge)
                    Spacer(Modifier.height(Dimens.gutter))
                    Text(d.contact.name.ifBlank { "(No name)" }, style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(Dimens.gutter))
                    val first = d.phones.firstOrNull()?.number
                    Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        Quick(Icons.Outlined.Call, "Call", first != null) { first?.let { Phone.dial(ctx, it) } }
                        Quick(Icons.AutoMirrored.Outlined.Message, "Text", first != null) { first?.let { Phone.sms(ctx, it) } }
                    }
                }
            }
            if (d.phones.isNotEmpty()) item {
                Section("Phone") {
                    d.phones.forEach { m ->
                        ListItem(
                            headlineContent = { Text(formatNumber(m.number)) },
                            supportingContent = { Text(m.label) },
                            leadingContent = { Icon(Icons.Outlined.Call, null) },
                            trailingContent = { IconButton(onClick = { Phone.sms(ctx, m.number) }) { Icon(Icons.AutoMirrored.Outlined.Message, "Text ${m.number}") } },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable { Phone.dial(ctx, m.number) },
                        )
                    }
                }
            }
            if (d.emails.isNotEmpty()) item {
                Section("Email") {
                    d.emails.forEach {
                        ListItem({ Text(it) }, leadingContent = { Icon(Icons.Outlined.Email, null) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                    }
                }
            }
            item {
                Section("Saved in") {
                    ListItem({ Text(d.sources.joinToString("\n")) }, colors = ListItemDefaults.colors(containerColor = Color.Transparent))
                }
            }
        }
    }
}

@Composable
private fun Quick(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(56.dp), shape = CircleShape) { Icon(icon, null) }
        Spacer(Modifier.height(4.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}
