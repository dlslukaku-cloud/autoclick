package com.apkgod.autoclicker.service
import kotlin.math.roundToInt

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.*
import android.graphics.*
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ImageView
import android.widget.Toast
import com.apkgod.autoclicker.*
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.vision.common.InputImage
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.*

class AutoClickAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val screenshotExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val ocrExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var worker: Thread? = null
    @Volatile private var running = false
    private var overlay: LinearLayout? = null
    private var overlayButton: TextView? = null
    private var editorPanel: LinearLayout? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private val receiver = object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) { refresh() } }
    private val actionRefresh = "com.apkgod.autoclicker.REFRESH"

    override fun onServiceConnected() {
        super.onServiceConnected()
        showOverlay()
        if (Build.VERSION.SDK_INT >= 30) runCatching { registerReceiverCompat() }
        refresh()
    }
    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}
    override fun onInterrupt() { stopMacro() }

    private fun registerReceiverCompat() { if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, IntentFilter(actionRefresh), Context.RECEIVER_NOT_EXPORTED) else @Suppress("DEPRECATION") run { registerReceiver(receiver, IntentFilter(actionRefresh)) } }
    private fun refresh() { if (ActionRepository.isRunning(this)) startMacro() else stopMacro() }

    private fun startMacro() {
        if (running) return
        val actions = ActionRepository.loadActions(this)
        if (actions.isEmpty()) { ActionRepository.setRunning(this, false); return }
        running = true; overlayButton?.text = "■"
        worker = Thread { runProgram() }.also { it.start() }
    }

    private fun runProgram() {
        var outer = 0
        val outerMax = ActionRepository.repeatCount(this)
        try {
            while (running && (outerMax == 0 || outer < outerMax)) {
                executeProgram(ActionRepository.loadActions(this))
                outer++
            }
        } finally {
            running = false; ActionRepository.setRunning(this, false)
            handler.post { overlayButton?.text = "▶" }
        }
    }

    private data class LoopFrame(val start: Int, var remaining: Int)

    private fun executeProgram(actions: List<MacroAction>) {
        val loops = ArrayDeque<LoopFrame>()
        var pc = 0
        while (running && pc < actions.size) {
            val a = actions[pc]
            when (a.type) {
                ActionTypes.TAP -> tap(a.x, a.y, 45)
                ActionTypes.DOUBLE_TAP -> { tap(a.x,a.y,45); sleep(90); tap(a.x,a.y,45) }
                ActionTypes.LONG_PRESS -> tap(a.x,a.y,a.durationMs.coerceAtLeast(500))
                ActionTypes.SWIPE -> swipe(a.x,a.y,a.x2,a.y2,a.durationMs.coerceIn(80,15000))
                ActionTypes.WAIT -> sleep(a.durationMs)
                ActionTypes.IMAGE -> findAndClick(a)
                ActionTypes.IF_IMAGE -> if (!findImage(a)) pc = skipIf(pc, actions)
                ActionTypes.IF_TEXT -> if (!findText(a.text ?: "", a.timeoutMs)) pc = skipIf(pc, actions)
                ActionTypes.ELSE -> pc = skipToIfEnd(pc, actions)
                ActionTypes.IF_END -> Unit
                ActionTypes.LOOP_START -> loops.addLast(LoopFrame(pc, a.loopCount))
                ActionTypes.LOOP_END -> if (loops.isNotEmpty()) { val f=loops.last(); f.remaining--; if (f.remaining > 0) pc=f.start else loops.removeLast() }
                ActionTypes.STOP -> return
            }
            sleep(a.delayMs)
            pc++
        }
    }

    private fun skipIf(index:Int, actions:List<MacroAction>):Int { var depth=0; for(i in index+1 until actions.size){when(actions[i].type){ActionTypes.IF_IMAGE,ActionTypes.IF_TEXT->depth++;ActionTypes.IF_END->{if(depth==0)return i;depth--};ActionTypes.ELSE->if(depth==0)return i}};return actions.lastIndex }
    private fun skipToIfEnd(index:Int, actions:List<MacroAction>):Int { var depth=0; for(i in index+1 until actions.size){when(actions[i].type){ActionTypes.IF_IMAGE,ActionTypes.IF_TEXT->depth++;ActionTypes.IF_END->{if(depth==0)return i;depth--}}};return actions.lastIndex }

    private fun tap(x:Float,y:Float,duration:Long){val p=Path().apply{moveTo(x,y)};dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p,0,duration)).build(),null,null)}
    private fun swipe(x1:Float,y1:Float,x2:Float,y2:Float,duration:Long){val p=Path().apply{moveTo(x1,y1);lineTo(x2,y2)};dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p,0,duration)).build(),null,null)}

    private fun findAndClick(a:MacroAction){findImagePoint(a)?.let{tap(it.first,it.second,45)}}
    private fun findImage(a:MacroAction):Boolean=findImagePoint(a)!=null
    private fun findImagePoint(a:MacroAction):Pair<Float,Float>? {
        if(Build.VERSION.SDK_INT<30) return null
        val template=BitmapFactory.decodeFile(a.imagePath ?: return null) ?: return null
        val deadline=System.currentTimeMillis()+a.timeoutMs
        try { while(running && System.currentTimeMillis()<deadline){val screen=screenshot(); if(screen!=null){val p=TemplateMatcher.find(screen,template,a);screen.recycle();if(p!=null)return p};sleep(260)} } finally { template.recycle() }
        return null
    }

    private fun findText(target:String,timeout:Long):Boolean {
        if(Build.VERSION.SDK_INT<30 || target.isBlank()) return false
        val deadline=System.currentTimeMillis()+timeout
        val recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        try { while(running && System.currentTimeMillis()<deadline){val b=screenshot();if(b!=null){val latch=CountDownLatch(1);var hit=false;ocrExecutor.execute{recognizer.process(InputImage.fromBitmap(b,0)).addOnSuccessListener{hit=it.text.contains(target,ignoreCase=true);latch.countDown()}.addOnFailureListener{latch.countDown()}};latch.await(2500,TimeUnit.MILLISECONDS);b.recycle();if(hit)return true};sleep(400)} } finally { recognizer.close() }
        return false
    }

    private fun screenshot():Bitmap? {
        if(Build.VERSION.SDK_INT<30)return null
        val latch=CountDownLatch(1);var result:Bitmap?=null
        runCatching { takeScreenshot(Display.DEFAULT_DISPLAY,screenshotExecutor,object:TakeScreenshotCallback{override fun onSuccess(s:ScreenshotResult){runCatching{val buffer:HardwareBuffer=s.hardwareBuffer;val hw=Bitmap.wrapHardwareBuffer(buffer,s.colorSpace);result=hw?.copy(Bitmap.Config.ARGB_8888,false);hw?.recycle();buffer.close()};latch.countDown()};override fun onFailure(errorCode:Int){latch.countDown()}}) }.onFailure { latch.countDown() }
        latch.await(2200,TimeUnit.MILLISECONDS);return result
    }

    private fun sleep(ms:Long){try{Thread.sleep(ms.coerceAtLeast(0))}catch(_:InterruptedException){Thread.currentThread().interrupt()}}

    private fun showOverlay(){
        if(overlay!=null)return
        val wm=getSystemService(WINDOW_SERVICE) as WindowManager
        val bubble=TextView(this).apply{
            text="✦"; textSize=22f; gravity=Gravity.CENTER; setTextColor(Color.WHITE);
            background=roundBg(0xFF6C4CF1.toInt(),28f); elevation=12f; contentDescription="APK GOD floating editor"
        }
        val size=62
        val p=WindowManager.LayoutParams(size,size,WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.TRANSLUCENT).apply{
            gravity=Gravity.TOP or Gravity.START; x=18; y=220
        }
        bubbleParams=p
        var downX=0f; var downY=0f; var startX=0; var startY=0; var moved=false
        bubble.setOnTouchListener { v,e ->
            when(e.actionMasked){
                MotionEvent.ACTION_DOWN->{downX=e.rawX;downY=e.rawY;startX=p.x;startY=p.y;moved=false;true}
                MotionEvent.ACTION_MOVE->{val dx=(e.rawX-downX).toInt();val dy=(e.rawY-downY).toInt();if(abs(dx)+abs(dy)>10)moved=true;p.x=startX+dx;p.y=startY+dy;runCatching{wm.updateViewLayout(v,p)};true}
                MotionEvent.ACTION_UP->{if(!moved)toggleEditorPanel(wm);true}
                else->false
            }
        }
        runCatching{wm.addView(bubble,p)}.onSuccess{overlay=bubble;overlayButton=bubble}
    }

    private fun toggleEditorPanel(wm:WindowManager){
        editorPanel?.let{runCatching{wm.removeView(it)};editorPanel=null;return}
        val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(14,14,14,14);background=roundBg(0xF21B1B27.toInt(),22f);elevation=18f}
        fun actionButton(label:String,onClick:()->Unit):TextView=TextView(this).apply{
            text=label;textSize=14f;setTextColor(Color.WHITE);gravity=Gravity.CENTER_VERTICAL;setPadding(18,12,18,12);
            background=roundBg(0xFF2A2A3A.toInt(),16f);setOnClickListener{onClick()};
            panel.addView(this,LinearLayout.LayoutParams(210,52).apply{bottomMargin=7})
        }
        actionButton("＋ Tap tại vị trí bóng"){
            val bp=bubbleParams; if(bp!=null){
                val dm=resources.displayMetrics; val x=bp!!.x+31f; val y=bp!!.y+31f
                val list=ActionRepository.loadActions(this).toMutableList(); list.add(MacroAction(ActionTypes.TAP,x,y,delayMs=300)); ActionRepository.saveActions(this,list); toast("Đã thêm Tap: ${x.toInt()}, ${y.toInt()}")
            }
        }
        actionButton("🖼  Thêm Image Match"){
            val i=Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("open_image_action",true);startActivity(i);closeEditorPanel(wm)
        }
        actionButton(if(running) "■  Dừng macro" else "▶  Chạy macro"){
            if(running){ActionRepository.setRunning(this,false);stopMacro()}else{ActionRepository.setRunning(this,true);startMacro()};closeEditorPanel(wm)
        }
        actionButton("✎  Mở trình chỉnh sửa"){
            startActivity(Intent(this,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP));closeEditorPanel(wm)
        }
        actionButton("×  Đóng"){closeEditorPanel(wm)}
        val bp=bubbleParams?:return; val pp=WindowManager.LayoutParams(230,wrapContent(),WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,PixelFormat.TRANSLUCENT).apply{gravity=Gravity.TOP or Gravity.START;x=(bp.x+68).coerceAtMost(resources.displayMetrics.widthPixels-240);y=bp.y}
        runCatching{wm.addView(panel,pp)}.onSuccess{editorPanel=panel}
    }

    private fun closeEditorPanel(wm:WindowManager){editorPanel?.let{runCatching{wm.removeView(it)}};editorPanel=null}
    private fun wrapContent()=WindowManager.LayoutParams.WRAP_CONTENT
    private fun roundBg(color:Int,radius:Float)=GradientDrawable().apply{setColor(color);cornerRadius=radius*resources.displayMetrics.density}
    private fun toast(s:String)=handler.post{Toast.makeText(this,s,Toast.LENGTH_SHORT).show()}
    private fun stopMacro(){running=false;worker?.interrupt();worker=null;handler.post{overlayButton?.text="✦"}}
    private fun removeOverlay(){val wm=getSystemService(WINDOW_SERVICE) as WindowManager;closeEditorPanel(wm);overlay?.let{runCatching{wm.removeView(it)}};overlay=null;overlayButton=null}
    
    /**
     * Enables/disables the visual editor for TAP actions.
     *
     * Every TAP action is represented by a small draggable marker.
     * Moving a marker updates the action coordinates immediately.
     */
    fun setEditMode(enabled: Boolean) {
        editMode = enabled
        if (enabled) {
            showTapMarkers()
        } else {
            hideTapMarkers()
        }
    }

    fun toggleEditMode() {
        setEditMode(!editMode)
    }

    private fun markerParams(x: Int, y: Int): WindowManager.LayoutParams {
        val size = dp(42)
        return WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = (x - size / 2).coerceAtLeast(0)
            this.y = (y - size / 2).coerceAtLeast(0)
        }
    }

    private fun createTapMarker(actionIndex: Int, x: Int, y: Int): View {
        val marker = TextView(this).apply {
            text = "✚"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            contentDescription = "Điểm click ${actionIndex + 1}"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(98, 0, 238))
                setStroke(dp(2), Color.WHITE)
            }
        }

        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var moved = false

        marker.setOnTouchListener { v, event ->
            val lp = v.layoutParams as WindowManager.LayoutParams
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    moved = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).toInt()
                    val dy = (event.rawY - downRawY).toInt()
                    if (kotlin.math.abs(dx) > dp(3) || kotlin.math.abs(dy) > dp(3)) {
                        moved = true
                    }

                    lp.x = startX + dx
                    lp.y = startY + dy
                    windowManager.updateViewLayout(v, lp)
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moved) {
                        val centerX = lp.x + v.width / 2
                        val centerY = lp.y + v.height / 2
                        updateTapActionPosition(actionIndex, centerX, centerY)
                        Toast.makeText(
                            this,
                            "Điểm ${actionIndex + 1}: X=$centerX, Y=$centerY",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    true
                }

                else -> false
            }
        }

        return marker
    }

    private fun updateTapActionPosition(actionIndex: Int, x: Int, y: Int) {
        // Repository API is intentionally defensive: update only if the indexed action
        // is still a TAP action. This prevents stale markers from corrupting other actions.
        try {
            actionRepository.updateTapCoordinates(actionIndex, x, y)
        } catch (_: Throwable) {
            // If the current repository implementation doesn't expose the optional
            // mutation API, keep the visual marker usable without crashing the service.
        }
    }

    private fun showTapMarkers() {
        hideTapMarkers()
        val actions = actionRepository.getActions()
        actions.forEachIndexed { index, action ->
            if (action.type.equals("tap", ignoreCase = true)) {
                val x = action.x
                val y = action.y
                val marker = createTapMarker(index, x, y)
                editMarkers[index] = marker
                windowManager.addView(marker, markerParams(x, y))
            }
        }
        editMarkersVisible = true
    }

    private fun hideTapMarkers() {
        editMarkers.values.toList().forEach { marker ->
            runCatching { windowManager.removeView(marker) }
        }
        editMarkers.clear()
        editMarkersVisible = false
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    override fun onDestroy(){stopMacro();screenshotExecutor.shutdownNow();ocrExecutor.shutdownNow();removeOverlay();runCatching{unregisterReceiver(receiver)};super.onDestroy()}

    private object TemplateMatcher {
        private data class Gray(val w:Int,val h:Int,val p:IntArray)
        fun find(screen:Bitmap,template:Bitmap,a:MacroAction):Pair<Float,Float>?{if(template.width<6||template.height<6||template.width>screen.width||template.height>screen.height)return null;val left=a.regionLeft.coerceIn(0,screen.width-template.width);val top=a.regionTop.coerceIn(0,screen.height-template.height);val right=if(a.regionRight>left)min(a.regionRight,screen.width-template.width)else screen.width-template.width;val bottom=if(a.regionBottom>top)min(a.regionBottom,screen.height-template.height)else screen.height-template.height;val tw=min(28,template.width);val th=min(28,template.height);val tpl=gray(template,tw,th);val sx=template.width.toFloat()/tw;val sy=template.height.toFloat()/th;val step=max(6,min(template.width,template.height)/7);var best=-1.0;var bx=-1;var by=-1;var y=top;while(y<=bottom){var x=left;while(x<=right){val score=similarity(screen,x,y,tpl,sx,sy);if(score>best){best=score;bx=x;by=y};x+=step};y+=step};if(bx<0||best<a.confidence)return null;var rx=bx;var ry=by;var refined=best;for(yy in max(top,by-step)..min(bottom,by+step))for(xx in max(left,bx-step)..min(right,bx+step)){val score=similarity(screen,xx,yy,tpl,sx,sy);if(score>refined){refined=score;rx=xx;ry=yy}};return if(refined>=a.confidence)Pair(rx+template.width/2f,ry+template.height/2f)else null}
        private fun similarity(screen:Bitmap,x:Int,y:Int,tpl:Gray,sx:Float,sy:Float):Double{var mse=0.0;var n=0;for(j in 0 until tpl.h)for(i in 0 until tpl.w){val c=screen.getPixel(min(screen.width-1,x+(i*sx).toInt()),min(screen.height-1,y+(j*sy).toInt()));val g=.299*Color.red(c)+.587*Color.green(c)+.114*Color.blue(c);val d=g-tpl.p[j*tpl.w+i];mse+=d*d;n++};return 1.0-(mse/max(1,n))/65025.0}
        private fun gray(b:Bitmap,w:Int,h:Int):Gray{val p=IntArray(w*h);for(y in 0 until h)for(x in 0 until w){val c=b.getPixel(min(b.width-1,x*b.width/w),min(b.height-1,y*b.height/h));p[y*w+x]=(.299*Color.red(c)+.587*Color.green(c)+.114*Color.blue(c)).toInt()};return Gray(w,h,p)}
    }
}
