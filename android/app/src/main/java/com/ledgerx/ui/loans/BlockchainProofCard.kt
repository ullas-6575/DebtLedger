package com.ledgerx.ui.loans

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.ledgerx.data.blockchain.ProofRepository
import com.ledgerx.data.entities.LoanEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun BlockchainProofCard(loan: LoanEntity,owner: String) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var wallet by remember(loan.id) { mutableStateOf("") }
    var tx by remember(loan.id) { mutableStateOf(loan.blockchainTxHash.orEmpty()) }
    var message by remember(loan.id) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Text("Blockchain proof · ${loan.blockchainStatus.replace('_',' ')}")
    if(!ProofRepository.configured) {
        Text("Optional. An Amoy registry and wallet setup are needed to enable proofs.")
        return
    }
    if(loan.acceptedAt==null || loan.status !in setOf("ACTIVE","PARTIALLY_REPAID","REPAID")) return
    Text("Only a salted hash is published. Wallet approval is separate from the loan approval above.")
    OutlinedTextField(value=wallet,onValueChange={wallet=it},label={Text("Other party's wallet address")})
    TextButton(enabled=!busy,onClick={scope.launch {
        busy=true
        try {
            val uri=ProofRepository(context,owner).prepare(loan,wallet.trim())
            context.startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(uri)))
            message="Complete the proposal in your wallet, then have the other wallet approve the registry agreement."
        } catch(e: CancellationException) { throw e }
        catch(_: Exception) { message="Could not open a compatible wallet. Check the address and wallet setup." }
        finally { busy=false }
    }}) { Text("Propose hash in wallet") }
    loan.blockchainHash?.let { Text("Payload hash: $it") }
    OutlinedTextField(value=tx,onValueChange={tx=it},label={Text("Confirmation transaction hash")})
    TextButton(enabled=!busy && loan.blockchainHash!=null,onClick={scope.launch {
        busy=true
        try { message=if(ProofRepository(context,owner).verify(loan,tx.trim())) "Confirmed on Amoy." else "Confirmation not verified yet. Background checks will retry." }
        catch(e: CancellationException) { throw e }
        catch(_: Exception) { message="Could not verify the proof. Check the transaction and network." }
        finally { busy=false }
    }}) { Text("Verify proof") }
    message?.let { Text(it) }
}
