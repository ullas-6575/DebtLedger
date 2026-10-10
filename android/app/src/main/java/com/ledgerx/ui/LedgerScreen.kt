package com.ledgerx.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ledgerx.data.entities.*
import com.ledgerx.data.repository.*
import com.ledgerx.domain.*
import java.time.Instant
import java.time.YearMonth
import java.time.format.DateTimeFormatter

private fun displayDate(time: Long)=Instant.ofEpochMilli(time).atZone(LedgerZone).format(DateTimeFormatter.ofPattern("dd MMM yyyy"))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LedgerScreen(vm: LedgerViewModel) {
    val state by vm.ledger.state.collectAsStateWithLifecycle()
    val auth by vm.auth.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var add by rememberSaveable { mutableStateOf(false) }
    val tabs=listOf("Home","Ledger","Loans","Profile")
    val icons=listOf(Icons.Outlined.Home,Icons.AutoMirrored.Outlined.List,Icons.Outlined.People,Icons.Outlined.Person)
    val snapshot=state.snapshot
    Scaffold(topBar={ TopAppBar(title={ Text("LedgerX") },actions={
        TextButton(onClick=vm.ledger::sync,enabled=!state.busy) { Text(if(state.busy) "Working…" else "Sync") }
    }) },bottomBar={ NavigationBar {
        tabs.forEachIndexed { i,label ->
            if(i==2) NavigationBarItem(selected=false,onClick={add=true},icon={Icon(Icons.Outlined.Add,"Add")},label={Text("Add")},enabled=state.ready)
            NavigationBarItem(selected=tab==i,onClick={tab=i},icon={Icon(icons[i],null)},label={Text(label)})
        }
    } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(tabs[tab],style=MaterialTheme.typography.headlineMedium)
            state.message?.let { Text(it,color=MaterialTheme.colorScheme.primary) }
            if(!state.ready) CircularProgressIndicator()
            else {
                val queued=state.queue.size
                if(queued>0) Text("$queued changes waiting to sync${if(state.queue.any { it.attempts>=3 }) " · Some changes need a retry" else ""}")
                when(tab) {
                    0 -> HomePanel(state)
                    1 -> HistoryPanel(state)
                    2 -> LoansPanel(state,vm.ledger)
                    3 -> ProfilePanel(state,auth.email.orEmpty(),vm.ledger,vm::signOut)
                }
            }
        }
    }
    if(add) ModalBottomSheet(onDismissRequest={add=false}) {
        AddPanel(state,vm.ledger) { add=false }
    }
}

@Composable
private fun Tile(title:String,value:String) { Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text(title); Text(value,style=MaterialTheme.typography.titleLarge) } } }
@Composable
private fun HomePanel(state:LedgerState) {
    val s=state.snapshot
    val accounts=balances(s,state.legacy,state.owner)
    Tile("Total balance",Money.format(accounts.values.sum()))
    accounts.forEach { (a,v) -> Text("${Account.valueOf(a).label}: ${Money.format(v)}") }
    val month=YearMonth.now(LedgerZone)
    val spending=s.transactions.filter { it.type=="EXPENSE" && YearMonth.from(Instant.ofEpochMilli(it.occurredAt).atZone(LedgerZone))==month }.sumOf { it.amountMinor }
    Tile("Spending this month",Money.format(spending))
    val legacyTotals=Ledger.summarize(state.legacy)
    val active=s.loans.filter { it.status in setOf("ACTIVE","PARTIALLY_REPAID") }
    Tile("People owe me",Money.format(active.filter { it.lenderId==state.owner }.sumOf { it.principalMinor-it.repaidMinor }+legacyTotals.owedToMe))
    Tile("I owe",Money.format(active.filter { it.borrowerId==state.owner }.sumOf { it.principalMinor-it.repaidMinor }+legacyTotals.iOwe))
    val approvals=s.loans.count { it.status=="UNDER_REVIEW" && it.proposedBy!=state.owner }+s.events.count { it.status=="PENDING" && it.proposedBy!=state.owner }
    Tile("Pending approvals",approvals.toString())
    if(state.legacy.isNotEmpty()) Text("Your older private loans are preserved under Loans.")
}

