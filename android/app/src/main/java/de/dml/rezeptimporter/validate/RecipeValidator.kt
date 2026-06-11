package de.dml.rezeptimporter.validate

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SpecVersion
import org.yaml.snakeyaml.Yaml

class RecipeValidator(schemaJson: String) {

    private val schema: JsonSchema = JsonSchemaFactory
        .getInstance(SpecVersion.VersionFlag.V202012)
        .getSchema(schemaJson)
    private val mapper = ObjectMapper()
    private val yaml = Yaml()

    /** Volles Markdown rein → Frontmatter-Roundtrip + Schema-Check. Leere Liste = valide. */
    fun validateMarkdown(markdown: String): List<String> {
        if (!markdown.startsWith("---\n")) return listOf("missing frontmatter delimiter")
        val fmText = markdown.removePrefix("---\n").substringBefore("\n---")

        // Parse YAML to Map
        val data: Any? = try {
            yaml.load<Any>(fmText)
        } catch (e: Exception) {
            return listOf("invalid YAML: ${e.message}")
        }

        if (data == null || data !is Map<*, *>) return listOf("frontmatter is not a mapping")

        // Convert the Map to JsonNode using Jackson's object mapper
        val node: JsonNode = try {
            mapper.convertValue(data, JsonNode::class.java)
        } catch (e: Exception) {
            return listOf("failed to convert YAML to JSON: ${e.message}")
        }

        return schema.validate(node).map { it.message }
    }
}
