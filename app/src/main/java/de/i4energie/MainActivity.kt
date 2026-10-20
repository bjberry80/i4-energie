package de.i4energie

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import de.i4energie.data.HaClient
import de.i4energie.data.HaRepository
import de.i4energie.data.HaSettings
import de.i4energie.data.RepoState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Einstellungen am Handy: HA-URL, Token, Verbindungstest und Status der letzten Abfrage. */
class MainActivity : ComponentActivity() {

    private lateinit var urlField: EditText
    private lateinit var tokenField: EditText
    private lateinit var messageView: TextView
    private lateinit var statusView: TextView
    private val timeFormat = SimpleDateFormat("dd.MM. HH:mm:ss", Locale.GERMANY)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        HaRepository.init(this)

        urlField = findViewById(R.id.url)
        tokenField = findViewById(R.id.token)
        messageView = findViewById(R.id.message)
        statusView = findViewById(R.id.status)

        val saved = HaRepository.settings().load()
        urlField.setText(saved.baseUrl)
        updateTokenHint(saved)

        findViewById<Button>(R.id.save).setOnClickListener { save() }
        findViewById<Button>(R.id.test).setOnClickListener { testConnection() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                HaRepository.state.collect { showStatus(it) }
            }
        }
    }

    /** Eingaben zusammenführen: leeres Token-Feld behält den gespeicherten Token. */
    private fun currentInput(): HaSettings {
        val saved = HaRepository.settings().load()
        val token = tokenField.text.toString().trim().ifEmpty { saved.token }
        return HaSettings(urlField.text.toString().trim(), token)
    }

    private fun save() {
        val input = currentInput()
        if (!input.isComplete) {
            messageView.setText(R.string.msg_missing)
            return
        }
        val normalized = input.copy(baseUrl = HaClient.normalizeBaseUrl(input.baseUrl))
        HaRepository.settings().save(normalized)
        urlField.setText(normalized.baseUrl)
        tokenField.text.clear()
        updateTokenHint(normalized)
        HaRepository.onSettingsChanged()
        messageView.setText(R.string.msg_saved)
    }

    private fun testConnection() {
        val input = currentInput()
        if (!input.isComplete) {
            messageView.setText(R.string.msg_missing)
            return
        }
        messageView.setText(R.string.msg_testing)
        lifecycleScope.launch {
            val error = withContext(Dispatchers.IO) {
                try {
                    HaClient(input.baseUrl, input.token).testConnection()
                    null
                } catch (e: Exception) {
                    e.message ?: e.javaClass.simpleName
                }
            }
            messageView.text = if (error == null) getString(R.string.msg_test_ok) else getString(R.string.msg_test_fail, error)
        }
    }

    private fun updateTokenHint(settings: HaSettings) {
        tokenField.setHint(if (settings.token.isNotEmpty()) R.string.hint_token_saved else R.string.hint_token)
    }

    private fun showStatus(state: RepoState) {
        val attempt = state.lastAttempt
        statusView.text = when {
            attempt == null -> getString(R.string.status_none)
            state.error == null -> getString(R.string.status_ok, timeFormat.format(Date(attempt)))
            else -> getString(R.string.status_error, timeFormat.format(Date(attempt)), state.error)
        }
    }
}
