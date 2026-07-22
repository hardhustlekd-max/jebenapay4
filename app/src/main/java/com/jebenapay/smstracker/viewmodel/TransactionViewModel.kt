package com.jebenapay.smstracker.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jebenapay.smstracker.data.Transaction
import com.jebenapay.smstracker.data.TransactionRepository
import com.jebenapay.smstracker.data.TransactionType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TransactionViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = TransactionRepository.getInstance(application)

    private val _transactions = MutableStateFlow<List<Transaction>>(emptyList())
    val transactions: StateFlow<List<Transaction>> = _transactions.asStateFlow()

    val totalIncome: StateFlow<Double> = _transactions.map { list ->
        list.filter { it.type == TransactionType.CREDIT }.sumOf { it.amount }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0.0)

    val totalExpense: StateFlow<Double> = _transactions.map { list ->
        list.filter { it.type == TransactionType.DEBIT }.sumOf { it.amount }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0.0)

    val netBalance: StateFlow<Double> = _transactions.map { list ->
        val income = list.filter { it.type == TransactionType.CREDIT }.sumOf { it.amount }
        val expense = list.filter { it.type == TransactionType.DEBIT }.sumOf { it.amount }
        income - expense
    }.stateIn(viewModelScope, SharingStarted.Eagerly, 0.0)

    private val _latestCapturedTransaction = MutableStateFlow<Transaction?>(null)
    val latestCapturedTransaction: StateFlow<Transaction?> = _latestCapturedTransaction.asStateFlow()

    init {
        // Collect Room Database Flow
        viewModelScope.launch {
            repository.getAllTransactions().collect { list ->
                _transactions.value = list
            }
        }

        // Collect live event stream to pop a dynamic banner and force immediate UI list update
        viewModelScope.launch {
            repository.liveTransactionEvent.collect { newTx ->
                _latestCapturedTransaction.value = newTx
                refreshTransactions()
            }
        }
    }

    fun refreshTransactions() {
        viewModelScope.launch {
            val list = repository.getAllTransactionsList()
            _transactions.value = list
        }
    }

    fun onTransactionCapturedLocally(transaction: Transaction) {
        _latestCapturedTransaction.value = transaction
        refreshTransactions()
    }

    fun dismissLiveBanner() {
        _latestCapturedTransaction.value = null
    }

    fun addManualOrSimulatedSms(sender: String, rawSms: String) {
        viewModelScope.launch {
            val parsed = com.jebenapay.smstracker.parser.SmsParser.parseSms(sender, rawSms)
            if (parsed != null) {
                repository.addTransaction(parsed)
            }
        }
    }

    fun clearAll() {
        viewModelScope.launch {
            repository.clearAll()
        }
    }
}
