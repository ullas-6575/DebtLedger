package com.ledgerx.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ledgerx.domain.*
import com.ledgerx.ui.auth.LoginScreen
import com.ledgerx.ui.auth.ProfileSetupScreen
import com.ledgerx.ui.theme.*
import java.time.Instant
import java.time.ZoneId
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.UUID

private fun monthOf(m: Movement) = YearMonth.from(Instant.ofEpochMilli(m.occurredAt).atZone(ZoneId.systemDefault()))

/**
 * Root composable that gates the entire app behind authentication.
 *
 * Flow: LOADING → LOGIN → PROFILE_SETUP → MAIN
 */
@Composable
fun LedgerApp(vm: LedgerViewModel) {
    val authState by vm.auth.collectAsStateWithLifecycle()

    LedgerXTheme {
        when (authState.screen) {
            AuthScreen.LOADING -> LoadingScreen()

            AuthScreen.LOGIN -> LoginScreen(
                busy = authState.busy,
                message = authState.message,
                onSignIn = vm::signIn,
                onSignUp = vm::signUp
            )

            AuthScreen.PROFILE_SETUP -> ProfileSetupScreen(
                email = authState.email ?: "",
                busy = authState.busy,
                message = authState.message,
                onCreateProfile = vm::createProfile
            )

            AuthScreen.MAIN -> LedgerScreen(vm)
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(
        Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator(color = Green)
            Text(
                "Loading your ledger…",
                style = MaterialTheme.typography.bodyMedium,
                color = Muted
            )
        }
    }
}

