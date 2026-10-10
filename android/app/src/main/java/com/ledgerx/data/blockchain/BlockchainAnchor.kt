package com.ledgerx.data.blockchain

import com.ledgerx.data.entities.LoanEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.net.URI
import java.net.HttpURLConnection

/** Wallets sign externally. No private keys, privileged RPC keys or money transfers in the app. */
class BlockchainAnchor(private val contract: String,private val rpcUrl: String) {
    init {
        require(Regex("0x[0-9a-fA-F]{40}").matches(contract))
        require(URI(rpcUrl).scheme=="https")
    }
    fun proposalUri(loan: LoanEntity,salt: String,counterpartyWallet: String,supersedes: Long=0): String {
        require(Regex("0x[0-9a-fA-F]{40}").matches(counterpartyWallet))
        require(supersedes>=0)
        val hash=PayloadHasher.hash(loan,contract,salt)
        return "ethereum:$contract@${PayloadHasher.CHAIN_ID}/propose?bytes32=$hash&address=$counterpartyWallet&uint256=$supersedes"
    }
    fun approvalUri(agreementId: Long): String {
        require(agreementId>0)
        return "ethereum:$contract@${PayloadHasher.CHAIN_ID}/approve?uint256=$agreementId"
    }
    /** Only a successful Confirmed log from the configured registry counts as a proof. */
    suspend fun verifyConfirmation(hash: String,transactionHash: String): Boolean = withContext(Dispatchers.IO) {
        require(Regex("0x[0-9a-fA-F]{64}").matches(transactionHash))
        require(Regex("0x[0-9a-fA-F]{64}").matches(hash))
        if(rpc("eth_chainId",JsonArray(emptyList())).jsonPrimitive.content.removePrefix("0x").toLong(16)!=PayloadHasher.CHAIN_ID) return@withContext false
        val receipt=rpc("eth_getTransactionReceipt",buildJsonArray { add(transactionHash) })
        if(receipt is JsonNull) return@withContext false
        val r=receipt.jsonObject
        if(r["status"]?.jsonPrimitive?.content!="0x1" || !r["to"]?.jsonPrimitive?.content.equals(contract,true)) return@withContext false
        val block=r.getValue("blockNumber").jsonPrimitive.content.removePrefix("0x").toLong(16)
        val latest=rpc("eth_blockNumber",JsonArray(emptyList())).jsonPrimitive.content.removePrefix("0x").toLong(16)
        if(latest-block<12) return@withContext false
        val topic="0x"+PayloadHasher.keccak("Confirmed(uint256,bytes32)".toByteArray())
        r.getValue("logs").jsonArray.any { log ->
            val entry=log.jsonObject
            val topics=entry["topics"]?.jsonArray?:JsonArray(emptyList())
            entry["address"]?.jsonPrimitive?.content.equals(contract,true) && topics.size==3 &&
                topics[0].jsonPrimitive.content.equals(topic,true) && topics[2].jsonPrimitive.content.equals(hash,true)
        }
    }
    private fun rpc(method: String,params: JsonArray): JsonElement {
        val connection=URI(rpcUrl).toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod="POST"; connection.connectTimeout=15000; connection.readTimeout=15000
            connection.doOutput=true; connection.setRequestProperty("Content-Type","application/json")
            val body=buildJsonObject { put("jsonrpc","2.0"); put("id",1); put("method",method); put("params",params) }
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            check(connection.responseCode==200) { "Proof network unavailable." }
            val response=Json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() }).jsonObject
            check(response["error"]==null) { "Proof network rejected the request." }
            return response.getValue("result")
        } finally { connection.disconnect() }
    }
}
