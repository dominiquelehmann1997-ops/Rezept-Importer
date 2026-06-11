package de.dml.rezeptimporter.vault

data class VaultFile(val fileName: String, val id: String?)

interface VaultStorage {
    fun listMarkdownFiles(): List<VaultFile>
    fun write(fileName: String, content: String)
}
