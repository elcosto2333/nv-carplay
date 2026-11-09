package com.example.carlauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

class MainActivity : Activity() {

    data class AppItem(val label: String, val pkg: String, val icon: android.graphics.drawable.Drawable)
    data class Task(val name: String, val km: Int, val months: Int)

    private val grey = Color.parseColor("#8FA3B8")
    private val tasks = listOf(
        Task("Vidange + filtre à huile", 15000, 12),
        Task("Filtre à air", 30000, 24),
        Task("Filtre d'habitacle", 30000, 24),
        Task("Filtre à carburant", 60000, 0),
        Task("Liquide de frein", 0, 24),
        Task("Distribution", 120000, 72),
        Task("Contrôle technique", 0, 24)
    )
    private val shortcuts = listOf(
        listOf("carplay", "zlink", "carlink"),
        listOf("maps", "waze", "navig"),
        listOf("spotify", "music", "deezer", "radio"),
        listOf("phone", "dialer")
    )

    private lateinit var pages: FrameLayout
    private lateinit var home: View
    private lateinit var appsPage: View
    private lateinit var carPage: View
    private lateinit var grid: GridView
    private lateinit var carList: LinearLayout
    private lateinit var kmText: TextView
    private lateinit var shortcutBox: LinearLayout
    private var car3d: Car3DView? = null
    private val prefs by lazy { getSharedPreferences("car", Context.MODE_PRIVATE) }

