package com.apkgod.autoclicker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object ActionTypes {
    const val TAP = "tap"
    const val DOUBLE_TAP = "double_tap"
    const val LONG_PRESS = "long_press"
    const val SWIPE = "swipe"
    const val WAIT = "wait"
    const val IMAGE = "image"
    const val IF_IMAGE = "if_image"
    const val IF_TEXT = "if_text"
    const val ELSE = "else"
    const val IF_END = "if_end"
    const val LOOP_START = "loop_start"
    const val LOOP_END = "loop_end"
    const val STOP = "stop"
}

data class MacroAction(
    val type: String,
    val x: Float = 0f, val y: Float = 0f,
    val x2: Float = 0f, val y2: Float = 0f,
    val durationMs: Long = 400L, val delayMs: Long = 300L,
    val imagePath: String? = null, val confidence: Float = 0.82f,
    val timeoutMs: Long = 2500L,
    val regionLeft: Int = 0, val regionTop: Int = 0,
    val regionRight: Int = 0, val regionBottom: Int = 0,
    val text: String? = null, val loopCount: Int = 2
)

object ActionRepository {
    private const val PREFS = "auto_clicker_v3"
    private const val ACTIONS = "actions"
    private const val RUNNING = "running"
    private const val REPEAT = "repeat_count"

    fun saveActions(context: Context, actions: List<MacroAction>) {
        prefs(context).edit().putString(ACTIONS, encode(actions).toString()).apply()
    }

    fun loadActions(context: Context): MutableList<MacroAction> = decode(prefs(context).getString(ACTIONS, null))

    fun exportJson(context: Context): String = encode(loadActions(context)).toString(2)

    fun importJson(context: Context, raw: String): Boolean = runCatching {
        val decoded = decode(raw)
        saveActions(context, decoded)
        decoded.isNotEmpty() || raw.trim() == "[]"
    }.getOrDefault(false)

    fun setRepeatCount(context: Context, count: Int) = prefs(context).edit().putInt(REPEAT, count.coerceIn(0, 999999)).apply()
    fun repeatCount(context: Context): Int = prefs(context).getInt(REPEAT, 0)
    fun setRunning(context: Context, running: Boolean) = prefs(context).edit().putBoolean(RUNNING, running).apply()
    fun isRunning(context: Context): Boolean = prefs(context).getBoolean(RUNNING, false)

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
        context.filesDir.listFiles()?.filter { it.name.startsWith("template_") }?.forEach(File::delete)
    }

    private fun encode(actions: List<MacroAction>) = JSONArray().apply {
        actions.forEach { a -> put(JSONObject().apply {
            put("type", a.type); put("x", a.x); put("y", a.y); put("x2", a.x2); put("y2", a.y2)
            put("durationMs", a.durationMs); put("delayMs", a.delayMs); put("imagePath", a.imagePath ?: "")
            put("confidence", a.confidence); put("timeoutMs", a.timeoutMs)
            put("regionLeft", a.regionLeft); put("regionTop", a.regionTop); put("regionRight", a.regionRight); put("regionBottom", a.regionBottom)
            put("text", a.text ?: ""); put("loopCount", a.loopCount)
        }) }
    }

    private fun decode(raw: String?): MutableList<MacroAction> {
        if (raw.isNullOrBlank()) return mutableListOf()
        return runCatching {
            val arr = JSONArray(raw)
            MutableList(arr.length()) { i ->
                val o = arr.getJSONObject(i)
                MacroAction(
                    type = o.optString("type", ActionTypes.TAP),
                    x = o.optDouble("x", 0.0).toFloat(), y = o.optDouble("y", 0.0).toFloat(),
                    x2 = o.optDouble("x2", 0.0).toFloat(), y2 = o.optDouble("y2", 0.0).toFloat(),
                    durationMs = o.optLong("durationMs", 400).coerceAtLeast(0), delayMs = o.optLong("delayMs", 300).coerceAtLeast(0),
                    imagePath = o.optString("imagePath").takeIf { it.isNotBlank() },
                    confidence = o.optDouble("confidence", .82).toFloat().coerceIn(.55f, .99f),
                    timeoutMs = o.optLong("timeoutMs", 2500).coerceIn(300, 30000),
                    regionLeft = o.optInt("regionLeft", 0), regionTop = o.optInt("regionTop", 0),
                    regionRight = o.optInt("regionRight", 0), regionBottom = o.optInt("regionBottom", 0),
                    text = o.optString("text").takeIf { it.isNotBlank() },
                    loopCount = o.optInt("loopCount", 2).coerceIn(1, 999999)
                )
            }
        }.getOrElse { mutableListOf() }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getActions(): List<Any> = actions.toList()

    fun updateTapCoordinates(index: Int, x: Int, y: Int) {
        if (index !in actions.indices) return
        val action = actions[index]
        try {
            val type = action.javaClass.getDeclaredField("type").apply { isAccessible = true }.get(action)?.toString()
            if (!type.equals("tap", ignoreCase = true)) return
            action.javaClass.getDeclaredField("x").apply { isAccessible = true }.set(action, x)
            action.javaClass.getDeclaredField("y").apply { isAccessible = true }.set(action, y)
            save()
        } catch (_: Throwable) {
            // Model-specific repository implementations can override this API.
        }
    }

}
