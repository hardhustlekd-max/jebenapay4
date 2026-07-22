package com.jebenapay.smstracker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jebenapay.smstracker.data.Transaction
import com.jebenapay.smstracker.data.TransactionType
import com.jebenapay.smstracker.viewmodel.TransactionViewModel
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : ComponentActivity() {

    private val viewModel: TransactionViewModel by viewModels()

    private val liveSmsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "com.jebenapay.ACTION_NEW_TRANSACTION") {
                // UI automatically re-collects state flows, but receiver guarantees wakeup
                val sender = intent.getStringExtra("sender") ?: "SMS"
                val amount = intent.getDoubleExtra("amount", 0.0)
                val typeStr = intent.getStringExtra("type") ?: "CREDIT"
                val ref = intent.getStringExtra("reference") ?: ""
                
                val tx = Transaction(
                    sender = sender,
                    amount = amount,
                    type = TransactionType.valueOf(typeStr),
                    reference = ref,
                    merchantOrParty = sender,
                    rawSms = "Live SMS Captured"
                )
                // Trigger live banner in viewmodel
                viewModel.dismissLiveBanner()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        setContent {
            JebenaPayTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0A0A0C)
                ) {
                    MainDashboardScreen(viewModel)
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter("com.jebenapay.ACTION_NEW_TRANSACTION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(liveSmsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(liveSmsReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(liveSmsReceiver)
        } catch (e: Exception) {
            // Receiver not registered
        }
    }
}

@Composable
fun JebenaPayTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color(0xFF0A0A0C),
            surface = Color(0xFF111115),
            primary = Color(0xFF10B981)
        ),
        content = content
    )
}

@Composable
fun MainDashboardScreen(viewModel: TransactionViewModel) {
    val transactions by viewModel.transactions.collectAsState()
    val netBalance by viewModel.netBalance.collectAsState()
    val totalIncome by viewModel.totalIncome.collectAsState()
    val totalExpense by viewModel.totalExpense.collectAsState()
    val latestCaptured by viewModel.latestCapturedTransaction.collectAsState()

    val currencyFormatter = remember { NumberFormat.getCurrencyInstance(Locale.US).apply { currency = Currency.getInstance("ETB") } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // App Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF10B981)),
                    contentAlignment = Alignment.Center
                ) {
                    Text("☕", fontSize = 18.sp)
                }
                Column {
                    Text("JEBENA PAY", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = Color.White)
                    Text("SMS TRANSACTION TRACKER", fontSize = 9.sp, color = Color(0xFF64748B), letterSpacing = 1.sp)
                }
            }

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF10B981).copy(alpha = 0.15f))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            ) {
                Text("LIVE SYNC ON", fontSize = 10.sp, color = Color(0xFF10B981), fontWeight = FontWeight.Bold)
            }
        }

        // Animated Real-Time Live Capture Banner
        AnimatedVisibility(
            visible = latestCaptured != null,
            enter = slideInVertically() + fadeIn(),
            exit = slideOutVertically() + fadeOut()
        ) {
            latestCaptured?.let { tx ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF064E3B)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("⚡ NEW TRANSACTION CAPTURED!", fontWeight = FontWeight.Bold, fontSize = 11.sp, color = Color(0xFF34D399))
                            Text("${tx.sender} — ${tx.type}: ETB ${tx.amount}", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = Color.White)
                            Text("Ref: ${tx.reference}", fontSize = 10.sp, color = Color(0xFFA7F3D0))
                        }
                        TextButton(onClick = { viewModel.dismissLiveBanner() }) {
                            Text("Dismiss", color = Color.White, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Balance Summary Card
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111115)),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("NET BALANCE", fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color(0xFF64748B), letterSpacing = 1.2.sp)
                Text(
                    text = "ETB ${String.format(Locale.US, "%.2f", netBalance)}",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(vertical = 4.dp)
                )

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("TOTAL INCOME", fontSize = 9.sp, color = Color(0xFF64748B))
                        Text("+ ETB ${String.format(Locale.US, "%.2f", totalIncome)}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF34D399))
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("TOTAL EXPENSE", fontSize = 9.sp, color = Color(0xFF64748B))
                        Text("- ETB ${String.format(Locale.US, "%.2f", totalExpense)}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFFF87171))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Recent Transactions Section
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("LIVE TRANSACTIONS (${transactions.size})", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8), letterSpacing = 1.sp)
            TextButton(onClick = {
                viewModel.addManualOrSimulatedSms("Telebirr", "You have received ETB 450.00 from Kebede. Txn ID: TB982347192.")
            }) {
                Text("+ Test Capture", fontSize = 11.sp, color = Color(0xFF10B981))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (transactions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("📲", fontSize = 32.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("No SMS Transactions Captured Yet", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                    Text("Incoming SMS from your bank or wallet will automatically populate here in real-time.", fontSize = 11.sp, color = Color(0xFF64748B))
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(transactions, key = { it.id }) { tx ->
                    TransactionRow(tx)
                }
            }
        }
    }
}

@Composable
fun TransactionRow(tx: Transaction) {
    val isCredit = tx.type == TransactionType.CREDIT
    val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.US) }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF111115)),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(if (isCredit) Color(0xFF064E3B) else Color(0xFF451A03)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(if (isCredit) "⇣" else "⇡", fontSize = 18.sp, color = if (isCredit) Color(0xFF34D399) else Color(0xFFF87171))
                }

                Column {
                    Text(tx.merchantOrParty, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = Color.White)
                    Text("Ref: ${tx.reference} • ${dateFormat.format(Date(tx.timestamp))}", fontSize = 10.sp, color = Color(0xFF64748B))
                }
            }

            Text(
                text = "${if (isCredit) "+" else "-"} ETB ${String.format(Locale.US, "%.2f", tx.amount)}",
                fontWeight = FontWeight.ExtraBold,
                fontSize = 13.sp,
                color = if (isCredit) Color(0xFF34D399) else Color(0xFFF87171)
            )
        }
    }
}
