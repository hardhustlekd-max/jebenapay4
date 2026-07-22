package com.jebenapay.smstracker.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

class TransactionRepository private constructor(context: Context) {
    private val database = AppDatabase.getDatabase(context)
    private val dao = database.transactionDao()

    // Real-time live event bus for instant foreground UI reactive updates
    private val _liveTransactionEvent = MutableSharedFlow<Transaction>(extraBufferCapacity = 64)
    val liveTransactionEvent: SharedFlow<Transaction> = _liveTransactionEvent.asSharedFlow()

    fun getAllTransactions(): Flow<List<Transaction>> {
        return dao.getAllTransactionsFlow()
    }

    suspend fun getAllTransactionsList(): List<Transaction> {
        return dao.getAllTransactionsList()
    }

    suspend fun addTransaction(transaction: Transaction): Long {
        val id = dao.insertTransaction(transaction)
        val created = transaction.copy(id = id)
        // Instantly publish to foreground UI event stream
        _liveTransactionEvent.emit(created)
        return id
    }

    suspend fun clearAll() {
        dao.clearAll()
    }

    companion object {
        @Volatile
        private var INSTANCE: TransactionRepository? = null

        fun getInstance(context: Context): TransactionRepository {
            return INSTANCE ?: synchronized(this) {
                val instance = TransactionRepository(context.applicationContext)
                INSTANCE = instance
                instance
            }
        }
    }
}
