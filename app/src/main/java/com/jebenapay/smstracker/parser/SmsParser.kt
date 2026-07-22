package com.jebenapay.smstracker.parser

import com.jebenapay.smstracker.data.Transaction
import com.jebenapay.smstracker.data.TransactionType
import java.util.regex.Pattern

object SmsParser {

    fun parseSms(sender: String, body: String, timestamp: Long = System.currentTimeMillis()): Transaction? {
        val cleanBody = body.trim()
        val lowerBody = cleanBody.lowercase()

        // Determine transaction type
        val type = when {
            lowerBody.contains("credited") || lowerBody.contains("received") || lowerBody.contains("deposited") || lowerBody.contains("cash-in") || lowerBody.contains("transferred to your") || lowerBody.contains("added") -> TransactionType.CREDIT
            lowerBody.contains("debited") || lowerBody.contains("paid") || lowerBody.contains("transferred") || lowerBody.contains("sent") || lowerBody.contains("withdrawn") || lowerBody.contains("purchased") -> TransactionType.DEBIT
            else -> TransactionType.CREDIT
        }

        // Extract Amount (e.g., ETB 500.00, 1,250.00 ETB, Birr 250, USD 45)
        val amountRegex = Pattern.compile("(?:ETB|Birr|USD|EUR|\\$)?\\s*([0-9]{1,3}(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?)\\s*(?:ETB|Birr)?", Pattern.CASE_INSENSITIVE)
        val matcher = amountRegex.matcher(cleanBody)
        var amount = 0.0
        
        while (matcher.find()) {
            val numStr = matcher.group(1)?.replace(",", "") ?: ""
            val parsed = numStr.toDoubleOrNull()
            if (parsed != null && parsed > 0) {
                amount = parsed
                break
            }
        }

        if (amount == 0.0) {
            // Fallback: look for any decimal or number
            val fallbackMatcher = Pattern.compile("([0-9]+\\.[0-9]{2})").matcher(cleanBody)
            if (fallbackMatcher.find()) {
                amount = fallbackMatcher.group(1)?.toDoubleOrNull() ?: 0.0
            }
        }

        // Extract Reference / Txn ID
        val refRegex = Pattern.compile("(?:txn|txid|ref|reference|no|id)[:.]?\\s*([a-zA-Z0-9]{6,20})", Pattern.CASE_INSENSITIVE)
        val refMatcher = refRegex.matcher(cleanBody)
        val reference = if (refMatcher.find()) refMatcher.group(1) ?: "N/A" else "TXN-${System.currentTimeMillis().toString().takeLast(8)}"

        // Merchant or Party
        val party = when {
            lowerBody.contains("from") -> cleanBody.substringAfter("from", "").take(25).trim()
            lowerBody.contains("to") -> cleanBody.substringAfter("to", "").take(25).trim()
            else -> sender
        }

        // Category guessing
        val category = when {
            lowerBody.contains("telebirr") -> "Mobile Wallet"
            lowerBody.contains("cbe") -> "Bank Transfer"
            lowerBody.contains("coffee") || lowerBody.contains("jebena") -> "Food & Beverage"
            lowerBody.contains("bill") || lowerBody.contains("electric") -> "Utilities"
            else -> "Financial"
        }

        return Transaction(
            sender = sender.ifBlank { "Bank SMS" },
            amount = amount,
            type = type,
            reference = reference,
            merchantOrParty = party.ifBlank { sender },
            rawSms = body,
            timestamp = timestamp,
            category = category
        )
    }
}
