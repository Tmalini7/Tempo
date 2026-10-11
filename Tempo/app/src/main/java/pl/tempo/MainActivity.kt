package pl.tempo

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    private lateinit var prefs: SharedPreferences
    private lateinit var speedTv: TextView
    private lateinit var rateTv: TextView
    private lateinit var trackTv: TextView
    private lateinit var startBtn: Button
    private lateinit var sliders: LinearLayout
    private var mode = "walk"

    private fun tv(text: String, size: Float, color: String = "#F3ECFF"): TextView =
        TextView(this).apply {
            this.text = text; textSize = size; setTextColor(Color.parseColor(color))
            setPadding(0, 12, 0, 4)
        }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = getSharedPreferences("tempo", MODE_PRIVATE)
        mode = prefs.getString("mode", "walk") ?: "walk"

        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(40, 80, 40, 60) }
        setContentView(ScrollView(this).apply { setBackgroundColor(Color.parseColor("#2A1F3D")); addView(root) })

        speedTv = tv("0.0 km/h", 44f, "#C6E86B")
        rateTv = tv("Tempo: 1.00×", 18f)
        trackTv = tv("Nie wybrano plików", 15f, "#B5A6D1")
        root.addView(speedTv); root.addView(rateTv); root.addView(trackTv)

        root.addView(Button(this).apply {
            text = "Wybierz pliki audio"
            setOnClickListener {
                val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "audio/*"
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }
                startActivityForResult(i, 10)
            }
        })
        startBtn = Button(this).apply { text = "Start"; setOnClickListener { onStartStop() } }
        root.addView(startBtn)

        root.addView(tv("Tryb", 15f, "#B5A6D1"))
        root.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, arrayOf("Spacer", "Samochód"))
            setSelection(if (mode == "car") 1 else 0)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val m = if (pos == 1) "car" else "walk"
                    if (m != mode) { mode = m; prefs.edit().putString("mode", m).apply(); rebuild() }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        })
        sliders = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(sliders)
        rebuild()

        val n = (prefs.getString("playlist", "") ?: "").split("\n").count { it.isNotBlank() }
        if (n > 0) trackTv.text = "Wybrano plików: $n"
    }

    private fun rebuild() {
        sliders.removeAllViews()
        val car = mode == "car"
        addSlider("Prędkość normalna (1×)", "normal", 1f, if (car) 160f else 15f, 1f, "km/h")
        addSlider("Prędkość „zwolniona”", "slow", 0f, if (car) 150f else 14f, 1f, "km/h")
        addSlider("Tempo przy zwolnieniu", "slowRate", 0.25f, 1f, 0.05f, "×")
        addSlider("Tempo maksymalne", "maxRate", 1f, 2.5f, 0.05f, "×")
    }

    private fun addSlider(lbl: String, key: String, min: Float, max: Float, step: Float, unit: String) {
        val label = tv("", 15f)
        val sb = SeekBar(this)
        sb.max = Math.round((max - min) / step)
        val cur = Cfg.get(prefs, mode, key)
        sb.progress = Math.round((cur - min) / step)
        fun txt(v: Float) = "$lbl: " + (if (step < 1f) "%.2f".format(v) else v.toInt().toString()) + " $unit"
        label.text = txt(cur)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                val v = min + p * step
                prefs.edit().putFloat("${mode}_$key", v).apply()
                label.text = txt(v)
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        sliders.addView(label); sliders.addView(sb)
    }

    private fun onStartStop() {
        if (PlayerService.running) {
            startService(Intent(this, PlayerService::class.java).setAction("stop"))
            startBtn.text = "Start"
            return
        }
        val need = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) startPlayer()
        else requestPermissions(need.toTypedArray(), 1)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) startPlayer()
        else Toast.makeText(this, "Bez zgody na lokalizację aplikacja nie zmierzy prędkości", Toast.LENGTH_LONG).show()
    }

    private fun startPlayer() {
        if ((prefs.getString("playlist", "") ?: "").isBlank()) {
            Toast.makeText(this, "Najpierw wybierz pliki audio", Toast.LENGTH_SHORT).show(); return
        }
        startForegroundService(Intent(this, PlayerService::class.java).setAction("start"))
        startBtn.text = "Stop"
    }

    @Deprecated("fine for Activity")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != 10 || res != RESULT_OK || data == null) return
        val uris = mutableListOf<Uri>()
        data.clipData?.let { for (i in 0 until it.itemCount) uris.add(it.getItemAt(i).uri) }
        data.data?.let { if (uris.isEmpty()) uris.add(it) }
        uris.forEach {
            try { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (e: Exception) { }
        }
        prefs.edit().putString("playlist", uris.joinToString("\n")).apply()
        trackTv.text = "Wybrano plików: ${uris.size}"
        if (PlayerService.running)
            startService(Intent(this, PlayerService::class.java).setAction("reload"))
    }

    override fun onResume() {
        super.onResume()
        startBtn.text = if (PlayerService.running) "Stop" else "Start"
        PlayerService.onUpdate = { v, r, t ->
            runOnUiThread {
                speedTv.text = "%.1f km/h".format(v)
                rateTv.text = "Tempo: %.2f×".format(r)
                if (t.isNotEmpty()) trackTv.text = t
            }
        }
    }

    override fun onPause() {
        PlayerService.onUpdate = null
        super.onPause()
    }
}
