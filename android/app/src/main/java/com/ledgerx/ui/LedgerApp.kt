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

/**
 * Main app content — only shown after successful authentication.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainContent(vm: LedgerViewModel) {
    val entries by vm.movements.collectAsStateWithLifecycle()
    val ready by vm.ready.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val saving by vm.saving.collectAsStateWithLifecycle()
    val cloud by vm.cloud.collectAsStateWithLifecycle()
    val authState by vm.auth.collectAsStateWithLifecycle()
    var showCloud by rememberSaveable { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var add by rememberSaveable { mutableStateOf(false) }
    val summary = remember(entries) { Ledger.summarize(entries) }
    val tabs = listOf("Overview", "Money", "Loans", "History")
    val icons = listOf(Icons.Outlined.Dashboard, Icons.Outlined.AccountBalanceWallet, Icons.Outlined.People, Icons.AutoMirrored.Outlined.ReceiptLong)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("LedgerX", fontWeight = FontWeight.Bold)
                        Text(
                            authState.email ?: "Your account",
                            style = MaterialTheme.typography.labelMedium,
                            color = Muted
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { showCloud = true }, enabled = !saving && !add) {
                        Text("Sync")
                    }
                    IconButton(onClick = vm::signOut) {
                        @Suppress("DEPRECATION")
                        Icon(Icons.Outlined.ExitToApp, contentDescription = "Sign out")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                tabs.forEachIndexed { i, label ->
                    NavigationBarItem(
                        selected = i == tab,
                        onClick = { tab = i },
                        icon = { Icon(icons[i], contentDescription = null) },
                        label = { Text(label) }
                    )
                }
            }
        },
        floatingActionButton = {
            if (ready && !cloud.busy) ExtendedFloatingActionButton(
                onClick = { vm.clearError(); add = true },
                icon = { Icon(Icons.Outlined.Add, null) },
                text = { Text("Add record") },
                containerColor = Green,
                contentColor = Color.White
            )
        }
    ) { padding ->
        if (!ready) Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            if (error == null) CircularProgressIndicator(color = Green)
            else Text(error!!, color = Red, modifier = Modifier.padding(24.dp))
        } else LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 100.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Text(tabs[tab], style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold) }
            when (tab) {
                0 -> {
                    item { BalanceCard(summary) }
                    item { Accounts(summary) }
                    item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Stat("Owed to you", summary.owedToMe, Modifier.weight(1f))
                        Stat("You owe", summary.iOwe, Modifier.weight(1f))
                    } }
                    item { Expenses(entries) }
                    item { SectionTitle("Recent activity") }
                    if (entries.isEmpty()) item { Empty("A fresh start", "Add your opening funds as Income, then record your spending and loans.") }
                    items(entries.take(5), key = { it.id }) { MovementRow(it) }
                }
                1 -> {
                    item { Accounts(summary) }
                    item { Empty("Move money between accounts", "Use Transfer to move cash, bKash or bank funds. Transfers don't count as income or expenses.") }
                    item { Expenses(entries) }
                    item { SectionTitle("Income & spending · this month") }
                    val monthEntries = entries.filter { monthOf(it) == YearMonth.now() }
                    item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Stat("Income", monthEntries.filter { it.kind == Kind.INCOME }.sumOf { it.amount }, Modifier.weight(1f))
                        Stat("Expenses", monthEntries.filter { it.kind == Kind.EXPENSE }.sumOf { it.amount }, Modifier.weight(1f))
                    } }
                }
                2 -> {
                    item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Stat("Owed to you", summary.owedToMe, Modifier.weight(1f))
                        Stat("You owe", summary.iOwe, Modifier.weight(1f))
                    } }
                    item { Text("Positive balance: they owe you. Negative balance: you owe them. These are your private records.", color = Muted) }
                    if (summary.people.isEmpty()) item { Empty("Keep track of every loan", "Choose Loan given or Loan taken and enter a person's name. Partial repayments update their balance.") }
                    items(summary.people.entries.sortedBy { it.key.lowercase() }, key = { it.key }) { (person, balance) ->
                        Card(Modifier.fillMaxWidth()) { Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Person, null, tint = Green)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) { Text(person, fontWeight = FontWeight.SemiBold); Text(if (balance > 0) "Owes you" else if (balance < 0) "You owe" else "Settled", color = Muted, style = MaterialTheme.typography.bodySmall) }
                            Text(Money.format(balance), fontWeight = FontWeight.Bold, color = if (balance < 0) Red else Green)
                        } }
                    }
                }
                3 -> {
                    item { Text("All records · ${entries.size} entries", color = Muted) }
                    if (entries.isEmpty()) item { Empty("No records yet", "Your income, spending, transfers and loan activity will appear here.") }
                    items(entries, key = { it.id }) { MovementRow(it) }
                }
            }
        }
    }
    if (add) EntryDialog(summary.people.keys.toList(), saving, error, onDismiss = { if (!saving) { add = false; vm.clearError() } }, onSave = { vm.save(it) { add = false; vm.clearError() } })
    if (showCloud) SyncDialog(cloud, vm, onDismiss = { showCloud = false })
}

// ── Sync dialog (auth removed — replaced by LoginScreen) ────────────

@Composable
private fun SyncDialog(state: CloudState, vm: LedgerViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = { if (!state.busy) onDismiss() },
        title = { Text("Sync") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Records save on this device first. Tap Sync to upload them and download changes from your other devices.")
                Button(onClick = { vm.sync() }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Sync") }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("To copy this device's original offline ledger into your account, use Upload offline records. The original stays on this device. Repeating does not duplicate records.", style = MaterialTheme.typography.bodySmall, color = Muted)
                OutlinedButton(onClick = { vm.sync(importOffline = true) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Upload offline records") }
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.message?.let { Text(it) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !state.busy) { Text("Close") } }
    )
}

// ── Shared UI components ────────────────────────────────────────────

@Composable private fun BalanceCard(s: LedgerSummary) {
    Card(colors = CardDefaults.cardColors(containerColor = Green), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("AVAILABLE MONEY", color = Color.White.copy(alpha = .75f), style = MaterialTheme.typography.labelMedium)
            Text(Money.format(s.available), color = Color.White, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            HorizontalDivider(color = Color.White.copy(alpha = .2f))
            Text("Net worth  ${Money.format(s.netWorth)}", color = Color.White)
            Text("Accounts + money owed to you − money you owe", color = Color.White.copy(alpha = .75f), style = MaterialTheme.typography.bodySmall)
        }
    }
}
@Composable private fun Accounts(s: LedgerSummary) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle("Your accounts")
        Account.entries.forEach { account ->
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(when (account) { Account.CASH -> Icons.Outlined.Payments; Account.BKASH -> Icons.Outlined.PhoneAndroid; Account.BANK -> Icons.Outlined.AccountBalance }, null, tint = Green)
                    Text(account.label, Modifier.weight(1f).padding(start = 12.dp), fontWeight = FontWeight.Medium)
                    Text(Money.format(s.accounts.getValue(account)), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
@Composable private fun Stat(label: String, amount: Long, modifier: Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(label, color = Muted, style = MaterialTheme.typography.labelMedium); Text(Money.format(amount), fontWeight = FontWeight.Bold) }
    }
}
@Composable private fun Expenses(entries: List<Movement>) {
    val month = YearMonth.now()
    val totals = Ledger.summarize(entries.filter { monthOf(it) == month }).expenses
    val total = totals.values.sum()
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionTitle("Expenses · ${month.format(DateTimeFormatter.ofPattern("MMMM yyyy"))}")
            Text(Money.format(total), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Category.entries.forEach { category ->
                val amount = totals.getValue(category)
                Row { Text(category.label, Modifier.weight(1f)); Text(Money.format(amount), color = Muted) }
                LinearProgressIndicator(progress = { if (total == 0L) 0f else amount.toFloat() / total.toFloat() }, modifier = Modifier.fillMaxWidth(), color = Green, trackColor = Background)
            }
        }
    }
}
@Composable private fun SectionTitle(text: String) { Text(text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium) }
@Composable private fun Empty(title: String, description: String) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = GreenSurface)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, fontWeight = FontWeight.SemiBold); Text(description, color = Muted) }
    }
}
@Composable private fun MovementRow(m: Movement) {
    val incoming = m.kind in setOf(Kind.INCOME, Kind.BORROW, Kind.RECEIVE_REPAYMENT)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row { Text(m.person ?: m.category?.label ?: m.kind.label, Modifier.weight(1f), fontWeight = FontWeight.SemiBold); Text((if (m.kind == Kind.TRANSFER) "" else if (incoming) "+" else "−") + Money.format(m.amount), color = if (incoming) Green else Ink, fontWeight = FontWeight.Bold) }
            Text("${m.kind.label} · ${m.account.label}" + (m.destination?.let { " → ${it.label}" } ?: ""), color = Muted, style = MaterialTheme.typography.bodySmall)
            if (m.note.isNotBlank()) Text(m.note, style = MaterialTheme.typography.bodySmall)
            Text(Instant.ofEpochMilli(m.occurredAt).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd MMM yyyy · HH:mm")), color = Muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun EntryDialog(people: List<String>, saving: Boolean, serverError: String?, onDismiss: () -> Unit, onSave: (Movement) -> Unit) {
    var kindName by rememberSaveable { mutableStateOf(Kind.EXPENSE.name) }
    var accountName by rememberSaveable { mutableStateOf(Account.CASH.name) }
    var destinationName by rememberSaveable { mutableStateOf(Account.BKASH.name) }
    var categoryName by rememberSaveable { mutableStateOf(Category.EAT.name) }
    var amount by rememberSaveable { mutableStateOf("") }
    var person by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    val kind = Kind.valueOf(kindName)
    val loan = kind in setOf(Kind.LEND, Kind.BORROW, Kind.RECEIVE_REPAYMENT, Kind.PAY_REPAYMENT)
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Add a record") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Choice("Type", Kind.entries.map { it.name to it.label }, kindName, !saving) { kindName = it }
            Choice("Account", Account.entries.map { it.name to it.label }, accountName, !saving) { accountName = it }
            if (kind == Kind.TRANSFER) Choice("To account", Account.entries.map { it.name to it.label }, destinationName, !saving) { destinationName = it }
            if (kind == Kind.EXPENSE) Choice("Category", Category.entries.map { it.name to it.label }, categoryName, !saving) { categoryName = it }
            OutlinedTextField(amount, { amount = it; localError = null }, label = { Text("Amount (BDT)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true, modifier = Modifier.fillMaxWidth(), enabled = !saving)
            if (loan) {
                OutlinedTextField(person, { person = it }, label = { Text("Person's name") }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !saving)
                if (people.isNotEmpty()) {
                    var expanded by remember { mutableStateOf(false) }
                    Box { TextButton(onClick = { expanded = true }, enabled = !saving) { Text("Choose existing person") }; DropdownMenu(expanded, { expanded = false }) { people.forEach { name -> DropdownMenuItem(text = { Text(name) }, onClick = { person = name; expanded = false }) } } }
                }
            }
            OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth(), maxLines = 3, enabled = !saving)
            if (kind == Kind.LEND) Text("Reduces this account. Increases what this person owes you.", color = Muted)
            if (kind == Kind.BORROW) Text("Increases this account. Increases what you owe this person.", color = Muted)
            val error = localError ?: serverError
            if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = {
        Button(enabled = !saving, onClick = {
            try {
                val movement = Movement(UUID.randomUUID().toString(), kind, Money.parse(amount), Account.valueOf(accountName), if (kind == Kind.TRANSFER) Account.valueOf(destinationName) else null, if (kind == Kind.EXPENSE) Category.valueOf(categoryName) else null, if (loan) person else null, note)
                onSave(movement)
            } catch (e: IllegalArgumentException) { localError = e.message }
        }) { Text(if (saving) "Saving…" else "Save record") }
    }, dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") } })
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun Choice(label: String, options: List<Pair<String, String>>, selected: String, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, { if (enabled) expanded = !expanded }) {
        OutlinedTextField(options.first { it.first == selected }.second, {}, readOnly = true, label = { Text(label) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor().fillMaxWidth(), enabled = enabled)
        ExposedDropdownMenu(expanded, { expanded = false }) { options.forEach { (value, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(value); expanded = false }) } }
    }
}
