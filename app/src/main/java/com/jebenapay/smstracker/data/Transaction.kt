package com.jebenapay.smstracker.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class TransactionType {
    CREDIT,
    DEBIT,
    UNKNOWN
}

@Entity(tableName = "transactions")
data class Transaction(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val sender: String,
    val amount: Double,
    val type: TransactionType,
    val reference: String,
    val merchantOrParty: String,
    val rawSms: String,
    val timestamp: Long = System.currentTimeMillis(),
    val category: String = "General"
)
