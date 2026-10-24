package com.ledgerx.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ledgerx.data.entities.*
import com.ledgerx.data.repository.*
import com.ledgerx.domain.*
import com.ledgerx.ui.theme.*
import java.time.Instant
import java.time.YearMonth
import java.time.format.DateTimeFormatter

internal fun displayDate(time: Long) = Instant.ofEpochMilli(time).atZone(LedgerZone).format(DateTimeFormatter.ofPattern("dd MMM yyyy"))
internal fun personName(state: LedgerState, id: String) = state.snapshot.profiles.find { it.id == id }?.displayName ?: "Ledger member"
internal fun otherPerson(loan: LoanEntity, owner: String) = if (loan.lenderId == owner) loan.borrowerId else loan.lenderId
internal fun accountName(value: String) = Account.entries.find { it.name == value }?.label ?: value
internal fun statusLabel(value: String) = when (value) {
    "LOCAL_PENDING" -> "Waiting to sync"
    "UNDER_REVIEW", "PENDING" -> "Awaiting approval"
    "PARTIALLY_REPAID" -> "Partly repaid"
    "SUPERSEDED" -> "Replaced by correction"
    else -> value.lowercase().replaceFirstChar { it.uppercase() }.replace('_', ' ')
}
internal fun LedgerState.decisionQueued(id: String) = queue.any { it.entityId == id && it.action in setOf("ACCEPT", "REJECT") }
internal fun LedgerState.syncLabel() = when {
    sync.running -> "Syncing…"
    sync.error != null || queue.any { it.lastError != null } -> "Sync needs attention"
    queue.isNotEmpty() -> "${queue.size} changes waiting"
    sync.lastSuccessAt != null -> "Up to date"
    else -> "Saved on this device"
}
private fun thisMonth(state: LedgerState) = state.snapshot.transactions.filter {
    YearMonth.from(Instant.ofEpochMilli(it.occurredAt).atZone(LedgerZone)) == YearMonth.now(LedgerZone)
}
private fun outstanding(state: LedgerState): Pair<Long, Long> {
    val legacy = Ledger.summarize(state.legacy)
    val loans = state.snapshot.loans.filter { it.status in setOf("ACTIVE", "PARTIALLY_REPAID") }
    return (loans.filter { it.lenderId == state.owner }.sumOf { it.principalMinor - it.repaidMinor } + legacy.owedToMe) to
        (loans.filter { it.borrowerId == state.owner }.sumOf { it.principalMinor - it.repaidMinor } + legacy.iOwe)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerScreen(vm: LedgerViewModel) {
    val state by vm.ledger.state.collectAsStateWithLifecycle()
    val auth by vm.auth.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var loanId by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val tabs = listOf("Overview", "Money", "Loans", "History")
    val icons = listOf(Icons.Outlined.Dashboard, Icons.Outlined.AccountBalanceWallet, Icons.Outlined.People, Icons.AutoMirrored.Outlined.ReceiptLong)
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); vm.ledger.clearMessage() }
    }
    fun open(value: String) { vm.ledger.clearMessage(); sheet = value }
    Scaffold(
        containerColor = Background,
        topBar = { TopAppBar(
            title = { Column {
                Text("LedgerX", fontWeight = FontWeight.Bold, color = Ink)
                Text("Your money, clearly.", style = MaterialTheme.typography.labelMedium, color = Muted)
            } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Background),
            actions = {
                IconButton(onClick = { open("sync") }) {
                    Icon(if (state.sync.error != null || state.queue.any { it.lastError != null }) Icons.Outlined.CloudOff else Icons.Outlined.CloudDone, "Cloud sync", tint = Green)
                }
                IconButton(onClick = { open("profile") }) { Icon(Icons.Outlined.AccountCircle, "Profile and settings", tint = Ink) }
            }
        ) },
        bottomBar = { NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
            tabs.forEachIndexed { i, label -> NavigationBarItem(
                selected = tab == i, onClick = { tab = i }, icon = { Icon(icons[i], null) }, label = { Text(label) },
                colors = NavigationBarItemDefaults.colors(indicatorColor = GreenSurface, selectedIconColor = Green, selectedTextColor = Green)
            ) }
        } },
        floatingActionButton = { if (state.ready) ExtendedFloatingActionButton(
            onClick = { open("add") }, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("Add record") },
            containerColor = Green, contentColor = Color.White
        ) },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { padding ->
        if (!state.ready) Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        else when (tab) {
            0 -> Overview(state, Modifier.padding(padding), { open("sync") }, { tab = 2 }, { tab = 3 })
            1 -> MoneyPanel(state, Modifier.padding(padding)) { open("categories") }
            2 -> LoansPanel(state, Modifier.padding(padding), { open("friends") }) { loanId = it; open("loan") }
            else -> HistoryPanel(state, vm.ledger, Modifier.padding(padding))
        }
    }
    if (sheet != null) ModalBottomSheet(onDismissRequest = { sheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Background) {
        when (sheet) {
            "add" -> AddPanel(state, vm.ledger) { sheet = null }
            "sync" -> SyncPanel(state, vm.ledger)
            "profile" -> ProfilePanel(state, auth.email.orEmpty(), vm.ledger, { open(it) }) { sheet = null; vm.signOut() }
            "friends" -> FriendsPanel(state, vm.ledger)
            "categories" -> CategoriesPanel(state, vm.ledger)
            "loan" -> state.snapshot.loans.find { it.id == loanId }?.let { LoanDetail(state, it, vm.ledger) }
        }
    }
}

