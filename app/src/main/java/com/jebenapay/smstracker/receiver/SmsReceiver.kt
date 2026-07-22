package com.jebenapay.smstracker.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.jebenapay.smstracker.data.TransactionRepository
import com.jebenapay.smstracker.parser.SmsParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            for (sms in messages) {
                val sender = sms.originatingAddress ?: "Unknown Sender"
                val body = sms.messageBody ?: ""

                Log.d("SmsReceiver", "Received SMS from $sender: $body")

                val transaction = SmsParser.parseSms(sender, body)
                if (transaction != null) {
                    val repo = TransactionRepository.getInstance(context)
                    CoroutineScope(Dispatchers.IO).launch {
                        repo.addTransaction(transaction)

                        // Send dynamic broadcast to notify active MainActivity if open
                        val liveIntent = Intent("com.jebenapay.ACTION_NEW_TRANSACTION").apply {
                            putExtra("sender", transaction.sender)
                            putExtra("amount", transaction.amount)
                            putExtra("type", transaction.type.name)
                            putExtra("reference", transaction.reference)
                        }
                        context.sendBroadcast(liveIntent)
                    }
                }
            }
        }
    }
}