    // ---------- Petits outils ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun glass(r: Int = 24): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(r).toFloat()
        setColor(Color.parseColor("#24FFFFFF"))
        setStroke(dp(1), Color.parseColor("#22FFFFFF"))
    }

    private fun tv(t: String, size: Float, color: Int = Color.WHITE, bold: Boolean = false) =
        TextView(this).apply {
            text = t; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = glass()
        setPadding(dp(16), dp(14), dp(16), dp(14))
    }

    private fun lp(w: Int, h: Int, weight: Float = 0f, margin: Int = 0) =
        LinearLayout.LayoutParams(w, h, weight).apply { setMargins(margin, margin, margin, margin) }

    private fun pill(label: String, onClick: () -> Unit): TextView =
        tv(label, 16f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = glass(18)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            setOnClickListener { onClick() }
        }

    private fun page(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = glass(28)
        setPadding(dp(20), dp(16), dp(20), dp(16))
    }

    private fun launch(pkg: String) {
        packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
    }

    private fun media(code: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun askNumber(title: String, initial: Int, onOk: (Int) -> Unit) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(if (initial > 0) initial.toString() else "")
        }
        AlertDialog.Builder(this).setTitle(title).setView(input)
            .setPositiveButton("OK") { _, _ -> input.text.toString().toIntOrNull()?.let(onOk) }
            .setNegativeButton("Annuler", null).show()
    }

    private fun loadApps(): List<AppItem> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val pm = packageManager
        return pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != packageName }
            .map { AppItem(it.loadLabel(pm).toString(), it.activityInfo.packageName, it.loadIcon(pm)) }
            .sortedBy { it.label.lowercase() }
    }

    // ---------- Écran ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#0E1020"), Color.parseColor("#232548"))
            )
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        root.addView(buildSidebar(), lp(dp(100), -1, 0f, dp(6)))
        pages = FrameLayout(this)
        home = buildHome(); appsPage = buildApps(); carPage = buildCar()
        pages.addView(home); pages.addView(appsPage); pages.addView(carPage)
        root.addView(pages, lp(0, -1, 1f, dp(6)))
        setContentView(root)
        show(home)
    }

    override fun onResume() {
        super.onResume()
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        refreshShortcuts()
        car3d?.onResume()
    }

    override fun onPause() {
        super.onPause()
        car3d?.onPause()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        show(home)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (home.visibility != View.VISIBLE) show(home)
    }

    private fun show(v: View) {
        for (p in listOf(home, appsPage, carPage)) {
            p.visibility = if (p === v) View.VISIBLE else View.GONE
        }
        if (v === appsPage) grid.adapter = Adapter(loadApps())
        if (v === carPage) refreshCar()
    }

    // ---------- Barre latérale ----------
    private fun buildSidebar(): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = glass(28)
            setPadding(dp(8), dp(16), dp(8), dp(12))
        }
        c.addView(TextClock(this).apply {
            format24Hour = "HH:mm"; format12Hour = "HH:mm"
            textSize = 22f; setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
        })
        shortcutBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        c.addView(shortcutBox, lp(-1, 0, 1f))
        c.addView(pill("Applis") { show(appsPage) }, lp(-1, -2))
        return c
    }

    private fun refreshShortcuts() {
        shortcutBox.removeAllViews()
        val apps = loadApps()
        val used = mutableSetOf<String>()
        for (keys in shortcuts) {
            val app = apps.firstOrNull { a ->
                a.pkg !in used && keys.any { a.label.lowercase().contains(it) || a.pkg.lowercase().contains(it) }
            } ?: continue
            used.add(app.pkg)
            val icon = ImageView(this).apply {
                setImageDrawable(app.icon)
                setOnClickListener { launch(app.pkg) }
            }
            shortcutBox.addView(icon, lp(dp(60), dp(60), 0f, dp(6)))
        }
    }

    // ---------- Accueil ----------
    private fun buildHome(): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        val left = card()
        left.addView(tv("Peugeot 207", 34f, Color.WHITE, true))
        val status = tv("Glisse le doigt pour la faire tourner", 16f, grey)
        left.addView(status)
        val car = Car3DView(this)
        car3d = car
        left.addView(car, lp(-1, 0, 1f))
        val btns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        btns.addView(pill("Entretien") { show(carPage) }, lp(0, -2, 1f, dp(4)))
        btns.addView(pill("Applis") { show(appsPage) }, lp(0, -2, 1f, dp(4)))
        btns.addView(pill("Réglages") { startActivity(Intent(Settings.ACTION_SETTINGS)) }, lp(0, -2, 1f, dp(4)))
        left.addView(btns)

        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val music = card()
        music.addView(tv("Musique", 14f, grey))
        music.addView(View(this), lp(-1, 0, 1f))
        val ctl = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        ctl.addView(pill("Préc.") { media(KeyEvent.KEYCODE_MEDIA_PREVIOUS) }, lp(0, -2, 1f, dp(4)))
        ctl.addView(pill("Lecture") { media(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }, lp(0, -2, 1f, dp(4)))
        ctl.addView(pill("Suiv.") { media(KeyEvent.KEYCODE_MEDIA_NEXT) }, lp(0, -2, 1f, dp(4)))
        music.addView(ctl)

        val dateCard = card()
        dateCard.addView(TextClock(this).apply {
            format24Hour = "EEEE d MMMM"; format12Hour = "EEEE d MMMM"
            textSize = 18f; setTextColor(grey)
        })
        dateCard.addView(TextClock(this).apply {
            format24Hour = "HH:mm"; format12Hour = "HH:mm"
            textSize = 64f; setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        })

        right.addView(music, lp(-1, 0, 1f, dp(6)))
        right.addView(dateCard, lp(-1, 0, 1f, dp(6)))
        row.addView(left, lp(0, -1, 1.4f, dp(6)))
        row.addView(right, lp(0, -1, 1f, dp(6)))
        return row
    }

    // ---------- Liste des applis ----------
    private fun buildApps(): View {
        val p = page()
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(tv("Applications", 26f, Color.WHITE, true), lp(0, -2, 1f))
        top.addView(pill("Retour") { show(home) })
        grid = GridView(this).apply {
            numColumns = 5
            verticalSpacing = dp(12)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            selector = ColorDrawable(Color.TRANSPARENT)
        }
        p.addView(top)
        p.addView(grid, lp(-1, 0, 1f, dp(4)))
        return p
    }

    inner class Adapter(private val apps: List<AppItem>) : BaseAdapter() {
        override fun getCount() = apps.size
        override fun getItem(p: Int) = apps[p]
        override fun getItemId(p: Int) = p.toLong()

        override fun getView(p: Int, convert: View?, parent: ViewGroup): View {
            val app = apps[p]
            val ctx = this@MainActivity
            val tile = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = glass(18)
                setPadding(dp(8), dp(12), dp(8), dp(12))
                setOnClickListener { launch(app.pkg) }
            }
            tile.addView(ImageView(ctx).apply { setImageDrawable(app.icon) }, LinearLayout.LayoutParams(dp(64), dp(64)))
            tile.addView(TextView(ctx).apply {
                text = app.label; textSize = 13f; maxLines = 1
                setTextColor(Color.WHITE); gravity = Gravity.CENTER
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            return tile
        }
    }

    // ---------- Carnet d'entretien ----------
    private fun buildCar(): View {
        val p = page()
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(tv("Entretien", 26f, Color.WHITE, true), lp(0, -2, 1f))
        top.addView(pill("Retour") { show(home) })

        val kmRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        kmText = tv("", 16f, grey)
        kmRow.addView(kmText, lp(0, -2, 1f))
        kmRow.addView(pill("Modifier km") {
            askNumber("Kilométrage actuel", prefs.getInt("km", 0)) { v ->
                prefs.edit().putInt("km", v).apply()
                refreshCar()
            }
        })

        carList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this)
        scroll.addView(carList)

        p.addView(top)
        p.addView(kmRow, lp(-1, -2, 0f, dp(4)))
        p.addView(scroll, lp(-1, 0, 1f))
        return p
    }

    private fun refreshCar() {
        val km = prefs.getInt("km", 0)
        kmText.text = if (km > 0) "Kilométrage : $km km" else "Kilométrage : non renseigné"
        carList.removeAllViews()
        for (t in tasks) {
            val done = prefs.getInt("done_${t.name}", -1)
            val date = prefs.getLong("date_${t.name}", 0L)
            var status = "Pas encore renseigné (touche pour noter)"
            var color = grey
            if (done >= 0) {
                val parts = mutableListOf<String>()
                var late = false
                if (t.km > 0) {
                    parts.add("prochain à ${done + t.km} km")
                    if (km > 0 && km >= done + t.km) late = true
                }
                if (t.months > 0) {
                    val m = ((System.currentTimeMillis() - date) / 2592000000L).toInt()
                    parts.add("fait il y a $m mois")
                    if (m >= t.months) late = true
                }
                status = (if (late) "À faire · " else "OK · ") + parts.joinToString(" · ")
                color = if (late) Color.parseColor("#FF6B6B") else Color.parseColor("#6BE39A")
            }
            val interval = listOfNotNull(
                if (t.km > 0) "tous les ${t.km} km" else null,
                if (t.months > 0) "${t.months} mois" else null
            ).joinToString(" / ")

            val row = card()
            row.setOnClickListener {
                askNumber("${t.name} : fait à combien de km ?", km) { v ->
                    prefs.edit()
                        .putInt("done_${t.name}", v)
                        .putLong("date_${t.name}", System.currentTimeMillis())
                        .apply()
                    refreshCar()
                }
            }
            row.addView(tv(t.name, 18f, Color.WHITE, true))
            row.addView(tv(interval, 13f, grey))
            row.addView(tv(status, 14f, color))
            carList.addView(row, lp(-1, -2, 0f, dp(5)))
        }
    }
}


