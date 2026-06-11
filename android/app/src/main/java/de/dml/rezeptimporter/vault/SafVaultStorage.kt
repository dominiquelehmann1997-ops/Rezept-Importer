package de.dml.rezeptimporter.vault

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

class SafVaultStorage(private val context: Context, private val treeUri: Uri) : VaultStorage {

    private val dir: DocumentFile =
        DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalStateException("Vault-Ordner nicht erreichbar — in Settings neu wählen")

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
