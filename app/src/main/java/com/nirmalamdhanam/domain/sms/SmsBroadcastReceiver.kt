package com.nirmalamgroup.nirmalamdhanam.domain.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

fun interface ParsedSmsAlertSink { suspend fun accept(alert: ParsedSmsAlert, receivedAtEpochMs: Long) }
/** App startup registers an authenticated in-process sink. No raw message body crosses this boundary. */
object SmsIngestionDispatcher { @Volatile var sink: ParsedSmsAlertSink? = null }

class SmsBroadcastReceiver(private val parser: SmsParserEngine = SmsParserEngine()) : BroadcastReceiver() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val pending = goAsync()
        scope.launch {
            try {
                Telephony.Sms.Intents.getMessagesFromIntent(intent).forEach { sms ->
                    // body is a short-lived local value and is intentionally never logged or stored.
                    parser.parse(sms.messageBody)?.let { SmsIngestionDispatcher.sink?.accept(it, sms.timestampMillis) }
                }
            } finally { pending.finish() }
        }
    }
}
