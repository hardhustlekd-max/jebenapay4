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

    private var lastHandledSmsTime: Long = System.currentTimeMillis()

    init {
        // Find existing highest SMS received date in inbox so we only observe future incoming SMS based on device time
        try {
            val cursor = context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf("date"),
                "type = ?",
                arrayOf("1"), // 1 = Inbox
                "date DESC LIMIT 1"
            )
            cursor?.use { c ->
                if (c.moveToFirst()) {
                    val latestDate = c.getLong(c.getColumnIndexOrThrow("date"))
                    if (latestDate > 0) {
                        lastHandledSmsTime = latestDate
                    }
                    Log.d("SmsObserver", "Initialized lastHandledSmsTime = $lastHandledSmsTime")
                }
            }
        } catch (e: Exception) {
            Log.e("SmsObserver", "Error initializing SmsObserver highest received time", e)
        }
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        try {
            val selection = "type = ? AND date > ?"
            val selectionArgs = arrayOf("1", lastHandledSmsTime.toString())

            val cursor = context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf("_id", "address", "body", "date", "type"),
                selection,
                selectionArgs,
                "date ASC"
            )

            cursor?.use { c ->
                val addressCol = c.getColumnIndexOrThrow("address")
                val bodyCol = c.getColumnIndexOrThrow("body")
                val dateCol = c.getColumnIndexOrThrow("date")

                while (c.moveToNext()) {
                    val smsDate = c.getLong(dateCol) // Time the SMS was received on the device
                    val address = c.getString(addressCol) ?: "SMS"
                    val body = c.getString(bodyCol) ?: ""

                    if (smsDate > lastHandledSmsTime) {
                        lastHandledSmsTime = smsDate
                        Log.d("SmsObserver", "Captured live SMS received at $smsDate from $address: $body")

                        val transaction = SmsParser.parseSms(address, body, timestamp = smsDate)
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

