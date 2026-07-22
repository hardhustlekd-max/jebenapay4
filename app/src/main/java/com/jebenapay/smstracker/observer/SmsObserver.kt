package com.jebenapay.smstracker.observer

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.provider.Telephony
import android.util.Log
import com.jebenapay.smstracker.data.TransactionRepository
import com.jebenapay.smstracker.parser.SmsParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsObserver(
    private val context: Context,
    handler: Handler,
    private val onCaptured: (transaction: com.jebenapay.smstracker.data.Transaction) -> Unit
) : ContentObserver(handler) {

    private var lastHandledSmsId: Long = -1

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        try {
            val cursor = context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf("_id", "address", "body", "date", "type"),
                "type = ?",
                arrayOf("1"), // 1 = Inbox (received)
                "date DESC LIMIT 1"
            )

            cursor?.use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(c.getColumnIndexOrThrow("_id"))
                    val address = c.getString(c.getColumnIndexOrThrow("address")) ?: ""
                    val body = c.getString(c.getColumnIndexOrThrow("body")) ?: ""

                    if (id != lastHandledSmsId) {
                        lastHandledSmsId = id
                        Log.d("SmsObserver", "ContentObserver detected new SMS from $address: $body")

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