// =====================================================================
//  Ta Peugeot 207 en 3D (fichier car207.bin dans app/src/main/assets)
//  Glisse le doigt pour la faire tourner. Elle tourne seule au repos.
// =====================================================================
class Car3DView(ctx: Context) : GLSurfaceView(ctx) {
    private val rend = CarRenderer(ctx)
    private var lx = 0f
    private var ly = 0f

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setZOrderOnTop(true)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setRenderer(rend)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { lx = e.x; ly = e.y; rend.dragging = true }
            MotionEvent.ACTION_MOVE -> {
                rend.yaw += (e.x - lx) * 0.4f
                rend.pitch = (rend.pitch + (e.y - ly) * 0.15f).coerceIn(2f, 40f)
                lx = e.x; ly = e.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                rend.dragging = false
                rend.releaseTime = System.currentTimeMillis()
            }
        }
        return true
    }
}

class CarRenderer(private val ctx: Context) : GLSurfaceView.Renderer {
    @Volatile var yaw = 35f
    @Volatile var pitch = 12f
    @Volatile var dragging = false
    @Volatile var releaseTime = 0L

    class Chunk(val ni: Int, val ids: IntArray, val c: FloatArray)

    private val statics = ArrayList<Chunk>()
    private val wheels = ArrayList<Chunk>()
    private var prog = 0
    private var aPos = 0
    private var aNor = 0
    private var aCol = 0
    private var uVP = 0
    private var uModel = 0
    private var uCam = 0
    private var aspect = 1.4f
    private var dist = 6f
    private var wheelAngle = 0f
    private var lastNs = 0L
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val vp = FloatArray(16)
    private val model = FloatArray(16)

