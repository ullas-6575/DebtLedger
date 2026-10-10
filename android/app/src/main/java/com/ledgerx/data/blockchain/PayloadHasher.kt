package com.ledgerx.data.blockchain

import com.ledgerx.data.entities.LoanEntity
import kotlinx.serialization.json.*
import org.bouncycastle.crypto.digests.KeccakDigest
import java.security.SecureRandom

object PayloadHasher {
    const val CHAIN_ID=80002L
    fun newSalt(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
    fun keccak(bytes: ByteArray): String {
        val digest=KeccakDigest(256); digest.update(bytes,0,bytes.size)
        return ByteArray(32).also { digest.doFinal(it,0) }.joinToString("") { "%02x".format(it) }
    }
    /** Fixed field order, UTF-8, integer amounts, explicit format/chain/contract domain. */
    fun canonical(loan: LoanEntity,contract: String,salt: String): String {
        require(loan.acceptedAt!=null && loan.status in setOf("ACTIVE","PARTIALLY_REPAID","REPAID")) { "Both parties must approve before creating a proof." }
        require(Regex("0x[0-9a-fA-F]{40}").matches(contract)) { "Configure an Amoy registry contract." }
        require(Regex("[0-9a-f]{64}").matches(salt))
        return buildJsonObject {
            put("format","ledgerx.shared-loan.v1"); put("chain_id",CHAIN_ID); put("contract",contract.lowercase())
            put("loan_id",loan.id); put("lender_id",loan.lenderId); put("borrower_id",loan.borrowerId)
            put("principal_minor",loan.principalMinor); put("currency",loan.currency)
            put("description",loan.description); put("accepted_at",loan.acceptedAt)
            put("supersedes",loan.supersedes?.let(::JsonPrimitive)?:JsonNull); put("salt",salt)
        }.toString()
    }
    fun hash(loan: LoanEntity,contract: String,salt: String)="0x"+keccak(canonical(loan,contract,salt).toByteArray(Charsets.UTF_8))
}

/** Repayments change operational versions, not the mutually accepted principal agreement. */
fun sameAgreement(a: LoanEntity,b: LoanEntity): Boolean = a.id==b.id && a.lenderId==b.lenderId &&
    a.borrowerId==b.borrowerId && a.principalMinor==b.principalMinor && a.currency==b.currency &&
    a.description==b.description && a.acceptedAt==b.acceptedAt && a.supersedes==b.supersedes
