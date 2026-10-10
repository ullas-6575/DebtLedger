package com.ledgerx.data.blockchain

import com.ledgerx.data.entities.LoanEntity
import org.junit.Test
import org.junit.Assert.*

class PayloadHasherTest {
    private val loan=LoanEntity("loan","lender","borrower",5000,"BDT","Tea","ACTIVE","lender","CASH","CASH",0,100,200,null,null,null,"NOT_REQUIRED","SYNCED",1,null)
    private val contract="0x"+"1".repeat(40)
    private val salt="a".repeat(64)
    @Test fun ethereumKeccakVector() { assertEquals("c5d2460186f7233c927e7db2dcc703c0e500b653ca82273b7bfad8045d85a470",PayloadHasher.keccak(byteArrayOf())) }
    @Test fun canonicalStableAndDomainSeparated() {
        val first=PayloadHasher.hash(loan,contract,salt)
        assertEquals(first,PayloadHasher.hash(loan,contract,salt))
        assertNotEquals(first,PayloadHasher.hash(loan,contract,"b".repeat(64)))
        assertNotEquals(first,PayloadHasher.hash(loan,"0x"+"2".repeat(40),salt))
        assertNotEquals(first,PayloadHasher.hash(loan.copy(principalMinor=5001),contract,salt))
    }
    @Test fun repaymentsDoNotChangeAgreementHash() { assertEquals(PayloadHasher.hash(loan,contract,salt),PayloadHasher.hash(loan.copy(version=3,repaidMinor=100,status="PARTIALLY_REPAID"),contract,salt)) }
    @Test(expected=IllegalArgumentException::class) fun neverHashUnapprovedLoan() { PayloadHasher.hash(loan.copy(status="UNDER_REVIEW",acceptedAt=null),contract,salt) }
    @Test fun walletRequestIsAmoyAndHashOnly() {
        val uri=BlockchainAnchor(contract,"https://example.com").proposalUri(loan,salt,"0x"+"2".repeat(40))
        assertTrue(uri.contains("@80002/propose?bytes32=0x"))
        assertFalse(uri.contains("Tea"));assertFalse(uri.contains("lender"))
    }
}
