package best.spaghetcodes.kira.utils

import best.spaghetcodes.kira.kira
import com.google.gson.JsonObject
import java.io.IOException
import java.net.URL


object HttpUtils {

    fun getPlayerStats(uuid: String): JsonObject? {
        val key = kira.config?.apiKey
        if (key != null && key != "") {
            return try {
                val url = URL("https://api.hypixel.net/player?key=${key}&uuid=$uuid")
                val json = url.readText()
                kira.gson.fromJson(json, JsonObject::class.java)
            } catch (e: IOException) {
                val msg = e.message
                val rx = Regex("HTTP response code: (\\d+)")
                val match = rx.find(msg!!)
                if (match != null) {
                    val code = match.groupValues[1].toInt()
                    ChatUtils.info("Error getting stats for $uuid: $code")
                }
                null
            }
        } else {
            return null
        }
    }

    fun usernameToUUID(username: String): String? {
        val url = URL("https://api.mojang.com/users/profiles/minecraft/$username")
        val json = url.readText()
        if (json == "") { // invalid username
            return null
        }
        val obj = kira.gson.fromJson(json, JsonObject::class.java)
        return obj.get("id").asString
    }

}