package de.dml.rezeptimporter.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.dml.rezeptimporter.settings.AppSettings
import de.dml.rezeptimporter.settings.Provider

class MainActivity : ComponentActivity() {

    private lateinit var settings: AppSettings

    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
                settings.vaultUri = uri
                vaultUriState.value = uri.toString()
            }
        }

    private val vaultUriState = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = AppSettings(this)
        vaultUriState.value = settings.vaultUri?.toString() ?: ""

        setContent {
            MaterialTheme {
                var provider by remember { mutableStateOf(settings.provider) }
                var geminiKey by remember { mutableStateOf(settings.geminiKey) }
                var anthropicKey by remember { mutableStateOf(settings.anthropicKey) }
                val vaultUri by vaultUriState

                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Rezept-Importer", style = MaterialTheme.typography.headlineSmall)

                    Text("Vault-Ordner", style = MaterialTheme.typography.titleMedium)
                    Text(if (vaultUri.isEmpty()) "— nicht gewählt —" else vaultUri,
                        style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { pickFolder.launch(null) }) { Text("Ordner wählen") }

                    HorizontalDivider()

                    Text("LLM-Provider", style = MaterialTheme.typography.titleMedium)
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        RadioButton(provider == Provider.GEMINI, onClick = {
                            provider = Provider.GEMINI; settings.provider = Provider.GEMINI
                        })
                        Text("Gemini Flash (Free Tier)")
                    }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        RadioButton(provider == Provider.HAIKU, onClick = {
                            provider = Provider.HAIKU; settings.provider = Provider.HAIKU
                        })
                        Text("Claude Haiku")
                    }

                    OutlinedTextField(
                        value = geminiKey,
                        onValueChange = { geminiKey = it; settings.geminiKey = it },
                        label = { Text("Gemini API-Key") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = anthropicKey,
                        onValueChange = { anthropicKey = it; settings.anthropicKey = it },
                        label = { Text("Anthropic API-Key") },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Text(
                        "Teile ein Foto oder einen Text mit dieser App, um ein Rezept zu importieren.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
