package com.ledgerx.data.repository

import androidx.room.withTransaction
import com.ledgerx.data.entities.FriendshipEntity
import kotlinx.serialization.encodeToString
import java.util.UUID

class FriendRepository(private val repo: TransactionRepository) {
    suspend fun request(friend: String) = repo.db.withTransaction {
        require(friend!=repo.owner)
        require(repo.db.friendshipDao().all().none { setOf(it.userId,it.friendId)==setOf(repo.owner,friend) }) { "A request already exists." }
        val row=FriendshipEntity(UUID.randomUUID().toString(),repo.owner,friend,"PENDING",System.currentTimeMillis())
        repo.db.friendshipDao().put(row); repo.queue("FRIENDSHIP",row.id,"PROPOSE",LedgerJson.encodeToString(row))
    }
    suspend fun decide(id: String,accept: Boolean) = repo.db.withTransaction { repo.queue("FRIENDSHIP",id,if(accept) "ACCEPT" else "REJECT","{}") }
}
