package com.jebenapay.smstracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    fun getAllTransactionsFlow(): Flow<List<Transaction>>

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    suspend fun getAllTransactionsList(): List<Transaction>

    @Query("SELECT * FROM transactions ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentTransactionsFlow(limit: Int): Flow<List<Transaction>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(transaction: Transaction): Long

    @Query("SELECT * FROM transactions WHERE reference = :ref LIMIT 1")
    suspend fun findExistingByRef(ref: String): Transaction?

    @Query("SELECT * FROM transactions WHERE sender = :sender AND amount = :amount AND timestamp >= :minTime LIMIT 1")
    suspend fun findExistingByMatch(sender: String, amount: Double, minTime: Long): Transaction?

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteTransaction(id: Long)

    @Query("DELETE FROM transactions")
    suspend fun clearAll()
}
