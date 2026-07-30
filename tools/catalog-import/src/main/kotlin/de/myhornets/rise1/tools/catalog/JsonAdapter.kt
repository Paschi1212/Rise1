package de.myhornets.rise1.tools.catalog

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Die **einzige** Stelle im Werkzeug, die JSON kennt. T-010.
 *
 * Bewusst so klein: Ein Wechsel der Bibliothek berührt diese Datei und sonst
 * nichts. [CatalogSourceValidator] arbeitet auf Maps und Listen und weiß
 * nicht, woher sie kommen.
 */
public object JsonAdapter {

    private val gson = Gson()
    private val typ = object : TypeToken<Map<String, Any?>>() {}.type

    public fun parse(text: String): Map<String, Any?> =
        gson.fromJson<Map<String, Any?>>(text, typ)
            ?: throw IllegalArgumentException("Die Quelldatei enthält kein JSON-Objekt.")

    public fun toPrettyJson(value: Any): String =
        com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(value)
}
