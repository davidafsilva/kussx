package pt.davidafsilva.kussx

import io.vertx.core.json.JsonObject

/**
 * The configuration
 *
 * @author david
 */
class Configuration(private val config: JsonObject) {

    fun getStr(key: String): String? {
        return System.getenv().getOrDefault(key, config.getString(key))
    }

    fun getStr(key: String, default: String): String {
        return System.getenv().getOrDefault(key, config.getString(key, default))
    }

    fun getStr(key: String, default: () -> String): String {
        return System.getenv().getOrDefault(key, config.getString(key, default()))
    }

    fun getInt(key: String, default: Int): Int {
        return System.getenv().getOrDefault(key, config.getInteger(key, default).toString()).toInt()
    }
}
