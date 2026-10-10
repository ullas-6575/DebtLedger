package com.ledgerx.ui.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgerx.ui.theme.*

/**
 * Post-signup profile creation screen.
 *
 * After successful Supabase sign-up and email confirmation, the user
 * is asked to choose a unique username and display name. This creates
 * the `profiles` row in Supabase.
 */
@Composable
fun ProfileSetupScreen(
    email: String,
    busy: Boolean,
    message: String?,
    onCreateProfile: (username: String, displayName: String) -> Unit
) {
    var username by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    val focusManager = LocalFocusManager.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(GreenDark, Green, GreenLight),
                    startY = 0f,
                    endY = 1200f
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(60.dp))

            Icon(
                Icons.Outlined.PersonAdd,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = Color.White.copy(alpha = 0.9f)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Complete Your Profile",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                "Choose a username so friends can find you",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f)
            )

            Spacer(Modifier.height(40.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier.padding(28.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Signed-in email confirmation
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = GreenSurface),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Outlined.CheckCircle,
                                contentDescription = null,
                                tint = Green,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                "Signed in as $email",
                                style = MaterialTheme.typography.bodySmall,
                                color = GreenDark
                            )
                        }
                    }

                    // Display name
                    OutlinedTextField(
                        value = displayName,
                        onValueChange = {
                            if (it.length <= 80) { displayName = it; localError = null }
                        },
                        label = { Text("Display Name") },
                        placeholder = { Text("Ullas Biswas") },
                        leadingIcon = { Icon(Icons.Outlined.Badge, null) },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                        keyboardActions = KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Down) }
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Username
                    OutlinedTextField(
                        value = username,
                        onValueChange = { raw ->
                            // Only allow lowercase, digits, underscores
                            val filtered = raw.lowercase().filter { it.isLetterOrDigit() || it == '_' }
                            if (filtered.length <= 32) { username = filtered; localError = null }
                        },
                        label = { Text("Username") },
                        placeholder = { Text("ullas_biswas") },
                        leadingIcon = { Text("@", color = Muted, modifier = Modifier.padding(start = 12.dp)) },
                        supportingText = {
                            Text(
                                "3–32 characters: lowercase letters, digits, underscores",
                                color = Muted
                            )
                        },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                focusManager.clearFocus()
                                validateAndSubmit(
                                    username, displayName, onCreateProfile
                                ) { localError = it }
                            }
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Error / message
                    val displayError = localError ?: message
                    if (displayError != null) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (localError != null) RedSurface else AmberSurface
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Row(
                                Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    if (localError != null) Icons.Outlined.ErrorOutline
                                    else Icons.Outlined.Info,
                                    contentDescription = null,
                                    tint = if (localError != null) Red else Amber,
                                    modifier = Modifier.size(20.dp)
                                )
                                Text(
                                    displayError,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Ink
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    Button(
                        onClick = {
                            validateAndSubmit(
                                username, displayName, onCreateProfile
                            ) { localError = it }
                        },
                        enabled = !busy,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Green)
                    ) {
                        if (busy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                "Continue",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))

            Text(
                "Your username is permanent and visible to friends.\nChoose carefully.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                lineHeight = 18.sp
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun validateAndSubmit(
    username: String,
    displayName: String,
    onCreateProfile: (String, String) -> Unit,
    onError: (String) -> Unit
) {
    val trimmedName = displayName.trim()
    if (trimmedName.isEmpty() || trimmedName.length > 80) {
        onError("Enter a display name (1–80 characters).")
        return
    }
    if (!Regex("[a-z0-9_]{3,32}").matches(username)) {
        onError("Username must be 3–32 characters: lowercase letters, digits, or underscores.")
        return
    }
    onCreateProfile(username, trimmedName)
}
