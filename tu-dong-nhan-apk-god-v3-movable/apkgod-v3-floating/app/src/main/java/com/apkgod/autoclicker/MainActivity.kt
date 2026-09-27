package com.apkgod.autoclicker

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import java.io.File

class MainActivity : AppCompatActivity() {
    private lateinit var list: LinearLayout
    private lateinit var status: TextView
    private lateinit var repeatInput: EditText
    private var editingImageIndex = -1
    private var pendingIfImage = false

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) showImageDialog(uri, editingImageIndex) }
    private val exportFile = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> if (uri != null) runCatching { contentResolver.openOutputStream(uri)?.use { it.write(ActionRepository.exportJson(this).toByteArray()) }; toast("Đã xuất macro") }.onFailure { toast("Xuất thất bại") } }
    private val importFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) runCatching { contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }?.let { raw -> if (ActionRepository.importJson(this, raw)) renderActions() else toast("JSON không hợp lệ") } }.onFailure { toast("Nhập thất bại") } }

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContentView(buildUi()); renderActions() }
    override fun onResume() { super.onResume(); if (::status.isInitialized) updateStatus() }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 22, 20, 22) }
        val scroll = ScrollView(this); val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(content)
        content.addView(TextView(this).apply { text = "Tự động nhấn V3"; textSize = 30f; setTypeface(null, 1) })
        status = TextView(this).apply { textSize = 14f; setPadding(0, 8, 0, 14) }; content.addView(status)
        content.addView(button("⚙  Accessibility") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
        content.addView(section("Macro Editor", "Action được thực thi từ trên xuống dưới."))
        val add = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val rows = listOf(
            "Tap" to { tapDialog(ActionTypes.TAP) }, "Double Tap" to { tapDialog(ActionTypes.DOUBLE_TAP) }, "Long Press" to { tapDialog(ActionTypes.LONG_PRESS) },
            "Swipe" to { swipeDialog() }, "Wait" to { waitDialog() }, "Image Match" to { editingImageIndex = -1; pickImage.launch("image/*") },
            "IF Image" to { editingImageIndex = -1; pendingIfImage = true; pickImage.launch("image/*") }, "IF Text" to { textConditionDialog() },
            "ELSE" to { addAction(MacroAction(ActionTypes.ELSE, delayMs = 0)) }, "END IF" to { addAction(MacroAction(ActionTypes.IF_END, delayMs = 0)) },
            "LOOP" to { loopDialog() }, "END LOOP" to { addAction(MacroAction(ActionTypes.LOOP_END, delayMs = 0)) }, "STOP" to { addAction(MacroAction(ActionTypes.STOP, delayMs = 0)) }
        )
        var row: LinearLayout? = null
        rows.forEachIndexed { i, pair -> if (i % 3 == 0) { row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; add.addView(row) }; row!!.addView(button(pair.first) { pair.second() }.apply { textSize = 11f }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = 4; bottomMargin = 4 }) }
        content.addView(add)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }; content.addView(list)

        val settings = MaterialCardView(this).apply { radius = 20f; setContentPadding(14, 14, 14, 14); layoutParams = lpTop(10) }
        val s = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        s.addView(TextView(this).apply { text = "Số vòng chạy macro (0 = vô hạn)"; textSize = 17f; setTypeface(null, 1) })
        repeatInput = edit(ActionRepository.repeatCount(this).toString(), InputType.TYPE_CLASS_NUMBER); s.addView(repeatInput)
        s.addView(button("Lưu vòng chạy") { ActionRepository.setRepeatCount(this, repeatInput.text.toString().toIntOrNull() ?: 0); toast("Đã lưu") })
        settings.addView(s); content.addView(settings)

        content.addView(button("▶  BẮT ĐẦU") { ActionRepository.setRunning(this, true); updateStatus(); toast("Đã yêu cầu chạy") }, lpTop(12))
        content.addView(button("■  DỪNG") { ActionRepository.setRunning(this, false); updateStatus() })
        val io = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        io.addView(button("Xuất JSON") { exportFile.launch("macro-${System.currentTimeMillis()}.json") }, weight())
        io.addView(button("Nhập JSON") { importFile.launch(arrayOf("application/json", "text/plain")) }, weight())
        content.addView(io)
        content.addView(button("Xóa macro") { ActionRepository.clear(this); renderActions(); toast("Đã xóa") })
        content.addView(TextView(this).apply { text = "V3: Image Match + OCR + IF/ELSE + LOOP + JSON. OCR dùng ML Kit unbundled để giảm kích thước APK."; textSize = 12f; setPadding(0, 12, 0, 0) })
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f)); return root
    }

    private fun renderActions() {
        if (!::list.isInitialized) return
        list.removeAllViews(); val actions = ActionRepository.loadActions(this)
        if (actions.isEmpty()) { list.addView(TextView(this).apply { text = "Chưa có action."; setPadding(0, 12, 0, 12) }); return }
        actions.forEachIndexed { i, a ->
            val card = MaterialCardView(this).apply { radius = 18f; setContentPadding(14, 10, 14, 10); layoutParams = lpTop(7) }
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            box.addView(TextView(this).apply { text = "${i + 1}. ${label(a)}"; textSize = 17f; setTypeface(null, 1) })
            box.addView(TextView(this).apply { text = detail(a); textSize = 12f })
            val c = LinearLayout(this)
            c.addView(button("↑") { move(i, -1) }, weight()); c.addView(button("↓") { move(i, 1) }, weight()); c.addView(button("✎") { edit(i) }, weight()); c.addView(button("×") { delete(i) }, weight())
            box.addView(c); card.addView(box); list.addView(card)
        }
    }

    private fun label(a: MacroAction) = when (a.type) { ActionTypes.TAP -> "Tap"; ActionTypes.DOUBLE_TAP -> "Double Tap"; ActionTypes.LONG_PRESS -> "Long Press"; ActionTypes.SWIPE -> "Swipe"; ActionTypes.WAIT -> "Wait"; ActionTypes.IMAGE -> "Image Match"; ActionTypes.IF_IMAGE -> "IF Image"; ActionTypes.IF_TEXT -> "IF Text"; ActionTypes.ELSE -> "ELSE"; ActionTypes.IF_END -> "END IF"; ActionTypes.LOOP_START -> "LOOP"; ActionTypes.LOOP_END -> "END LOOP"; ActionTypes.STOP -> "STOP"; else -> a.type }
    private fun detail(a: MacroAction) = when (a.type) {
        ActionTypes.TAP, ActionTypes.DOUBLE_TAP, ActionTypes.LONG_PRESS -> "(${a.x.toInt()}, ${a.y.toInt()}) • ${a.durationMs}ms • delay ${a.delayMs}ms"
        ActionTypes.SWIPE -> "(${a.x.toInt()},${a.y.toInt()}) → (${a.x2.toInt()},${a.y2.toInt()}) • ${a.durationMs}ms"
        ActionTypes.WAIT -> "${a.durationMs}ms"; ActionTypes.IMAGE, ActionTypes.IF_IMAGE -> "${File(a.imagePath ?: "").name} • ${(a.confidence * 100).toInt()}% • timeout ${a.timeoutMs}ms"
        ActionTypes.IF_TEXT -> "contains: ${a.text}"; ActionTypes.LOOP_START -> "${a.loopCount} lần"; else -> ""
    }

    private fun tapDialog(type: String, existing: MacroAction? = null, index: Int = -1) {
        val fields = if (type == ActionTypes.LONG_PRESS) listOf("X" to (existing?.x?.toInt()?.toString() ?: "500"), "Y" to (existing?.y?.toInt()?.toString() ?: "500"), "Hold ms" to (existing?.durationMs?.toString() ?: "800"), "Delay ms" to (existing?.delayMs?.toString() ?: "300")) else listOf("X" to (existing?.x?.toInt()?.toString() ?: "500"), "Y" to (existing?.y?.toInt()?.toString() ?: "500"), "Delay ms" to (existing?.delayMs?.toString() ?: "300"))
        val f = form(fields); AlertDialog.Builder(this).setTitle(label(MacroAction(type))).setView(f.root).setPositiveButton("Lưu") { _, _ -> val v = f.values.map { it() }; val a = if (type == ActionTypes.LONG_PRESS) MacroAction(type, v[0], v[1], durationMs = v[2].toLong().coerceAtLeast(500), delayMs = v[3].toLong().coerceAtLeast(0)) else MacroAction(type, v[0], v[1], delayMs = v[2].toLong().coerceAtLeast(0)); saveEdited(index, a) }.setNegativeButton("Hủy", null).show()
    }

    private fun swipeDialog(existing: MacroAction? = null, index: Int = -1) {
        val f = form(listOf("X1" to (existing?.x?.toInt()?.toString() ?: "300"), "Y1" to (existing?.y?.toInt()?.toString() ?: "800"), "X2" to (existing?.x2?.toInt()?.toString() ?: "700"), "Y2" to (existing?.y2?.toInt()?.toString() ?: "800"), "Duration ms" to (existing?.durationMs?.toString() ?: "500"), "Delay ms" to (existing?.delayMs?.toString() ?: "300")))
        AlertDialog.Builder(this).setTitle("Swipe").setView(f.root).setPositiveButton("Lưu") { _, _ -> val v=f.values.map{it()}; saveEdited(index, MacroAction(ActionTypes.SWIPE,v[0],v[1],v[2],v[3],v[4].toLong().coerceAtLeast(80),v[5].toLong().coerceAtLeast(0))) }.setNegativeButton("Hủy",null).show()
    }
    private fun waitDialog(existing: MacroAction?=null,index:Int=-1){ val f=form(listOf("Wait ms" to (existing?.durationMs?.toString() ?: "1000"))); AlertDialog.Builder(this).setTitle("Wait").setView(f.root).setPositiveButton("Lưu"){_,_->saveEdited(index,MacroAction(ActionTypes.WAIT,durationMs=f.values[0]().toLong().coerceAtLeast(0)))}.setNegativeButton("Hủy",null).show() }
    private fun loopDialog(existing: MacroAction?=null,index:Int=-1){ val f=form(listOf("Số lần" to (existing?.loopCount?.toString()?:"2"))); AlertDialog.Builder(this).setTitle("Loop").setView(f.root).setPositiveButton("Lưu"){_,_->saveEdited(index,MacroAction(ActionTypes.LOOP_START,loopCount=f.values[0]().toInt().coerceIn(1,999999)))}.setNegativeButton("Hủy",null).show() }
    private fun textConditionDialog(existing:MacroAction?=null,index:Int=-1){ val e=EditText(this).apply{hint="Ví dụ: PLAY";setText(existing?.text?:"")}; AlertDialog.Builder(this).setTitle("IF Text").setView(e).setPositiveButton("Lưu"){_,_->saveEdited(index,MacroAction(ActionTypes.IF_TEXT,text=e.text.toString().trim(),timeoutMs=4000))}.setNegativeButton("Hủy",null).show() }

    private fun showImageDialog(uri: Uri, index: Int) { val file=copyImage(uri) ?: return; val targetType = if (pendingIfImage) ActionTypes.IF_IMAGE else null; pendingIfImage = false; imageDialog(file.absolutePath,index,targetType) }
    private fun imageDialog(path:String,index:Int,targetType:String? = null){ val f=form(listOf("Confidence %" to "82","Timeout ms" to "3000","Left" to "0","Top" to "0","Right (0=full)" to "0","Bottom (0=full)" to "0")); AlertDialog.Builder(this).setTitle(if(index<0) "Image Match" else "Sửa Image").setView(f.root).setPositiveButton("Lưu"){_,_->val v=f.values.map{it()}; val type=targetType ?: if(index>=0 && ActionRepository.loadActions(this).getOrNull(index)?.type==ActionTypes.IF_IMAGE) ActionTypes.IF_IMAGE else ActionTypes.IMAGE; saveEdited(index,MacroAction(type,imagePath=path,confidence=(v[0]/100f).coerceIn(.55f,.99f),timeoutMs=v[1].toLong().coerceIn(300,30000),regionLeft=v[2].toInt(),regionTop=v[3].toInt(),regionRight=v[4].toInt(),regionBottom=v[5].toInt()))}.setNegativeButton("Hủy",null).show() }

    private fun edit(i:Int){ val a=ActionRepository.loadActions(this)[i]; when(a.type){ActionTypes.TAP,ActionTypes.DOUBLE_TAP,ActionTypes.LONG_PRESS->tapDialog(a.type,a,i);ActionTypes.SWIPE->swipeDialog(a,i);ActionTypes.WAIT->waitDialog(a,i);ActionTypes.IMAGE,ActionTypes.IF_IMAGE->{editingImageIndex=i;pickImage.launch("image/*")};ActionTypes.IF_TEXT->textConditionDialog(a,i);ActionTypes.LOOP_START->loopDialog(a,i);else->toast("Action này không cần chỉnh sửa")} }
    private fun addAction(a:MacroAction){val x=ActionRepository.loadActions(this);x.add(a);ActionRepository.saveActions(this,x);renderActions()}
    private fun saveEdited(i:Int,a:MacroAction){val x=ActionRepository.loadActions(this);if(i<0)x.add(a)else x[i]=a;ActionRepository.saveActions(this,x);renderActions()}
    private fun move(i:Int,d:Int){val x=ActionRepository.loadActions(this);val j=i+d;if(j !in x.indices)return;val t=x.removeAt(i);x.add(j,t);ActionRepository.saveActions(this,x);renderActions()}
    private fun delete(i:Int){val x=ActionRepository.loadActions(this);if(i in x.indices){x.removeAt(i);ActionRepository.saveActions(this,x);renderActions()}}
    private fun copyImage(uri:Uri):File?=runCatching{val f=File(filesDir,"template_${System.currentTimeMillis()}.png");contentResolver.openInputStream(uri)!!.use{input->f.outputStream().use{input.copyTo(it)}};f}.getOrElse{toast("Không đọc được ảnh");null}

    private data class Form(val root:LinearLayout,val values:List<()->Float>)
    private fun form(fields:List<Pair<String,String>>):Form{val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(24,0,24,0)};val es=fields.map{(hint,value)->EditText(this).apply{this.hint=hint;setText(value);inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL;root.addView(this,LinearLayout.LayoutParams(-1,58))}};return Form(root,es.map{{it.text.toString().toFloatOrNull()?:0f}})}
    private fun button(t:String,click:()->Unit)=MaterialButton(this).apply{text=t;setOnClickListener{click()};isAllCaps=false}
    private fun edit(v:String,input:Int)=EditText(this).apply{setText(v);inputType=input}
    private fun section(a:String,b:String)=TextView(this).apply{text="$a\n$b";textSize=16f;setPadding(0,16,0,10)}
    private fun lpTop(v:Int)=LinearLayout.LayoutParams(-1,-2).apply{topMargin=v}
    private fun weight()=LinearLayout.LayoutParams(0,-2,1f).apply{marginEnd=4}
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_SHORT).show()
    private fun updateStatus(){status.text=if(ActionRepository.isRunning(this))"● Đang chạy macro" else "○ Đang dừng"}
}
