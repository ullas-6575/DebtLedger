package com.ledgerx.ui.auth

import androidx.compose.animation.*
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ledgerx.ui.theme.*

/**
 * Full-screen login and sign-up screen.
 *
 * This is the mandatory entry point — there is no guest mode.
 * Sign-up requires internet to create the Supabase account.
 * After successful login, the session is stored encrypted locally
 * and subsequent opens skip this screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    busy: Boolean,
    message: String?,
    onSignIn: (email: String, password: String) -> Unit,
    onSignUp: (email: String, password: String) -> Unit
) {
    var isSignUp by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    // Never persist passwords across configuration changes.
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
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

            // ── Brand ──────────────────────────────────────
            Icon(
                Icons.Outlined.AccountBalanceWallet,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = Color.White.copy(alpha = 0.9f)
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "LedgerX",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                letterSpacing = 1.sp
            )
            Text(
                "Your personal finance ledger",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.7f)
            )

            Spacer(Modifier.height(48.dp))

            // ── Card ───────────────────────────────────────
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
                    Text(
                        if (isSignUp) "Create Account" else "Welcome Back",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Ink
                    )
                    Text(
                        if (isSignUp)
                            "Sign up to keep your finances synced across devices."
                        else
                            "Sign in to access your ledger.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Muted
                    )

                    Spacer(Modifier.height(4.dp))

                    // Email
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it; localError = null },
                        label = { Text("Email") },
                        leadingIcon = { Icon(Icons.Outlined.Email, null) },
                        singleLine = true,
                        enabled = !busy,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Email,
                            imeAction = ImeAction.Next
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Down) }
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Password
                    OutlinedTextField(
                        value = password,
                        onValueChange = { password = it; localError = null },
                        label = { Text("Password") },
                        leadingIcon = { Icon(Icons.Outlined.Lock, null) },
                        trailingIcon = {
                            IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                Icon(
                                    if (passwordVisible) Icons.Outlined.VisibilityOff
                                    else Icons.Outlined.Visibility,
                                    contentDescription = if (passwordVisible) "Hide" else "Show"
                                )
                            }
                        },
                        singleLine = true,
                        enabled = !busy,
                        visualTransformation = if (passwordVisible) VisualTransformation.None
                            else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = if (isSignUp) ImeAction.Next else ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onNext = { focusManager.moveFocus(FocusDirection.Down) },
                            onDone = {
                                focusManager.clearFocus()
                                if (!isSignUp) attemptAuth(
                                    email, password, "", false,
                                    onSignIn, onSignUp
                                ) { localError = it }
                            }
                        ),
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Confirm password (sign-up only)
                    AnimatedVisibility(
                        visible = isSignUp,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        OutlinedTextField(
                            value = confirmPassword,
                            onValueChange = { confirmPassword = it; localError = null },
                            label = { Text("Confirm Password") },
                            leadingIcon = { Icon(Icons.Outlined.Lock, null) },
                            singleLine = true,
                            enabled = !busy,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onDone = {
                                    focusManager.clearFocus()
                                    attemptAuth(
                                        email, password, confirmPassword, true,
                                        onSignIn, onSignUp
                                    ) { localError = it }
                                }
                            ),
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

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

                    // Primary action button
                    Button(
                        onClick = {
                            attemptAuth(
                                email, password, confirmPassword, isSignUp,
                                onSignIn, onSignUp
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
                                if (isSignUp) "Create Account" else "Sign In",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 16.sp
                            )
                        }
                    }

                    // Toggle sign-in / sign-up
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            if (isSignUp) "Already have an account?" else "Don't have an account?",
                            color = Muted,
                            style = MaterialTheme.typography.bodySmall
                        )
                        TextButton(
                            onClick = {
                                isSignUp = !isSignUp
                                localError = null
                                password = ""
                                confirmPassword = ""
                            },
                            enabled = !busy
                        ) {
                            Text(
                                if (isSignUp) "Sign In" else "Sign Up",
                                fontWeight = FontWeight.SemiBold,
                                color = Green
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))

            // Footer
            Text(
                "Your data is encrypted and stored securely.\nLedgerX works offline after first login.",
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                lineHeight = 18.sp
            )

            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Client-side validation before hitting Supabase. */
private fun attemptAuth(
    email: String,
    password: String,
    confirmPassword: String,
    isSignUp: Boolean,
    onSignIn: (String, String) -> Unit,
    onSignUp: (String, String) -> Unit,
    onError: (String) -> Unit
) {
    val trimmedEmail = email.trim()
    if (!trimmedEmail.contains('@') || trimmedEmail.length < 5) {
        onError("Enter a valid email address.")
        return
    }
    if (password.isBlank()) {
        onError("Enter your password.")
        return
    }
    if (password.length < 6) {
        onError("Password must be at least 6 characters.")
        return
    }
    if (isSignUp) {
        if (confirmPassword != password) {
            onError("Passwords do not match.")
            return
        }
        onSignUp(trimmedEmail, password)
    } else {
        onSignIn(trimmedEmail, password)
    }
}