@Composable
private fun HistoryPanel(state:LedgerState) {
    var trash by rememberSaveable { mutableStateOf(false) }
    Row { FilterChip(selected=!trash,onClick={trash=false},label={Text("Ledger")}); Spacer(Modifier.width(8.dp)); FilterChip(selected=trash,onClick={trash=true},label={Text("Trash")}) }
    val s=state.snapshot
    if(trash) Text("Original details are permanently deleted 30 days after entering Trash. Monthly totals stay in your ledger.")
    if(!trash) s.summaries.sortedWith(compareByDescending<MonthlySummaryEntity> { it.year }.thenByDescending { it.month }).groupBy { Triple(it.year,it.month,it.categoryId) }.values.forEach { group ->
        val summary=group.first()
        var expanded by remember(summary.id) { mutableStateOf(false) }
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
            Text("${summary.year}-${summary.month.toString().padStart(2,'0')} · ${s.categories.find { it.id==summary.categoryId }?.name?:"Other"}")
            Text("Income ${Money.format(group.sumOf { it.incomeMinor })} · Expense ${Money.format(group.sumOf { it.expenseMinor })}")
            Text("${group.sumOf { it.transactionCount }} transactions")
            val summaryIds=group.map { it.id }.toSet()
            val details=s.transactions.filter { it.summaryId in summaryIds }
            if(details.isNotEmpty()) {
                TextButton(onClick={expanded=!expanded}) { Text(if(expanded) "Hide details" else "Show details in Trash") }
                if(expanded) details.forEach { TransactionTile(it,s.categories) }
            } else Text("Original details deleted")
        } }
    }
    val rows=s.transactions.filter { (it.summaryStatus=="TRASHED")==trash }.sortedByDescending { it.occurredAt }
    if(rows.isEmpty()) Text(if(trash) "Trash is empty." else "No individual transactions to show.")
    rows.forEach { TransactionTile(it,s.categories) }
}
@Composable
private fun TransactionTile(t:TransactionEntity,categories:List<CategoryEntity>) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
        Text("${t.type} · ${Money.format(t.amountMinor)}")
        Text("${displayDate(t.occurredAt)} · ${t.account} ${t.destination?.let { "→ $it" }.orEmpty()}")
        categories.find { it.id==t.categoryId }?.let { Text("${it.icon} ${it.name}") }
        if(t.description.isNotBlank()) Text(t.description)
        Text(t.syncStatus)
        t.deleteAfter?.let { Text("Deletes ${displayDate(it)}${if(t.syncStatus!="SYNCED") " after cloud backup is confirmed" else ""}") }
    } }
}

