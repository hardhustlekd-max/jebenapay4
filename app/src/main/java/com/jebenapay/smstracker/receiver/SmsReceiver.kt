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
            val pendingResult = goAsync()
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    for (sms in messages) {
                        val sender = sms.originatingAddress ?: "Unknown Sender"
                        val body = sms.messageBody ?: ""
                        val smsTime = sms.timestampMillis

                        Log.d("SmsReceiver", "Received SMS from $sender at $smsTime: $body")

                        val transaction = SmsParser.parseSms(sender, body, timestamp = smsTime)
                        if (transaction != null) {
                            val repo = TransactionRepository.getInstance(context)
                            repo.addTransaction(transaction)

                            // Send broadcast explicitly to active MainActivity
                            val liveIntent = Intent("com.jebenapay.ACTION_NEW_TRANSACTION").apply {
                                setPackage(context.packageName)
                                putExtra("sender", transaction.sender)
                                putExtra("amount", transaction.amount)
                                putExtra("type", transaction.type.name)
                                putExtra("reference", transaction.reference)
                                putExtra("party", transaction.merchantOrParty)
                            }
                            context.sendBroadcast(liveIntent)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SmsReceiver", "Error handling SMS capture", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}

