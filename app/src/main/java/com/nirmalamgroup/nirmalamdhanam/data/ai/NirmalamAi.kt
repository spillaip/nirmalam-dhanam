package com.nirmalamgroup.nirmalamdhanam.data.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.nirmalamgroup.nirmalamdhanam.data.local.InvestmentBalanceSnapshotEntity
import com.nirmalamgroup.nirmalamdhanam.data.local.TransactionDirection
import com.nirmalamgroup.nirmalamdhanam.data.local.TransactionEntity
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

enum class NirmalamAiInsight(val title: String, val prompt: String) {
    SPENDING_FOCUS("Spending focus", "Explain the biggest spending patterns and name one practical, non-judgemental focus for the coming week."),
    CASH_PLAN("Cash plan", "Assess available cash, current-period income and expenses. Suggest a calm next action; do not provide regulated financial advice."),
    PORTFOLIO_REVIEW("Portfolio review", "Describe portfolio allocation, gain/loss and XIRR context in plain language. Do not recommend securities, funds, or trades."),
    MONTHLY_RECAP("Monthly recap", "Write a brief factual monthly recap with wins, changes, and one question for the user to consider.")
}

data class NirmalamAiSettings(val endpoint: String, val model: String, val enabled: Boolean)

/** Stores the provider key encrypted with an Android Keystore AES key; it is never put in Room. */
class NirmalamAiPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("nirmalam_ai", Context.MODE_PRIVATE)

    fun settings(): NirmalamAiSettings = NirmalamAiSettings(
        endpoint = prefs.getString("endpoint", "https://api.openai.com/v1") ?: "https://api.openai.com/v1",
        model = prefs.getString("model", "") ?: "",
        enabled = prefs.getBoolean("enabled", false)
    )

    fun isReady(): Boolean = settings().enabled && settings().model.isNotBlank() && decryptKey() != null

    fun save(endpoint: String, model: String, apiKey: String, enabled: Boolean) {
        require(endpoint.startsWith("https://")) { "Use an HTTPS provider endpoint." }
        require(model.isNotBlank()) { "Enter a model name." }
        require(apiKey.isNotBlank()) { "Enter an API key." }
        val (ciphertext, iv) = encrypt(apiKey)
        check(prefs.edit().putString("endpoint", endpoint.trim().trimEnd('/')).putString("model", model.trim()).putString("key", ciphertext).putString("iv", iv).putBoolean("enabled", enabled).commit())
    }

    fun disable() { check(prefs.edit().putBoolean("enabled", false).commit()) }
    fun clear() { check(prefs.edit().clear().commit()) }
    fun apiKey(): String? = decryptKey()

    private fun encrypt(value: String): Pair<String, String> {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.doFinal(value.encodeToByteArray()), Base64.NO_WRAP) to Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
    }
    private fun decryptKey(): String? = runCatching {
        val encrypted = prefs.getString("key", null) ?: return null
        val iv = prefs.getString("iv", null) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP))) }
        cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)).decodeToString()
    }.getOrNull()
    private fun key() = (KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey("nirmalam_ai_key", null)
        ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("nirmalam_ai_key", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey())
}

object NirmalamAiClient {
    fun request(settings: NirmalamAiSettings, apiKey: String, insight: NirmalamAiInsight, financeSummary: String): Result<String> = runCatching {
        val url = URL(settings.endpoint.trimEnd('/') + "/chat/completions")
        val body = JSONObject().put("model", settings.model).put("temperature", 0.2).put("max_tokens", 450).put("messages", JSONArray()
            .put(JSONObject().put("role", "system").put("content", "You are Nirmalam AI, a private personal-finance reflection assistant. Use only supplied aggregate data. Never state that you accessed data not supplied. Never give investment, tax, legal, credit, or trading advice. Be concise, factual, and non-judgemental."))
            .put(JSONObject().put("role", "user").put("content", "Preset: ${insight.title}. ${insight.prompt}\n\nAggregate local summary:\n$financeSummary")))
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 20_000; readTimeout = 30_000; doOutput = true
            instanceFollowRedirects = false
            setRequestProperty("Content-Type", "application/json"); setRequestProperty("Authorization", "Bearer $apiKey")
        }
        connection.outputStream.use { it.write(body.toString().encodeToByteArray()) }
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val response = stream.bufferedReader().use { it.readText() }
        check(connection.responseCode in 200..299) { "Provider request failed (${connection.responseCode}). ${JSONObject(response).optString("error").ifBlank { "Check the endpoint, model, and key." }}" }
        JSONObject(response).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content").trim().also { check(it.isNotBlank()) { "The provider returned an empty insight." } }
    }
}

fun buildNirmalamAiSummary(
    cashPaise: Long,
    transactions: List<TransactionEntity>,
    investmentHistory: List<InvestmentBalanceSnapshotEntity>
): String {
    val monthStart = Calendar.getInstance().apply { set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    val month = transactions.filter { it.occurredAtEpochMs >= monthStart && !it.isHoldingTank }
    val income = month.filter { it.direction == TransactionDirection.CREDIT }.sumOf { it.amountPaise }
    val expense = month.filter { it.direction == TransactionDirection.DEBIT }.sumOf { it.amountPaise }
    val categories = month.filter { it.direction == TransactionDirection.DEBIT }.groupBy { it.category?.ifBlank { null } ?: "Uncategorised" }
        .mapValues { (_, items) -> items.sumOf { it.amountPaise } }.entries.sortedByDescending { it.value }.take(5)
        .joinToString { "${it.key}: ₹${it.value / 100.0}" }
    val latest = investmentHistory.groupBy { it.accountId }.values.map { it.maxBy { snapshot -> snapshot.asOfEpochDay } }
    val portfolioValue = latest.sumOf { it.currentValuePaise }
    val portfolioCost = latest.sumOf { it.totalCostPaise }
    return "Currency: INR. Available cash: ₹${cashPaise / 100.0}. This-month income: ₹${income / 100.0}. This-month expense: ₹${expense / 100.0}. Top spending categories: ${categories.ifBlank { "none recorded" }.replace("₹", "INR ")}. Investment holdings: ${latest.size}. Portfolio cost: ₹${portfolioCost / 100.0}. Portfolio value: ₹${portfolioValue / 100.0}. No individual payees, descriptions, raw transactions, or account identifiers were shared."
}
