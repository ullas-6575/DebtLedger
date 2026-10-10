package com.ledgerx.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ledgerx.data.entities.LoanEntity
import com.ledgerx.domain.Account
import com.ledgerx.domain.Money
import com.ledgerx.ui.theme.*
import java.time.Instant
import java.time.format.DateTimeFormatter
import com.ledgerx.data.repository.LedgerZone

@Composable
private fun SheetContent(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = Muted) }
        content()
    }
}
@Composable
private fun FormMessage(state: LedgerState) {
    state.message?.takeUnless { it == "Saved on this device." }?.let { Text(it, color = Red) }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun Choice(label: String, value: String, options: List<Pair<String, String>>, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { if (options.isNotEmpty()) open = !open }) {
        OutlinedTextField(value = options.find { it.first == value }?.second.orEmpty(), onValueChange = {}, readOnly = true,
            label = { Text(label) }, placeholder = { Text(if (options.isEmpty()) "None available" else "Choose") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) }, modifier = Modifier.menuAnchor().fillMaxWidth(), singleLine = true, shape = MaterialTheme.shapes.medium)
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { onChange(id); open = false }) }
        }
    }
}
@Composable
private fun AmountInput(value: String, onChange: (String) -> Unit, label: String = "Amount") {
    OutlinedTextField(value, onChange, label = { Text(label) }, prefix = { Text("৳ ") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), textStyle = MaterialTheme.typography.headlineSmall, shape = MaterialTheme.shapes.medium)
}
@Composable
internal fun AddPanel(state: LedgerState, controller: LedgerController, dismiss: () -> Unit) {
    var type by rememberSaveable { mutableStateOf("EXPENSE") }
    var amount by rememberSaveable { mutableStateOf("") }
    var account by rememberSaveable { mutableStateOf("CASH") }
    var destination by rememberSaveable { mutableStateOf("BKASH") }
    var category by rememberSaveable { mutableStateOf(state.snapshot.categories.firstOrNull()?.id.orEmpty()) }
    var friend by rememberSaveable { mutableStateOf("") }
    var repaymentLoan by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val accounts = Account.entries.map { it.name to it.label }
    SheetContent("Add record", "Keep your daily money in order.") {
        Choice("Record type", type, listOf("EXPENSE" to "Expense", "INCOME" to "Income", "TRANSFER" to "Transfer", "LEND" to "Give loan", "BORROW" to "Borrow / request loan", "REPAYMENT" to "Loan repayment")) { type = it; error = null; controller.clearMessage() }
        AmountInput(amount, { amount = it })
        if (type == "REPAYMENT") {
            Choice("Loan", repaymentLoan, state.snapshot.loans.filter { it.status in setOf("ACTIVE", "PARTIALLY_REPAID") }.map {
                it.id to "${personName(state, otherPerson(it, state.owner))} · ${Money.format(it.principalMinor - it.repaidMinor)}"
            }) { repaymentLoan = it }
            Text("Repayments use the original loan accounts and require the other person's approval.", color = Muted, style = MaterialTheme.typography.bodySmall)
        } else Choice(if (type == "TRANSFER") "From account" else "Account", account, accounts) { account = it }
        if (type == "TRANSFER") Choice("To account", destination, accounts.filter { it.first != account }) { destination = it }
        if (type in setOf("INCOME", "EXPENSE")) Choice("Category", category, state.snapshot.categories.sortedBy { it.sortOrder }.map { it.id to "${it.icon} ${it.name}" }) { category = it }
        if (type in setOf("LEND", "BORROW")) {
            val friends = state.snapshot.friendships.filter { it.status == "ACCEPTED" }.map { if (it.userId == state.owner) it.friendId else it.userId }
            Choice("Friend", friend, friends.map { it to personName(state, it) }) { friend = it }
            Text(if (friends.isEmpty()) "Add a friend from Loans → Friends first." else "The other person approves before balances change.", color = Muted, style = MaterialTheme.typography.bodySmall)
        }
        if (type != "REPAYMENT") OutlinedTextField(note, { note = it }, label = { Text("Note (optional)") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)
        error?.let { Text(it, color = Red) }; FormMessage(state)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = dismiss, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("Cancel") }
            Button(enabled = !state.busy, modifier = Modifier.weight(1f), onClick = {
                try {
                    error = null
                    val minor = Money.parse(amount)
                    require(note.length <= 300) { "Keep the note within 300 characters." }
                    when (type) {
                        "LEND", "BORROW" -> { require(friend.isNotEmpty()) { "Choose an accepted friend." }; controller.loan(friend, minor, type == "BORROW", note, account, dismiss) }
                        "REPAYMENT" -> {
                            val loan = state.snapshot.loans.find { it.id == repaymentLoan } ?: throw IllegalArgumentException("Choose an active loan.")
                            controller.event(loan, minor, false, dismiss)
                        }
                        else -> {
                            require(type != "TRANSFER" || account != destination) { "Choose two different accounts." }
                            controller.add(type, minor, account, if (type == "TRANSFER") destination else null, if (type == "TRANSFER") null else category, note, dismiss)
                        }
                    }
                } catch (e: IllegalArgumentException) { error = e.message ?: "Check the entered values." }
            }) { Text(if (state.busy) "Saving…" else if (type in setOf("LEND", "BORROW", "REPAYMENT")) "Propose" else "Save record") }
        }
    }
}
@Composable
internal fun SyncPanel(state: LedgerState, controller: LedgerController) {
    SheetContent("Cloud sync", "Your records stay on this device while offline.") {
        WhiteCard {
            Icon(if (state.sync.error != null) Icons.Outlined.CloudOff else Icons.Outlined.CloudDone, null, tint = Green, modifier = Modifier.size(32.dp))
            Text(state.syncLabel(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            state.sync.lastSuccessAt?.let { Text("Last checked ${Instant.ofEpochMilli(it).atZone(LedgerZone).format(DateTimeFormatter.ofPattern("dd MMM, h:mm a"))}", color = Muted, style = MaterialTheme.typography.bodySmall) }
            Text("${state.queue.size} changes waiting to upload", color = Muted)
            state.sync.error?.let { Text(it, color = Red) }
            if (state.sync.running) LinearProgressIndicator(Modifier.fillMaxWidth())
            Button(onClick = controller::retry, enabled = !state.busy && !state.sync.running, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Sync, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (state.queue.any { it.lastError != null }) "Retry sync" else "Sync now")
            }
        }
        state.queue.filter { it.lastError != null }.groupBy { it.entityType to it.lastError }.forEach { (key, rows) -> WhiteCard {
            Text("${statusLabel(key.first)} · ${rows.size} waiting", fontWeight = FontWeight.SemiBold)
            Text(key.second.orEmpty(), color = Red)
        } }
        Text("Connection interruptions retry automatically. If a proposal is no longer valid, open the loan to review or reject it. Retrying does not change an invalid amount.", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
}
@Composable
internal fun ProfilePanel(state: LedgerState, email: String, controller: LedgerController, navigate: (String) -> Unit, signOut: () -> Unit) {
    var confirmImport by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    SheetContent("Your profile") {
        WhiteCard {
            Icon(Icons.Outlined.AccountCircle, null, Modifier.size(40.dp), tint = Green)
            val profile = state.snapshot.profiles.find { it.id == state.owner }
            Text(profile?.displayName ?: "LedgerX member", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            profile?.let { Text("@${it.username}", color = Green) }
            Text(email, color = Muted)
        }
        WhiteCard {
            listOf("friends" to "Friends", "categories" to "Categories", "sync" to "Cloud sync").forEach { (id, title) ->
                TextButton(onClick = { navigate(id) }, modifier = Modifier.fillMaxWidth()) { Text(title, Modifier.weight(1f)); Icon(Icons.Outlined.ChevronRight, null) }
            }
        }
        TextButton(onClick = { confirmImport = true }, enabled = !state.busy) { Text("Import original offline ledger") }
        TextButton(onClick = { confirmSignOut = true }, enabled = !state.busy) { Text("Sign out", color = Red) }
        Text("LedgerX ${com.ledgerx.BuildConfig.VERSION_NAME}", color = Muted, style = MaterialTheme.typography.bodySmall)
        FormMessage(state)
    }
    if (confirmImport) AlertDialog(onDismissRequest = { confirmImport = false }, title = { Text("Import older records?") }, text = { Text("Copy this device's original offline ledger into the signed-in account. Existing record IDs are preserved to avoid duplicates.") }, confirmButton = { TextButton(onClick = { confirmImport = false; controller.importOffline() }) { Text("Import") } }, dismissButton = { TextButton(onClick = { confirmImport = false }) { Text("Cancel") } })
    if (confirmSignOut) AlertDialog(onDismissRequest = { confirmSignOut = false }, title = { Text("Sign out?") }, text = { Text(if (state.queue.isNotEmpty()) "${state.queue.size} changes remain on this device. They will sync when you sign back into this account." else "Your ledger remains saved for your next sign-in.") }, confirmButton = { TextButton(onClick = signOut) { Text("Sign out") } }, dismissButton = { TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") } })
}
@Composable
internal fun FriendsPanel(state: LedgerState, controller: LedgerController) {
    var username by rememberSaveable { mutableStateOf("") }
    SheetContent("Friends", "Use their exact username to connect and share loans.") {
        OutlinedTextField(username, { username = it }, label = { Text("Username") }, prefix = { Text("@") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(onClick = { controller.search(username) }, enabled = username.isNotBlank() && !state.busy, modifier = Modifier.fillMaxWidth()) { Text("Find friend") }
        FormMessage(state)
        state.search.forEach { person -> WhiteCard {
            Text(person.displayName, fontWeight = FontWeight.SemiBold); Text("@${person.username}", color = Muted)
            val existing = state.snapshot.friendships.any { setOf(it.userId, it.friendId) == setOf(state.owner, person.id) }
            TextButton(onClick = { controller.requestFriend(person.id) }, enabled = !state.busy && !existing) { Text(if (existing) "Already connected or requested" else "Send request") }
        } }
        SectionTitle("Your connections")
        if (state.snapshot.friendships.isEmpty()) EmptyState("Start with a friend", "Find someone by username, send a request and wait for them to accept.", Icons.Outlined.People)
        state.snapshot.friendships.forEach { f -> WhiteCard {
            Text(personName(state, if (f.userId == state.owner) f.friendId else f.userId), fontWeight = FontWeight.SemiBold)
            Text(if (state.decisionQueued(f.id)) "Decision waiting to sync" else statusLabel(f.status), color = Muted)
            if (f.friendId == state.owner && f.status == "PENDING") DecisionButtons(state.busy || state.decisionQueued(f.id), { controller.decideFriend(f.id, true) }, { controller.decideFriend(f.id, false) })
        } }
    }
}
@Composable
internal fun CategoriesPanel(state: LedgerState, controller: LedgerController) {
    var name by rememberSaveable { mutableStateOf("") }
    var icon by rememberSaveable { mutableStateOf("📦") }
    SheetContent("Categories", "Organize spending and monthly totals your way.") {
        WhiteCard { state.snapshot.categories.sortedBy { it.sortOrder }.forEach { Text("${it.icon}  ${it.name}") } }
        SectionTitle("New category")
        OutlinedTextField(name, { name = it }, label = { Text("Category name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(icon, { icon = it }, label = { Text("Icon / emoji") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        FormMessage(state)
        Button(onClick = { controller.category(name, icon) { name = ""; icon = "📦" } }, enabled = !state.busy && name.isNotBlank() && icon.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Add category") }
    }
}
@Composable
private fun DecisionButtons(busy: Boolean, accept: () -> Unit, reject: () -> Unit, canAccept: Boolean = true) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = accept, enabled = !busy && canAccept, modifier = Modifier.weight(1f)) { Text("Accept") }
        OutlinedButton(onClick = reject, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Reject") }
    }
}
@Composable
internal fun LoanDetail(state: LedgerState, loan: LoanEntity, controller: LedgerController) {
    var account by rememberSaveable(loan.id) { mutableStateOf("CASH") }
    var edit by rememberSaveable(loan.id) { mutableStateOf<String?>(null) }
    var amount by rememberSaveable(loan.id) { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var proof by rememberSaveable(loan.id) { mutableStateOf(false) }
    val active = loan.status in setOf("ACTIVE", "PARTIALLY_REPAID")
    val remaining = loan.principalMinor - loan.repaidMinor
    SheetContent(personName(state, otherPerson(loan, state.owner)), if (loan.lenderId == state.owner) "You lent · ${statusLabel(loan.status)}" else "You borrowed · ${statusLabel(loan.status)}") {
        WhiteCard {
            Text("OUTSTANDING", color = Muted, style = MaterialTheme.typography.labelMedium)
            Text(Money.format(remaining), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = Green)
            Row { Text("Principal", Modifier.weight(1f), color = Muted); Text(Money.format(loan.principalMinor)) }
            Row { Text("Repaid", Modifier.weight(1f), color = Muted); Text(Money.format(loan.repaidMinor)) }
            Text("Created ${displayDate(loan.createdAt)}", color = Muted, style = MaterialTheme.typography.bodySmall)
            if (loan.description.isNotBlank()) Text(loan.description)
        }
        FormMessage(state)
        val queued = state.queue.find { it.entityId == loan.id && it.action in setOf("ACCEPT", "REJECT") }
        queued?.let { Text(it.lastError ?: "Your decision is waiting to sync.", color = if (it.lastError == null) Muted else Red) }
        if (loan.status == "UNDER_REVIEW" && loan.proposedBy != state.owner) {
            Choice("Your account for this loan", account, Account.entries.map { it.name to it.label }) { account = it }
            DecisionButtons(state.busy || (queued != null && queued.lastError == null), { controller.decideLoan(loan.id, true, account) }, { controller.decideLoan(loan.id, false, account) })
        }
        if (active) {
            if (edit == null) {
                Button(onClick = { edit = "REPAYMENT"; amount = ""; error = null }, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) { Text("Propose repayment") }
                TextButton(onClick = { edit = "CORRECTION"; amount = ""; error = null }, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) { Text("Correct principal") }
            } else WhiteCard {
                Text(if (edit == "CORRECTION") "Correct principal" else "Propose repayment", fontWeight = FontWeight.Bold)
                Text(if (edit == "CORRECTION") "Enter the corrected total, not the difference. Both people must approve." else "Available to repay: ${Money.format(remaining)}. The other person must approve.", color = Muted, style = MaterialTheme.typography.bodySmall)
                AmountInput(amount, { amount = it }, if (edit == "CORRECTION") "New principal" else "Repayment amount")
                error?.let { Text(it, color = Red) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { edit = null }, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    Button(onClick = {
                        try { error = null; controller.event(loan, Money.parse(amount), edit == "CORRECTION") { edit = null; amount = "" } }
                        catch (e: IllegalArgumentException) { error = e.message }
                    }, enabled = !state.busy, modifier = Modifier.weight(1f)) { Text("Propose") }
                }
            }
        }
        val events = state.snapshot.events.filter { it.loanId == loan.id }.sortedByDescending { it.createdAt }
        if (events.isNotEmpty()) SectionTitle("Proposals & activity")
        events.forEach { event -> WhiteCard {
            val decision = state.queue.find { it.entityId == event.id && it.action in setOf("ACCEPT", "REJECT") }
            Text("${statusLabel(event.type)}${event.amountMinor?.let { " · ${Money.format(it)}" }.orEmpty()}", fontWeight = FontWeight.SemiBold)
            Text("${displayDate(event.createdAt)} · ${if (decision != null) "Decision waiting to sync" else statusLabel(event.status)}", style = MaterialTheme.typography.bodySmall, color = Muted)
            if (event.status == "PENDING" && event.proposedBy != state.owner && active) {
                val valid = when (event.type) { "REPAYMENT" -> (event.amountMinor ?: 0) in 1..remaining; "CORRECTION" -> (event.amountMinor ?: 0) >= loan.repaidMinor; else -> true }
                if (!valid) Text(if (event.type == "REPAYMENT") "This exceeds the remaining balance. Reject it and request the correct amount." else "This principal is below confirmed repayments. Reject it and request a correction.", color = Red)
                decision?.lastError?.let { Text(it, color = Red, style = MaterialTheme.typography.bodySmall) }
                DecisionButtons(state.busy || (decision != null && decision.lastError == null), { controller.decideEvent(event.id, true) }, { controller.decideEvent(event.id, false) }, valid)
            }
        } }
        TextButton(onClick = { proof = !proof }, modifier = Modifier.fillMaxWidth()) { Text(if (proof) "Hide blockchain proof" else "Blockchain proof (optional)") }
        if (proof) com.ledgerx.ui.loans.BlockchainProofCard(loan, state.owner)
    }
}
