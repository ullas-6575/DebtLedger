package com.ledgerx.data.blockchain

import android.content.Context
import androidx.room.withTransaction
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.ledgerx.BuildConfig
import com.ledgerx.data.LedgerDatabase
import com.ledgerx.data.entities.LoanEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProofRepository(private val context: Context,private val owner: String) {
    companion object { val configured get()=BuildConfig.AMOY_REGISTRY.isNotBlank() && BuildConfig.AMOY_RPC_URL.isNotBlank() }
    private val db get()=LedgerDatabase.create(context,owner)
    private fun anchor()=BlockchainAnchor(BuildConfig.AMOY_REGISTRY,BuildConfig.AMOY_RPC_URL)
    suspend fun prepare(loan: LoanEntity,wallet: String): String = withContext(Dispatchers.IO) {
        require(owner in setOf(loan.lenderId,loan.borrowerId))
        val key=MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        val prefs=EncryptedSharedPreferences.create(context,"ledger_proof_salts",key,EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        val saltKey="$owner:${loan.id}"
        val salt=prefs.getString(saltKey,null)?:PayloadHasher.newSalt().also { check(prefs.edit().putString(saltKey,it).commit()) }
        val uri=anchor().proposalUri(loan,salt,wallet)
        val hash=PayloadHasher.hash(loan,BuildConfig.AMOY_REGISTRY,salt)
        updateProof(loan,hash,null,"WAITING")
        uri
    }
    suspend fun verify(loan: LoanEntity,tx: String): Boolean {
        val hash=checkNotNull(loan.blockchainHash) { "Prepare this agreement's hash first." }
        updateProof(loan,hash,tx,"SUBMITTED")
        return try {
            val confirmed=anchor().verifyConfirmation(hash,tx)
            updateProof(loan,hash,tx,if(confirmed) "CONFIRMED" else "SUBMITTED")
            confirmed
        } catch(e: CancellationException) { throw e }
        catch(e: Exception) { updateProof(loan,hash,tx,"FAILED"); throw e }
    }
    private suspend fun updateProof(loan: LoanEntity,hash: String,tx: String?,status: String) = db.withTransaction {
        val current=checkNotNull(db.loanDao().all().find { it.id==loan.id })
        check(sameAgreement(current,loan)) { "The agreement changed. Prepare a new proof." }
        db.loanDao().put(current.copy(blockchainHash=hash,blockchainTxHash=tx,blockchainStatus=status))
    }
    suspend fun retryPending() {
        if(!configured) return
        db.loanDao().all().filter { it.blockchainStatus in setOf("SUBMITTED","FAILED") && it.blockchainHash!=null && it.blockchainTxHash!=null }.forEach { l ->
            try { verify(l,checkNotNull(l.blockchainTxHash)) } catch(e: CancellationException) { throw e } catch(_: Exception) { /* Independent from financial synchronization. */ }
        }
    }
}
