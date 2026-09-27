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
}

data class MacroAction(
    val type: String,
    val x: Float = 0f,
    val y: Float = 0f,
    val x2: Float = 0f,
    val y2: Float = 0f,
    val durationMs: Long = 400L,
    val delayMs: Long = 300L,
    val imagePath: String? = null,
    val confidence: Float = 0.82f,
    val timeoutMs: Long = 2500L,
    val regionLeft: Int = 0,
    val regionTop: Int = 0,
    val regionRight: Int = 0,
    val regionBottom: Int = 0
)

object ActionRepository {
    private const val PREFS = "auto_clicker_v2"
    private const val ACTIONS = "actions"
    private const val RUNNING = "running"
    private const val REPEAT = "repeat_count"

    fun saveActions(context: Context, actions: List<MacroAction>) {
        val array = JSONArray()
        actions.forEach { a ->
            array.put(JSONObject().apply {
                put("type", a.type)
                put("x", a.x.toDouble()); put("y", a.y.toDouble())
                put("x2", a.x2.toDouble()); put("y2", a.y2.toDouble())
                put("durationMs", a.durationMs); put("delayMs", a.delayMs)
                put("imagePath", a.imagePath ?: "")
                put("confidence", a.confidence.toDouble()); put("timeoutMs", a.timeoutMs)
                put("regionLeft", a.regionLeft); put("regionTop", a.regionTop)
                put("regionRight", a.regionRight); put("regionBottom", a.regionBottom)
            })
        }
        prefs(context).edit().putString(ACTIONS, array.toString()).apply()
    }

    fun loadActions(context: Context): MutableList<MacroAction> {
        val raw = prefs(context).getString(ACTIONS, null) ?: return mutableListOf()
        return runCatching {
            val array = JSONArray(raw)
            MutableList(array.length()) { i ->
                val o = array.getJSONObject(i)
                MacroAction(
                    type = o.optString("type", ActionTypes.TAP),
                    x = o.optDouble("x").toFloat(), y = o.optDouble("y").toFloat(),
                    x2 = o.optDouble("x2").toFloat(), y2 = o.optDouble("y2").toFloat(),
                    durationMs = o.optLong("durationMs", 400).coerceAtLeast(80),
                    delayMs = o.optLong("delayMs", 300).coerceAtLeast(0),
                    imagePath = o.optString("imagePath").takeIf { it.isNotBlank() },
                    confidence = o.optDouble("confidence", .82).toFloat().coerceIn(.55f, .99f),
                    timeoutMs = o.optLong("timeoutMs", 2500).coerceIn(300, 15000),
                    regionLeft = o.optInt("regionLeft", 0), regionTop = o.optInt("regionTop", 0),
                    regionRight = o.optInt("regionRight", 0), regionBottom = o.optInt("regionBottom", 0)
                )
            }
        }.getOrElse { mutableListOf() }
    }

    fun setRepeatCount(context: Context, count: Int) = prefs(context).edit()
        .putInt(REPEAT, count.coerceIn(0, 999999)).apply()

    fun repeatCount(context: Context): Int = prefs(context).getInt(REPEAT, 0)

    fun setRunning(context: Context, running: Boolean) = prefs(context).edit()
        .putBoolean(RUNNING, running).apply()

    fun isRunning(context: Context): Boolean = prefs(context).getBoolean(RUNNING, false)

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
        context.filesDir.listFiles()?.filter { it.name.startsWith("template_") }?.forEach(File::delete)
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