@Composable
private fun LoansPanel(state:LedgerState,controller:LedgerController) {
    val s=state.snapshot
    var account by rememberSaveable { mutableStateOf("CASH") }
    Choice("Account for accepting a loan",account,Account.entries.map { it.name to it.label }) { account=it }
    if(s.loans.isEmpty()) Text("Add a friend from Profile, then use Add to give or request a loan.")
    s.loans.sortedByDescending { it.createdAt }.forEach { loan ->
        var amount by remember(loan.id) { mutableStateOf("") }
        var formError by remember(loan.id) { mutableStateOf<String?>(null) }
        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            val friend=if(loan.lenderId==state.owner) loan.borrowerId else loan.lenderId
            Text(s.profiles.find { it.id==friend }?.displayName?:friend)
            Text("${if(loan.lenderId==state.owner) "Lent" else "Borrowed"} ${Money.format(loan.principalMinor)} · ${loan.status.replace('_',' ')}")
            Text("Repaid ${Money.format(loan.repaidMinor)} · Outstanding ${Money.format(loan.principalMinor-loan.repaidMinor)}")
            if(loan.description.isNotBlank()) Text(loan.description)
            if(loan.status=="UNDER_REVIEW" && loan.proposedBy!=state.owner) DecisionButtons(state.busy,{controller.decideLoan(loan.id,true,account)},{controller.decideLoan(loan.id,false,account)})
            if(loan.status in setOf("ACTIVE","PARTIALLY_REPAID")) {
                OutlinedTextField(value=amount,onValueChange={amount=it},label={Text("Amount (BDT)")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
                Row {
                    TextButton(enabled=!state.busy,onClick={ try { controller.event(loan,Money.parse(amount),false); formError=null } catch(e:IllegalArgumentException) { formError=e.message } }) { Text("Propose repayment") }
                    TextButton(enabled=!state.busy,onClick={ try { controller.event(loan,Money.parse(amount),true); formError=null } catch(e:IllegalArgumentException) { formError=e.message } }) { Text("Correct principal") }
                }
                formError?.let { Text(it,color=MaterialTheme.colorScheme.error) }
            }
            s.events.filter { it.loanId==loan.id }.forEach { event ->
                Text("${event.type}: ${event.amountMinor?.let(Money::format).orEmpty()} · ${event.status}")
                if(event.status=="PENDING" && event.proposedBy!=state.owner) DecisionButtons(state.busy,{controller.decideEvent(event.id,true)},{controller.decideEvent(event.id,false)})
            }
            com.ledgerx.ui.loans.BlockchainProofCard(loan,state.owner)
            loan.blockchainTxHash?.let { Text(it) }
        } }
    }
    if(state.legacy.isNotEmpty()) {
        Text("Older private loans",style=MaterialTheme.typography.titleLarge)
        Text("These name-based records have no shared approval. They remain private and keep their original account effects.")
        Ledger.summarize(state.legacy).people.forEach { (name,balance) -> Tile(name,Money.format(balance)) }
    }
}
@Composable
private fun DecisionButtons(busy:Boolean,accept:()->Unit,reject:()->Unit) { Row {
    TextButton(enabled=!busy,onClick=accept) { Text("Accept") }
    TextButton(enabled=!busy,onClick=reject) { Text("Reject") }
} }

@Composable
private fun ProfilePanel(state:LedgerState,email:String,controller:LedgerController,signOut:()->Unit) {
    Text(email)
    state.snapshot.profiles.find { it.id==state.owner }?.let { Text("${it.displayName} · @${it.username}") }
    Text("Friends",style=MaterialTheme.typography.titleLarge)
    var username by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(value=username,onValueChange={username=it},label={Text("Exact username")})
    Button(onClick={controller.search(username)},enabled=username.isNotBlank()) { Text("Find friend") }
    state.search.forEach { person -> Row { Text("${person.displayName} · @${person.username}",Modifier.weight(1f)); TextButton(onClick={controller.requestFriend(person.id)},enabled=!state.busy) { Text("Request") } } }
    state.snapshot.friendships.forEach { f ->
        val other=if(f.userId==state.owner) f.friendId else f.userId
        Text("${state.snapshot.profiles.find { it.id==other }?.displayName?:other} · ${f.status}")
        if(f.friendId==state.owner && f.status=="PENDING") DecisionButtons(state.busy,{controller.decideFriend(f.id,true)},{controller.decideFriend(f.id,false)})
    }
    Text("Categories",style=MaterialTheme.typography.titleLarge)
    state.snapshot.categories.sortedBy { it.sortOrder }.forEach { Text("${it.icon} ${it.name}") }
    var name by rememberSaveable { mutableStateOf("") }
    var icon by rememberSaveable { mutableStateOf("📦") }
    OutlinedTextField(value=name,onValueChange={name=it},label={Text("New category")})
    OutlinedTextField(value=icon,onValueChange={icon=it},label={Text("Icon")})
    TextButton(onClick={controller.category(name,icon)},enabled=!state.busy && name.isNotBlank()) { Text("Add category") }
    if(state.queue.any { it.attempts>=3 }) {
        Text("Some changes failed after 3 attempts. Check the connection and server setup, then retry.")
        Button(onClick=controller::retry,enabled=!state.busy) { Text("Retry failed changes") }
    }
    TextButton(onClick=controller::importOffline,enabled=!state.busy) { Text("Import original offline ledger into this account") }
    TextButton(onClick=signOut,enabled=!state.busy) { Text("Sign out") }
}

