package com.ledgerx.data.migration

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Legacy rows remain intact until the repository imports them transactionally. */
object Migration1to2 : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `transactions` (`id` TEXT NOT NULL PRIMARY KEY, `userId` TEXT NOT NULL, `type` TEXT NOT NULL, `amountMinor` INTEGER NOT NULL, `account` TEXT NOT NULL, `destination` TEXT, `categoryId` TEXT, `description` TEXT NOT NULL, `occurredAt` INTEGER NOT NULL, `syncStatus` TEXT NOT NULL, `summaryStatus` TEXT NOT NULL, `summaryId` TEXT, `trashedAt` INTEGER, `deleteAfter` INTEGER, `version` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `monthly_summaries` (`id` TEXT NOT NULL PRIMARY KEY, `userId` TEXT NOT NULL, `year` INTEGER NOT NULL, `month` INTEGER NOT NULL, `categoryId` TEXT NOT NULL, `account` TEXT NOT NULL, `incomeMinor` INTEGER NOT NULL, `expenseMinor` INTEGER NOT NULL, `transactionCount` INTEGER NOT NULL, `generatedAt` INTEGER NOT NULL, `version` INTEGER NOT NULL)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_monthly_summaries_userId_year_month_categoryId_account ON monthly_summaries(userId,year,month,categoryId,account)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `loans` (`id` TEXT NOT NULL PRIMARY KEY, `lenderId` TEXT NOT NULL, `borrowerId` TEXT NOT NULL, `principalMinor` INTEGER NOT NULL, `currency` TEXT NOT NULL, `description` TEXT NOT NULL, `status` TEXT NOT NULL, `proposedBy` TEXT NOT NULL, `lenderAccount` TEXT NOT NULL, `borrowerAccount` TEXT NOT NULL, `repaidMinor` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, `acceptedAt` INTEGER, `fullyRepaidAt` INTEGER, `blockchainHash` TEXT, `blockchainTxHash` TEXT, `blockchainStatus` TEXT NOT NULL, `syncStatus` TEXT NOT NULL, `version` INTEGER NOT NULL, `supersedes` TEXT)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `loan_events` (`id` TEXT NOT NULL PRIMARY KEY, `loanId` TEXT NOT NULL, `type` TEXT NOT NULL, `amountMinor` INTEGER, `proposedBy` TEXT NOT NULL, `approvedBy` TEXT, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `syncStatus` TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `sync_queue` (`id` TEXT NOT NULL PRIMARY KEY, `entityType` TEXT NOT NULL, `entityId` TEXT NOT NULL, `action` TEXT NOT NULL, `payload` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `attempts` INTEGER NOT NULL, `lastAttemptAt` INTEGER)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `categories` (`id` TEXT NOT NULL PRIMARY KEY, `userId` TEXT NOT NULL, `name` TEXT NOT NULL, `icon` TEXT NOT NULL, `isDefault` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `friendships` (`id` TEXT NOT NULL PRIMARY KEY, `userId` TEXT NOT NULL, `friendId` TEXT NOT NULL, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `profiles_cache` (`id` TEXT NOT NULL PRIMARY KEY, `username` TEXT NOT NULL, `displayName` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS `deletions` (`id` TEXT NOT NULL PRIMARY KEY, `userId` TEXT NOT NULL, `deletedAt` INTEGER NOT NULL)")
    }
}
