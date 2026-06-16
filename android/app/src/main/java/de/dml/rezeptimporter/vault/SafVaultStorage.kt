package de.dml.rezeptimporter.vault

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

/**
 * @param subfolder optionaler Zielordner relativ zum gewählten Vault (z.B. "Dashboard").
 *   Fehlende Ordner werden angelegt. null/leer ⇒ direkt in den Vault-Wurzelordner.
 */
class SafVaultStorage(
    private val context: Context,
    private val treeUri: Uri,
    private val subfolder: String? = null,
) : VaultStorage {

    private val root: DocumentFile =
        DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalStateException("Vault-Ordner nicht erreichbar — in Settings neu wählen")

    private val dir: DocumentFile = resolveDir(subfolder)

    /** Pfad-Segmente (per "/" getrennt) unterhalb des Vaults auflösen, fehlende anlegen. */
    private fun resolveDir(path: String?): DocumentFile {
        if (path.isNullOrBlank()) return root
        var current = root
        for (segment in path.split('/').map { it.trim() }.filter { it.isNotEmpty() }) {
            current = current.findFile(segment)?.takeIf { it.isDirectory }
                ?: current.createDirectory(segment)
                ?: throw IllegalStateException("Unterordner konnte nicht angelegt werden: $segment")
        }
        return current
    }

    override fun listMarkdownFiles(): List<VaultFile> =
        dir.listFiles()
            .filter { it.isFile && (it.name ?: "").endsWith(".md", ignoreCase = true) }
            .map { file ->
                val head = context.contentResolver.openInputStream(file.uri)?.use { ins ->
                    val buf = ByteArray(2048)
                    val n = ins.read(buf)
                    if (n <= 0) "" else String(buf, 0, n, Charsets.UTF_8)
                } ?: ""
                VaultFile(
                    fileName = file.name ?: "",
                    id = Regex("(?m)^id:\\s*\"?([A-Za-z0-9-]+)\"?").find(head)?.groupValues?.get(1),
                )
            }

    override fun write(fileName: String, content: String) {
        dir.findFile(fileName)?.delete()
        val file = dir.createFile("text/markdown", fileName)
            ?: throw IllegalStateException("Datei konnte nicht angelegt werden: $fileName")
        context.contentResolver.openOutputStream(file.uri)?.use {
            it.write(content.toByteArray(Charsets.UTF_8))
        } ?: throw IllegalStateException("OutputStream null für $fileName")
    }
}