@Composable
private fun Choice(label:String,value:String,options:List<Pair<String,String>>,onChange:(String)->Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick={open=true}) { Text("$label: ${options.find { it.first==value }?.second?:"Choose"}") }
        DropdownMenu(expanded=open,onDismissRequest={open=false}) { options.forEach { (id,name) -> DropdownMenuItem(text={Text(name)},onClick={onChange(id);open=false}) } }
    }
}
@Composable
private fun AddPanel(state:LedgerState,controller:LedgerController,dismiss:()->Unit) {
    var type by rememberSaveable { mutableStateOf("EXPENSE") }
    var amount by rememberSaveable { mutableStateOf("") }
    var account by rememberSaveable { mutableStateOf("CASH") }
    var destination by rememberSaveable { mutableStateOf("BKASH") }
    var category by rememberSaveable { mutableStateOf(state.snapshot.categories.firstOrNull()?.id.orEmpty()) }
    var friend by rememberSaveable { mutableStateOf("") }
    var repaymentLoan by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("Add record",style=MaterialTheme.typography.titleLarge)
        Choice("Type",type,listOf("EXPENSE" to "Expense","INCOME" to "Income","TRANSFER" to "Transfer","LEND" to "Give loan","BORROW" to "Borrow / Loan request","REPAYMENT" to "Repayment")) { type=it }
        if(type=="REPAYMENT") Choice("Loan",repaymentLoan,state.snapshot.loans.filter { it.status in setOf("ACTIVE","PARTIALLY_REPAID") }.map { loan ->
            val other=if(loan.lenderId==state.owner) loan.borrowerId else loan.lenderId
            loan.id to "${state.snapshot.profiles.find { it.id==other }?.displayName?:other} · ${Money.format(loan.principalMinor-loan.repaidMinor)}"
        }) { repaymentLoan=it }
        OutlinedTextField(value=amount,onValueChange={amount=it},label={Text("Amount (BDT)")},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Decimal))
        Choice("Account",account,Account.entries.map { it.name to it.label }) { account=it }
        if(type=="TRANSFER") Choice("Destination",destination,Account.entries.map { it.name to it.label }) { destination=it }
        if(type in setOf("INCOME","EXPENSE")) Choice("Category",category,state.snapshot.categories.sortedBy { it.sortOrder }.map { it.id to "${it.icon} ${it.name}" }) { category=it }
        if(type in setOf("LEND","BORROW")) {
            val friends=state.snapshot.friendships.filter { it.status=="ACCEPTED" }.map { if(it.userId==state.owner) it.friendId else it.userId }
            Choice("Friend",friend,friends.map { id -> id to (state.snapshot.profiles.find { it.id==id }?.displayName?:id) }) { friend=it }
        }
        OutlinedTextField(value=note,onValueChange={note=it},label={Text("Description")})
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        Button(enabled=!state.busy,onClick={
            try {
                val minor=Money.parse(amount)
                require(note.length<=300) { "Description must be at most 300 characters." }
                if(type in setOf("LEND","BORROW")) { require(friend.isNotEmpty()) { "Choose an accepted friend." }; controller.loan(friend,minor,type=="BORROW",note,account) }
                else if(type=="REPAYMENT") {
                    val loan=state.snapshot.loans.find { it.id==repaymentLoan }?:throw IllegalArgumentException("Choose an active loan.")
                    controller.event(loan,minor,false)
                }
                else controller.add(type,minor,account,if(type=="TRANSFER") destination else null,if(type=="TRANSFER") null else category,note)
                dismiss()
            } catch(e:IllegalArgumentException) { error=e.message }
        }) { Text("Save") }
        Spacer(Modifier.height(20.dp))
    }
}
