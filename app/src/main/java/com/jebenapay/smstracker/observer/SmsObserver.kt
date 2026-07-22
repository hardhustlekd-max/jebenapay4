package com.jebenapay.smstracker.observer

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.provider.Telephony
import android.util.Log
import com.jebenapay.smstracker.data.Transaction
import com.jebenapay.smstracker.data.TransactionRepository
import com.jebenapay.smstracker.parser.SmsParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsObserver(
    private val context: Context,
    handler: Handler,
    private val onCaptured: (transaction: Transaction) -> Unit
) : ContentObserver(handler) {

    private var lastHandledSmsId: Long = -1

    init {
        // Find existing highest SMS _id in inbox so we only observe future incoming SMS
        try {
            val cursor = context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf("_id"),
                "type = ?",
                arrayOf("1"),
                "_id DESC LIMIT 1"
            )
            cursor?.use { c ->
                if (c.moveToFirst()) {
                    lastHandledSmsId = c.getLong(c.getColumnIndexOrThrow("_id"))
                    Log.d("SmsObserver", "Initialized lastHandledSmsId = $lastHandledSmsId")
                }
            }
        } catch (e: Exception) {
            Log.e("SmsObserver", "Error initializing SmsObserver highest ID", e)
        }
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        try {
            val selection = if (lastHandledSmsId > 0) "type = ? AND _id > ?" else "type = ?"
            val selectionArgs = if (lastHandledSmsId > 0) arrayOf("1", lastHandledSmsId.toString()) else arrayOf("1")

            val cursor = context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf("_id", "address", "body", "date", "type"),
                selection,
                selectionArgs,
                "_id ASC"
            )

            cursor?.use { c ->
                val idCol = c.getColumnIndexOrThrow("_id")
                val addressCol = c.getColumnIndexOrThrow("address")
                val bodyCol = c.getColumnIndexOrThrow("body")

                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val address = c.getString(addressCol) ?: "SMS"
                    val body = c.getString(bodyCol) ?: ""

                    if (id > lastHandledSmsId) {
                        lastHandledSmsId = id
                        Log.d("SmsObserver", "Captured live SMS (ID $id) from $address: $body")

                        val transaction = SmsParser.parseSms(address, body)
                        if (transaction != null) {
                            val repo = TransactionRepository.getInstance(context)
                            CoroutineScope(Dispatchers.IO).launch {
                                repo.addTransaction(transaction)
                                CoroutineScope(Dispatchers.Main).launch {
                                    onCaptured(transaction)
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("SmsObserver", "Error in SmsObserver onChange", e)
        }
    }
}

