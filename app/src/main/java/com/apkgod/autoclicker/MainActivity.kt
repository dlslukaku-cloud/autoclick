package com.apkgod.autoclicker

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var repeatInput: EditText

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) showImageDialog(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        renderActions()
    }

    override fun onResume() { super.onResume(); updateStatus(); renderActions() }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 24, 20, 24) }
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)

        content.addView(TextView(this).apply { text = "Tự động nhấn V2"; textSize = 30f; setTypeface(null, android.graphics.Typeface.BOLD) })
        status = TextView(this).apply { textSize = 14f; setPadding(0, 8, 0, 12) }
        content.addView(status)

        content.addView(button("⚙  Bật / quản lý Accessibility") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })

        content.addView(section("Macro", "Thêm action theo thứ tự thực thi."))
        val addRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(list)

        val actionButtons = listOf(
            "Tap" to { showTapDialog(ActionTypes.TAP) },
            "Double Tap" to { showTapDialog(ActionTypes.DOUBLE_TAP) },
            "Long Press" to { showTapDialog(ActionTypes.LONG_PRESS) },
            "Swipe" to { showSwipeDialog() },
            "Wait" to { showWaitDialog() },
            "Image" to { pickImage.launch("image/*") }
        )
        actionButtons.forEach { (label, action) -> addRow.addView(button(label, action).apply { textSize = 12f }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = 4 }) }
        content.addView(addRow)

        val settingsCard = MaterialCardView(this).apply { radius = 22f; setContentPadding(16, 16, 16, 16); layoutParams = lpTop(14) }
        val settings = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        settings.addView(TextView(this).apply { text = "Vòng lặp"; textSize = 19f; setTypeface(null, android.graphics.Typeface.BOLD) })
        settings.addView(TextView(this).apply { text = "0 = vô hạn. Số dương = số lần chạy macro."; textSize = 13f })
        repeatInput = edit("0", InputType.TYPE_CLASS_NUMBER)
        repeatInput.setText(ActionRepository.repeatCount(this).toString())
        settings.addView(repeatInput)
        settings.addView(button("Lưu số vòng lặp") { ActionRepository.setRepeatCount(this, repeatInput.text.toString().toIntOrNull() ?: 0); toast("Đã lưu") })
        settingsCard.addView(settings); content.addView(settingsCard)

        content.addView(button("▶  BẮT ĐẦU") { setRunning(true) }, lpTop(18))
        content.addView(button("■  DỪNG") { setRunning(false) })
        content.addView(button("Xóa toàn bộ macro") { ActionRepository.clear(this); renderActions(); toast("Đã xóa") })
        content.addView(TextView(this).apply { text = "Image action: chọn ảnh mẫu, confidence và vùng tìm kiếm. Screenshot được xử lý cục bộ trên thiết bị."; textSize = 13f; setPadding(0, 14, 0, 0) })

        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun renderActions() {
        if (!::list.isInitialized) return
        list.removeAllViews()
        val actions = ActionRepository.loadActions(this)
        if (actions.isEmpty()) {
            list.addView(TextView(this).apply { text = "Chưa có action."; setPadding(0, 12, 0, 12) })
            return
        }
        actions.forEachIndexed { index, a ->
            val card = MaterialCardView(this).apply { radius = 18f; setContentPadding(14, 12, 14, 12); layoutParams = lpTop(8) }
            val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            row.addView(TextView(this).apply { text = "${index + 1}. ${label(a)}"; textSize = 17f; setTypeface(null, android.graphics.Typeface.BOLD) })
            row.addView(TextView(this).apply { text = detail(a); textSize = 12f })
            val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            controls.addView(button("↑") { move(index, -1) }, weightLp())
            controls.addView(button("↓") { move(index, 1) }, weightLp())
            controls.addView(button("✎") { editAction(index) }, weightLp())
            controls.addView(button("×") { delete(index) }, weightLp())
            row.addView(controls); card.addView(row); list.addView(card)
        }
    }

    private fun label(a: MacroAction) = when (a.type) {
        ActionTypes.TAP -> "Tap"; ActionTypes.DOUBLE_TAP -> "Double Tap"; ActionTypes.LONG_PRESS -> "Long Press"
        ActionTypes.SWIPE -> "Swipe"; ActionTypes.WAIT -> "Wait"; ActionTypes.IMAGE -> "Image Match"; else -> a.type
    }

    private fun detail(a: MacroAction) = when (a.type) {
        ActionTypes.TAP, ActionTypes.DOUBLE_TAP, ActionTypes.LONG_PRESS -> "(${a.x.toInt()}, ${a.y.toInt()}) • delay ${a.delayMs}ms"
        ActionTypes.SWIPE -> "(${a.x.toInt()},${a.y.toInt()}) → (${a.x2.toInt()},${a.y2.toInt()}) • ${a.durationMs}ms"
        ActionTypes.WAIT -> "${a.durationMs}ms"
        ActionTypes.IMAGE -> "${File(a.imagePath ?: "").name} • confidence ${(a.confidence * 100).toInt()}% • timeout ${a.timeoutMs}ms"
        else -> ""
    }

    private fun showTapDialog(type: String, existing: MacroAction? = null, index: Int = -1) {
        val box = form("X", existing?.x?.toInt()?.toString() ?: "500", "Y", existing?.y?.toInt()?.toString() ?: "500", "Delay ms", existing?.delayMs?.toString() ?: "300")
        AlertDialog.Builder(this).setTitle(label(MacroAction(type))).setView(box.root).setPositiveButton("Lưu") { _, _ ->
            val a = MacroAction(type, box.values[0](), box.values[1](), delayMs = box.values[2]().toLong().coerceAtLeast(0))
            saveEdited(index, a)
        }.setNegativeButton("Hủy", null).show()
    }

    private fun showLongPressDialog(existing: MacroAction? = null, index: Int = -1) {
        val box = form("X", existing?.x?.toInt()?.toString() ?: "500", "Y", existing?.y?.toInt()?.toString() ?: "500", "Hold ms", existing?.durationMs?.toString() ?: "800", "Delay ms", existing?.delayMs?.toString() ?: "300")
        AlertDialog.Builder(this).setTitle("Long Press").setView(box.root).setPositiveButton("Lưu") { _, _ ->
            val v = box.values.map { it() }
            saveEdited(index, MacroAction(ActionTypes.LONG_PRESS, v[0], v[1], durationMs = v[2].toLong().coerceAtLeast(500), delayMs = v[3].toLong().coerceAtLeast(0)))
        }.setNegativeButton("Hủy", null).show()
    }

    private fun showSwipeDialog(existing: MacroAction? = null, index: Int = -1) {
        val box = form("X1", existing?.x?.toInt()?.toString() ?: "300", "Y1", existing?.y?.toInt()?.toString() ?: "800", "X2", existing?.x2?.toInt()?.toString() ?: "700", "Y2", existing?.y2?.toInt()?.toString() ?: "800", "Duration ms", existing?.durationMs?.toString() ?: "500", "Delay ms", existing?.delayMs?.toString() ?: "300")
        AlertDialog.Builder(this).setTitle("Swipe").setView(box.root).setPositiveButton("Lưu") { _, _ ->
            val v = box.values.map { it() }
            saveEdited(index, MacroAction(ActionTypes.SWIPE, v[0], v[1], v[2], v[3], v[4].toLong().coerceAtLeast(80), v[5].toLong().coerceAtLeast(0)))
        }.setNegativeButton("Hủy", null).show()
    }

    private fun showWaitDialog(existing: MacroAction? = null, index: Int = -1) {
        val box = form("Wait ms", existing?.durationMs?.toString() ?: "1000")
        AlertDialog.Builder(this).setTitle("Wait").setView(box.root).setPositiveButton("Lưu") { _, _ -> saveEdited(index, MacroAction(ActionTypes.WAIT, durationMs = box.values[0]().toLong().coerceAtLeast(0))) }.setNegativeButton("Hủy", null).show()
    }

    private fun showImageDialog(uri: Uri, existing: MacroAction? = null, index: Int = -1) {
        val file = copyImage(uri) ?: return
        imageOptions(file.absolutePath, existing, index)
    }

    private fun imageOptions(path: String, existing: MacroAction?, index: Int) {
        val box = form("Confidence %", ((existing?.confidence ?: .82f) * 100).toInt().toString(), "Timeout ms", existing?.timeoutMs?.toString() ?: "2500", "Left (0=full)", existing?.regionLeft?.toString() ?: "0", "Top", existing?.regionTop?.toString() ?: "0", "Right (0=full)", existing?.regionRight?.toString() ?: "0", "Bottom (0=full)", existing?.regionBottom?.toString() ?: "0")
        AlertDialog.Builder(this).setTitle("Image Match").setMessage("Tìm ảnh rồi click vào tâm. Vùng = 0 nghĩa là toàn màn hình.").setView(box.root).setPositiveButton("Lưu") { _, _ ->
            val v = box.values.map { it() }
            val action = MacroAction(ActionTypes.IMAGE, delayMs = 300, imagePath = path, confidence = (v[0] / 100f).coerceIn(.55f, .99f), timeoutMs = v[1].toLong().coerceIn(300, 15000), regionLeft = v[2].toInt(), regionTop = v[3].toInt(), regionRight = v[4].toInt(), regionBottom = v[5].toInt())
            val oldPath = if (index >= 0) ActionRepository.loadActions(this).getOrNull(index)?.imagePath else null
            saveEdited(index, action)
            if (oldPath != null && oldPath != path) File(oldPath).delete()
        }.setNegativeButton("Hủy") { _, _ -> if (index < 0) File(path).delete() }.show()
    }

    private fun editAction(index: Int) {
        val a = ActionRepository.loadActions(this)[index]
        when (a.type) {
            ActionTypes.TAP, ActionTypes.DOUBLE_TAP -> showTapDialog(a.type, a, index)
            ActionTypes.LONG_PRESS -> showLongPressDialog(a, index)
            ActionTypes.SWIPE -> showSwipeDialog(a, index)
            ActionTypes.WAIT -> showWaitDialog(a, index)
            ActionTypes.IMAGE -> { pendingImageIndex = index; pickImage.launch("image/*") }
        }
    }

    private fun saveEdited(index: Int, action: MacroAction) {
        val actions = ActionRepository.loadActions(this)
        if (index in actions.indices) actions[index] = action else actions.add(action)
        ActionRepository.saveActions(this, actions); renderActions()
    }

    private fun move(index: Int, direction: Int) { val a = ActionRepository.loadActions(this); val n = index + direction; if (n !in a.indices) return; val t = a[index]; a[index] = a[n]; a[n] = t; ActionRepository.saveActions(this, a); renderActions() }
    private fun delete(index: Int) { val a = ActionRepository.loadActions(this); a.removeAt(index); ActionRepository.saveActions(this, a); renderActions() }

    private fun copyImage(uri: Uri): File? = runCatching {
        val f = File(filesDir, "template_${System.currentTimeMillis()}.png")
        contentResolver.openInputStream(uri).use { input -> requireNotNull(input); f.outputStream().use { input.copyTo(it) } }
        f
    }.getOrElse { toast("Không đọc được ảnh"); null }

    private fun setRunning(value: Boolean) {
        if (!isAccessibilityEnabled()) { toast("Bật Accessibility trước"); startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); return }
        if (value && ActionRepository.loadActions(this).isEmpty()) { toast("Macro trống"); return }
        ActionRepository.setRunning(this, value)
        sendBroadcast(Intent(AutoClickAccessibilityService.ACTION_REFRESH))
        updateStatus()
    }

    private fun updateStatus() { status.text = if (isAccessibilityEnabled()) "● Accessibility OK • ${if (ActionRepository.isRunning(this)) "Đang chạy" else "Sẵn sàng"}" else "○ Chưa bật Accessibility" }
    private fun isAccessibilityEnabled() = (getSystemService(ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager).getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any { it.resolveInfo.serviceInfo.packageName == packageName }

    private fun section(title: String, sub: String): View = TextView(this).apply { text = "$title\n$sub"; textSize = 18f; setPadding(0, 14, 0, 10) }
    private fun button(text: String, action: () -> Unit): MaterialButton = MaterialButton(this).apply { this.text = text; setOnClickListener { action() } }
    private fun button(text: String, action: () -> Unit, lp: LinearLayout.LayoutParams): MaterialButton = button(text, action).apply { layoutParams = lp }
    private fun edit(value: String, type: Int) = EditText(this).apply { setText(value); inputType = type; layoutParams = LinearLayout.LayoutParams(-1, -2) }
    private fun lpTop(dp: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp }
    private fun weightLp() = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = 3 }
    private data class Form(val root: LinearLayout, val values: List<() -> Float>)
    private fun form(vararg pairs: String): Form { val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 0, 28, 0) }; val values = mutableListOf<() -> Float>(); var i = 0; while (i < pairs.size) { val e = edit(pairs[i + 1], InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL); root.addView(TextView(this).apply { text = pairs[i] }); root.addView(e); values += { e.text.toString().toFloatOrNull() ?: 0f }; i += 2 }; return Form(root, values) }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