    private val vs = """
        uniform mat4 uVP; uniform mat4 uModel;
        attribute vec3 aPos; attribute vec3 aNor; attribute vec4 aCol;
        varying vec3 vN; varying vec3 vP; varying vec4 vC;
        void main() {
            vec4 w = uModel * vec4(aPos, 1.0);
            vN = (uModel * vec4(aNor, 0.0)).xyz;
            vP = w.xyz; vC = aCol;
            gl_Position = uVP * w;
        }
    """
    private val fs = """
        precision mediump float;
        uniform vec3 uCam;
        varying vec3 vN; varying vec3 vP; varying vec4 vC;
        void main() {
            vec3 n = normalize(vN);
            vec3 v = normalize(uCam - vP);
            vec3 L = normalize(vec3(-0.5, 1.0, 0.6));
            float diff = max(dot(n, L), 0.0);
            vec3 col = vC.rgb;
            vec3 o = col * (0.25 + 0.75 * diff);
            if (vC.a > 0.9) {
                o = col;
            } else if (vC.a > 0.3) {
                float nd = max(dot(n, v), 0.0);
                float fres = pow(1.0 - nd, 3.0);
                vec3 r = reflect(-v, n);
                float sk = clamp(r.y * 0.5 + 0.5, 0.0, 1.0);
                vec3 sky = mix(vec3(0.03, 0.035, 0.05), vec3(0.45, 0.55, 0.75), sk);
                vec3 h = normalize(L + v);
                float spec = pow(max(dot(n, h), 0.0), 70.0);
                o += sky * (0.10 + 0.9 * fres) + vec3(spec * 0.6);
            }
            gl_FragColor = vec4(o, 1.0);
        }
    """