@Composable
internal fun WhiteCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}
@Composable
internal fun SectionTitle(title: String, action: String? = null, onClick: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Ink)
        if (action != null) TextButton(onClick) { Text(action) }
    }
}
@Composable
internal fun EmptyState(title: String, body: String, icon: ImageVector = Icons.AutoMirrored.Outlined.ReceiptLong) {
    WhiteCard {
        Icon(icon, null, tint = Green, modifier = Modifier.size(28.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = Muted)
    }
}
@Composable
private fun Overview(state: LedgerState, modifier: Modifier, sync: () -> Unit, loans: () -> Unit, history: () -> Unit) {
    val accounts = balances(state.snapshot, state.legacy, state.owner)
    val (owed, owing) = outstanding(state)
    val activeIds = state.snapshot.loans.filter { it.status in setOf("ACTIVE", "PARTIALLY_REPAID") }.map { it.id }.toSet()
    val approvals = state.snapshot.loans.count { it.status == "UNDER_REVIEW" && it.proposedBy != state.owner && !state.decisionQueued(it.id) } +
        state.snapshot.events.count { it.status == "PENDING" && it.proposedBy != state.owner && it.loanId in activeIds && !state.decisionQueued(it.id) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 100.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Card(colors = CardDefaults.cardColors(containerColor = Green), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("AVAILABLE MONEY", color = Color.White.copy(alpha = .75f), style = MaterialTheme.typography.labelMedium)
                Text(Money.format(accounts.values.sum()), color = Color.White, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                HorizontalDivider(color = Color.White.copy(alpha = .2f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Net worth", color = Color.White.copy(alpha = .85f))
                    Text(Money.format(accounts.values.sum() + owed - owing), color = Color.White, fontWeight = FontWeight.SemiBold)
                }
                Text("Accounts + money owed to you − money you owe", color = Color.White.copy(alpha = .7f), style = MaterialTheme.typography.bodySmall)
            }
        } }
        item { TextButton(onClick = sync, contentPadding = PaddingValues(0.dp)) {
            Icon(if (state.sync.running) Icons.Outlined.Sync else Icons.Outlined.CloudQueue, null, Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp)); Text(state.syncLabel(), style = MaterialTheme.typography.labelMedium)
        } }
        item { AccountCard(accounts) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("Owed to you", owed, Green, Modifier.weight(1f))
            StatCard("You owe", owing, Red, Modifier.weight(1f))
        } }
        if (approvals > 0) item { OutlinedCard(onClick = loans, colors = CardDefaults.outlinedCardColors(containerColor = AmberSurface)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.PendingActions, null, tint = Ink); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) { Text("$approvals awaiting your approval", fontWeight = FontWeight.SemiBold); Text("Review shared loan requests", style = MaterialTheme.typography.bodySmall, color = Muted) }
                Icon(Icons.Outlined.ChevronRight, null)
            }
        } }
        item { SpendingCard(state) }
        item { SectionTitle("Recent activity", "See all", history) }
        val recent = state.snapshot.transactions.filter { it.summaryStatus == "ACTIVE" }.sortedByDescending { it.occurredAt }.take(4)
        if (recent.isEmpty()) item { EmptyState("A clear start", "Use Add record to track income, spending, transfers or a shared loan.") }
        else items(recent, key = { it.id }) { TransactionTile(it, state.snapshot.categories) }
    }
}
@Composable
private fun AccountCard(accounts: Map<String, Long>) {
    WhiteCard {
        Text("Your accounts", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        accounts.entries.forEachIndexed { index, (name, amount) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).background(GreenSurface, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Icon(when (name) { "CASH" -> Icons.Outlined.Payments; "BANK" -> Icons.Outlined.AccountBalance; else -> Icons.Outlined.PhoneAndroid }, null, tint = Green, modifier = Modifier.size(21.dp))
                }
                Text(accountName(name), Modifier.weight(1f).padding(horizontal = 12.dp), style = MaterialTheme.typography.bodyMedium)
                Text(Money.format(amount), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            if (index < accounts.size - 1) HorizontalDivider(color = Background)
        }
    }
}
@Composable
private fun StatCard(label: String, amount: Long, color: Color, modifier: Modifier = Modifier) {
    WhiteCard(modifier) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = Muted)
        Text(Money.format(amount), color = color, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}
@Composable
private fun SpendingCard(state: LedgerState) {
    val rows = thisMonth(state).filter { it.type == "EXPENSE" }
    val total = rows.sumOf { it.amountMinor }
    WhiteCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Monthly expenses", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(YearMonth.now(LedgerZone).format(DateTimeFormatter.ofPattern("MMMM yyyy")), style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            Text(Money.format(total), fontWeight = FontWeight.Bold)
        }
        if (rows.isEmpty()) Text("No spending recorded this month.", color = Muted, style = MaterialTheme.typography.bodyMedium)
        rows.groupBy { it.categoryId }.entries.sortedByDescending { it.value.sumOf { t -> t.amountMinor } }.forEach { (id, entries) ->
            val category = state.snapshot.categories.find { it.id == id }
            val amount = entries.sumOf { it.amountMinor }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row {
                    Text("${category?.icon ?: "📦"}  ${category?.name ?: "Other"}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(Money.format(amount), style = MaterialTheme.typography.bodyMedium)
                }
                LinearProgressIndicator(progress = { if (total > 0) amount.toFloat() / total else 0f }, modifier = Modifier.fillMaxWidth().height(4.dp), color = Green, trackColor = GreenSurface)
            }
        }
    }
}
@Composable
private fun MoneyPanel(state: LedgerState, modifier: Modifier, categories: () -> Unit) {
    val rows = thisMonth(state)
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { SectionTitle("Money", "Categories", categories) }
        item { AccountCard(balances(state.snapshot, state.legacy, state.owner)) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatCard("Income this month", rows.filter { it.type == "INCOME" }.sumOf { it.amountMinor }, Green, Modifier.weight(1f))
            StatCard("Spent this month", rows.filter { it.type == "EXPENSE" }.sumOf { it.amountMinor }, Red, Modifier.weight(1f))
        } }
        item { SpendingCard(state) }
        item { Text("Transfers move money between your accounts. Shared loans affect balances only after approval.", style = MaterialTheme.typography.bodySmall, color = Muted) }
    }
}
@Composable
private fun LoansPanel(state: LedgerState, modifier: Modifier, friends: () -> Unit, open: (String) -> Unit) {
    var closed by rememberSaveable { mutableStateOf(false) }
    val loans = state.snapshot.loans.filter { (it.status in setOf("REPAID", "REJECTED", "SUPERSEDED", "CANCELLED")) == closed }.sortedByDescending { it.createdAt }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionTitle("Shared loans", "Friends", friends) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!closed, { closed = false }, { Text("Open") })
            FilterChip(closed, { closed = true }, { Text("Completed") })
        } }
        if (loans.isEmpty()) item { EmptyState(if (closed) "No completed shared loans" else "No open shared loans", "Choose Give loan or Borrow in Add record. Use Name only for someone without an account, or choose an app friend for a shared loan.", Icons.Outlined.People) }
        items(loans, key = { it.id }) { loan ->
            Card(onClick = { open(loan.id) }, colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(personName(state, otherPerson(loan, state.owner)), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(if (loan.lenderId == state.owner) "You lent" else "You borrowed", color = Muted, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(Money.format(loan.principalMinor - loan.repaidMinor), fontWeight = FontWeight.Bold, color = if (loan.lenderId == state.owner) Green else Red)
                        Icon(Icons.Outlined.ChevronRight, null, tint = Muted)
                    }
                    val pending = state.snapshot.events.count { it.loanId == loan.id && it.status == "PENDING" && it.proposedBy != state.owner && !state.decisionQueued(it.id) }
                    Text(if (state.decisionQueued(loan.id)) "Decision waiting to sync" else if (pending > 0) "$pending proposals to review" else statusLabel(loan.status), color = if (pending > 0 || loan.status == "UNDER_REVIEW") Green else Muted, style = MaterialTheme.typography.labelMedium)
                    if (loan.description.isNotBlank()) Text(loan.description, maxLines = 1, overflow = TextOverflow.Ellipsis, color = Muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (state.legacy.isNotEmpty()) {
            item { SectionTitle("Private loans · name only") }
            item { Text("Use Add record → Loan repayment → Name only to record money returned.", color = Muted, style = MaterialTheme.typography.bodySmall) }
            items(Ledger.summarize(state.legacy).people.entries.filter { (it.value == 0L) == closed }, key = { "legacy:${it.key}" }) { (name, balance) -> WhiteCard {
                Row { Text(name, Modifier.weight(1f)); Text(Money.format(kotlin.math.abs(balance)), fontWeight = FontWeight.SemiBold) }
                Text(if (balance > 0) "Owes you" else if (balance < 0) "You owe" else "Settled", color = Muted)
            } }
        }
    }
}
@Composable
private fun HistoryPanel(state: LedgerState, controller: LedgerController, modifier: Modifier) {
    var trash by rememberSaveable { mutableStateOf(false) }
    val s = state.snapshot
    val rows = s.transactions.filter { (it.summaryStatus == "TRASHED") == trash }.sortedByDescending { it.occurredAt }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp, 8.dp, 20.dp, 100.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionTitle("History") }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!trash, { trash = false }, { Text("All records") })
            FilterChip(trash, { trash = true }, { Text("Trash") }, leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp)) })
        } }
        if (trash) item { Text("Original details stay in Trash for 30 days. Once backed up, they are permanently deleted; monthly totals remain.", color = Muted, style = MaterialTheme.typography.bodyMedium) }
        if (!trash) {
            val summaries = s.summaries.sortedWith(compareByDescending<MonthlySummaryEntity> { it.year }.thenByDescending { it.month }).groupBy { Triple(it.year, it.month, it.categoryId) }.values.toList()
            items(summaries, key = { "summary:${it.first().id}" }) { group -> SummaryTile(group, state) }
        }
        if (rows.isEmpty() && (trash || s.summaries.isEmpty())) item { EmptyState(if (trash) "Trash is empty" else "No records yet", if (trash) "Closed-month originals appear here after monthly summaries are created." else "Your income, expenses and transfers will appear here.") }
        items(rows, key = { it.id }) {
            Column { TransactionTile(it, s.categories); UndoRecordButton(state, controller, "TRANSACTION", it.id) }
        }
        if (!trash && state.legacy.isNotEmpty()) {
            item { SectionTitle("Private loan records") }
            items(state.legacy.sortedByDescending { it.occurredAt }, key = { "private:${it.id}" }) { movement -> WhiteCard {
                Text("${movement.kind.label} · ${movement.person}", fontWeight = FontWeight.SemiBold)
                Text("${Money.format(movement.amount)} · ${movement.account.label} · ${displayDate(movement.occurredAt)}", color = Muted)
                if (movement.note.isNotBlank()) Text(movement.note)
                UndoRecordButton(state, controller, "PRIVATE_MOVEMENT", movement.id)
            } }
        }
    }
}

