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

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteTransaction(id: Long)

    @Query("DELETE FROM transactions")
    suspend fun clearAll()
}