    private fun shader(type: Int, code: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, code)
        GLES20.glCompileShader(s)
        Log.d("Car3D", GLES20.glGetShaderInfoLog(s))
        return s
    }

    private fun load() {
        val data = ctx.assets.open("car207.bin").readBytes()
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(4)
        val nStatic = bb.int
        val nWheel = bb.int
        fun take(n: Int): ByteBuffer {
            val d = ByteBuffer.allocateDirect(n).order(ByteOrder.nativeOrder())
            d.put(data, bb.position(), n); d.position(0)
            bb.position(bb.position() + n)
            return d
        }
        fun chunk(withCenter: Boolean): Chunk {
            val c = if (withCenter) floatArrayOf(bb.float, bb.float, bb.float) else floatArrayOf(0f, 0f, 0f)
            val nv = bb.int
            val ni = bb.int
            val bufs = arrayOf(take(nv * 12), take(nv * 4), take(nv * 4), take(ni * 2))
            val ids = IntArray(4)
            GLES20.glGenBuffers(4, ids, 0)
            for (k in 0 until 3) {
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ids[k])
                GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, bufs[k].capacity(), bufs[k], GLES20.GL_STATIC_DRAW)
            }
            GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ids[3])
            GLES20.glBufferData(GLES20.GL_ELEMENT_ARRAY_BUFFER, bufs[3].capacity(), bufs[3], GLES20.GL_STATIC_DRAW)
            return Chunk(ni, ids, c)
        }
        statics.clear(); wheels.clear()
        for (i in 0 until nStatic) statics.add(chunk(false))
        for (i in 0 until nWheel) wheels.add(chunk(true))
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, shader(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(prog, shader(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(prog)
        aPos = GLES20.glGetAttribLocation(prog, "aPos")
        aNor = GLES20.glGetAttribLocation(prog, "aNor")
        aCol = GLES20.glGetAttribLocation(prog, "aCol")
        uVP = GLES20.glGetUniformLocation(prog, "uVP")
        uModel = GLES20.glGetUniformLocation(prog, "uModel")
        uCam = GLES20.glGetUniformLocation(prog, "uCam")
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        try { load() } catch (e: Exception) { Log.e("Car3D", "car207.bin introuvable", e) }
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        GLES20.glViewport(0, 0, w, h)
        aspect = w.toFloat() / maxOf(h, 1)
        Matrix.perspectiveM(proj, 0, 32f, aspect, 0.5f, 40f)
        dist = maxOf(5f, 2.6f / (tan(Math.toRadians(16.0)).toFloat() * aspect))
    }

    private fun draw(ch: Chunk, m: FloatArray) {
        GLES20.glUniformMatrix4fv(uModel, 1, false, m, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ch.ids[0])
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, 0, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ch.ids[1])
        GLES20.glVertexAttribPointer(aNor, 3, GLES20.GL_BYTE, true, 4, 0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ch.ids[2])
        GLES20.glVertexAttribPointer(aCol, 4, GLES20.GL_UNSIGNED_BYTE, true, 0, 0)
        GLES20.glBindBuffer(GLES20.GL_ELEMENT_ARRAY_BUFFER, ch.ids[3])
        GLES20.glDrawElements(GLES20.GL_TRIANGLES, ch.ni, GLES20.GL_UNSIGNED_SHORT, 0)
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val dt = if (lastNs == 0L) 0.016f else ((now - lastNs) / 1e9f).coerceAtMost(0.1f)
        lastNs = now
        if (!dragging && System.currentTimeMillis() - releaseTime > 2500) yaw += 14f * dt
        wheelAngle += 120f * dt

        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        if (statics.isEmpty()) return

        val pr = Math.toRadians(pitch.toDouble())
        val ey = 0.6f + dist * sin(pr).toFloat()
        val ez = dist * cos(pr).toFloat()
        Matrix.setLookAtM(view, 0, 0f, ey, ez, 0f, 0.6f, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0)

        GLES20.glUseProgram(prog)
        GLES20.glUniformMatrix4fv(uVP, 1, false, vp, 0)
        GLES20.glUniform3f(uCam, 0f, ey, ez)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glEnableVertexAttribArray(aNor)
        GLES20.glEnableVertexAttribArray(aCol)

        Matrix.setRotateM(model, 0, yaw, 0f, 1f, 0f)
        for (ch in statics) draw(ch, model)
        for (ch in wheels) {
            Matrix.setRotateM(model, 0, yaw, 0f, 1f, 0f)
            Matrix.translateM(model, 0, ch.c[0], ch.c[1], ch.c[2])
            Matrix.rotateM(model, 0, wheelAngle, 1f, 0f, 0f)
            draw(ch, model)
        }
    }
}