@Composable
internal fun UndoRecordButton(state: LedgerState, controller: LedgerController, type: String, id: String) {
    var confirm by rememberSaveable(id) { mutableStateOf(false) }
    val pending = state.queue.find { it.entityType == type && it.entityId == id && it.action == "UNDO" }
    TextButton(onClick = { confirm = true }, enabled = !state.busy && pending == null) {
        Text(if (pending != null) "Undo waiting to sync" else "Undo transaction")
    }
    pending?.lastError?.let { Text(it, color = Red, style = MaterialTheme.typography.bodySmall) }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Undo this transaction?") },
        text = { Text("Reverse this record's effect on balances after sync. Undo is blocked if it would leave insufficient funds or invalidate later repayments.") },
        confirmButton = { TextButton(onClick = { confirm = false; controller.undo(type, id) }) { Text("Undo transaction") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Keep record") } })
}
@Composable
private fun SummaryTile(group: List<MonthlySummaryEntity>, state: LedgerState) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val summary = group.first()
    val category = state.snapshot.categories.find { it.id == summary.categoryId }
    WhiteCard {
        Text("${category?.icon ?: "📦"}  ${category?.name ?: "Other"}", fontWeight = FontWeight.SemiBold)
        Text("${YearMonth.of(summary.year, summary.month).format(DateTimeFormatter.ofPattern("MMMM yyyy"))} · ${group.sumOf { it.transactionCount }} records", style = MaterialTheme.typography.bodySmall, color = Muted)
        Text("Income ${Money.format(group.sumOf { it.incomeMinor })} · Spent ${Money.format(group.sumOf { it.expenseMinor })}", style = MaterialTheme.typography.bodyMedium)
        val ids = group.map { it.id }.toSet()
        val details = state.snapshot.transactions.filter { it.summaryId in ids }
        if (details.isEmpty()) Text("Original details deleted", style = MaterialTheme.typography.bodySmall, color = Muted)
        else {
            TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) { Text(if (expanded) "Hide originals" else "View originals in Trash") }
            if (expanded) details.forEach { TransactionTile(it, state.snapshot.categories) }
        }
    }
}
@Composable
internal fun TransactionTile(t: TransactionEntity, categories: List<CategoryEntity>) {
    val category = categories.find { it.id == t.categoryId }
    WhiteCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 10.dp)) {
                Text(if (t.type == "TRANSFER") "Account transfer" else "${category?.icon ?: "📦"}  ${category?.name ?: statusLabel(t.type)}", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyLarge)
                Text("${accountName(t.account)}${t.destination?.let { " → ${accountName(it)}" }.orEmpty()} · ${displayDate(t.occurredAt)}", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            Text("${when (t.type) { "INCOME" -> "+"; "EXPENSE" -> "−"; else -> "" }}${Money.format(t.amountMinor)}", color = when (t.type) { "INCOME" -> Green; "EXPENSE" -> Red; else -> Ink }, fontWeight = FontWeight.SemiBold)
        }
        if (t.description.isNotBlank()) Text(t.description, style = MaterialTheme.typography.bodySmall, color = Muted)
        if (t.syncStatus != "SYNCED") Text("Saved locally · awaiting sync", color = Muted, style = MaterialTheme.typography.labelSmall)
        t.deleteAfter?.let { Text("Deletes ${displayDate(it)}${if (t.syncStatus != "SYNCED") " after backup is confirmed" else ""}", color = Muted, style = MaterialTheme.typography.bodySmall) }
    }
}
