package com.ledgerx.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun CloudDialog(state: CloudState, vm: LedgerViewModel, onDismiss: () -> Unit) {
    var email by rememberSaveable { mutableStateOf("") }
    // Do not put passwords in saved instance state.
    var password by remember { mutableStateOf("") }
    LaunchedEffect(state.owner) { password = "" }
    AlertDialog(
        onDismissRequest = { if (!state.busy) onDismiss() },
        title = { Text("Cloud account") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!state.configured) {
                    Text("Cloud is not configured in this build. Add your Supabase URL and publishable key using the setup guide, then rebuild.")
                } else if (state.owner == null) {
                    Text("Sign in to keep a private ledger in Supabase. Each account has its own records. Your existing offline ledger stays on this device and is available when you sign out.")
                    OutlinedTextField(email, { email = it }, label = { Text("Email") }, singleLine = true, enabled = !state.busy,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email), modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(password, { password = it }, label = { Text("Password") }, singleLine = true, enabled = !state.busy,
                        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth())
                    Button(onClick = { vm.authenticate(email, password, false) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Sign in") }
                    OutlinedButton(onClick = { vm.authenticate(email, password, true) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Create account") }
                    Text("After creating an account, confirm your email before signing in. Use a password that meets your project's requirements.")
                } else {
                    Text("Signed in as ${state.email ?: "your account"}")
                    Text("Records save on this device first. Tap Sync to upload them and download changes from your other devices. Sync before signing out or changing phones.")
                    Button(onClick = { vm.sync() }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Sync") }
                    Text("To copy this device's original offline ledger into the account shown above, use Upload offline records. The original stays on this device. Repeating the upload does not duplicate records.")
                    OutlinedButton(onClick = { vm.sync(importOffline = true) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Upload offline records") }
                    OutlinedButton(onClick = vm::signOut, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
                }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.message?.let { Text(it) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !state.busy) { Text("Close") } }
    )
}
