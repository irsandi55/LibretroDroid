package com.swordfish.libretrodroid

import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.android.libretrodroid.R
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipFile

class ZeroTeamActivity : AppCompatActivity() {
    private var retroView: GLRetroView? = null
    private var isEditingControls = false
    private var controlScale = 1.0f
    private var controlOpacity = 0.88f
    private var controlGroups: List<ControlGroup> = emptyList()
    private lateinit var controlsSettingsButton: Button
    private val controlPrefs by lazy {
        getSharedPreferences("zero_team_controls", MODE_PRIVATE)
    }

    private data class ControlGroup(
        val container: FrameLayout,
        val content: View,
        val dragHandle: TextView,
        val preferenceKey: String,
        val defaultGravity: Int,
        val defaultLeftMargin: Int,
        val defaultRightMargin: Int,
        val defaultBottomMargin: Int,
        var dragOffsetX: Float = 0f,
        var dragOffsetY: Float = 0f
    )

    private val romPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) importAndLaunch(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersiveMode()
        showStartScreen()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) applyImmersiveMode()
    }

    private fun applyImmersiveMode() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
    }

    private fun showStartScreen(message: String = "Pilih arsip ROM zeroteam.zip untuk mulai bermain.") {
        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(Color.rgb(15, 18, 24))
        }

        val title = TextView(this).apply {
            text = "ZERO TEAM"
            textSize = 30f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        val description = TextView(this).apply {
            text = message
            textSize = 15f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(20))
        }

        val chooseButton = Button(this).apply {
            text = "PILIH ROM"
            setOnClickListener {
                romPicker.launch(
                    arrayOf(
                        "application/zip",
                        "application/x-zip-compressed",
                        "application/octet-stream"
                    )
                )
            }
        }

        screen.addView(title)
        screen.addView(description)
        screen.addView(
            chooseButton,
            LinearLayout.LayoutParams(dp(190), dp(54))
        )
        setContentView(screen)
    }

    private fun importAndLaunch(uri: Uri) {
        try {
            val incoming = File(cacheDir, "zeroteam-import.zip")
            val input = contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("File tidak bisa dibaca.")

            input.use { source ->
                incoming.outputStream().use { target ->
                    source.copyTo(target)
                }
            }

            validateRomArchive(incoming)

            val rom = File(filesDir, "zeroteam.zip")
            incoming.copyTo(rom, overwrite = true)
            incoming.delete()
            launchGame(rom)
        } catch (error: Exception) {
            File(cacheDir, "zeroteam-import.zip").delete()
            showStartScreen(
                "ROM tidak dapat digunakan: ${error.message ?: "format tidak valid"}"
            )
        }
    }

    private fun validateRomArchive(file: File) {
        val expected = linkedMapOf(
            "copx-d2.u0313.6n" to 0xA6732FF9L,
            "musha_back-1.u075.4s" to 0x8B7F9219L,
            "musha_back-2.u0714.2s" to 0xCE61C952L,
            "musha_obj-1.u0811.6f" to 0x45BE8029L,
            "musha_obj-2.u082.5f" to 0xCB61C19DL,
            "seibu__1.u024.5k" to 0x25AA5BA4L,
            "seibu__2.u025.6l" to 0x54F3D359L,
            "seibu__3.u023.6k" to 0xEC79A12BL,
            "seibu__4.u026.5l" to 0xA017B8D0L,
            "seibu__5.u1110.5b" to 0x7EC1FBC3L,
            "seibu__6.u105.4a" to 0x48BE32B1L,
            "seibu__7.u072.5s" to 0x9F6AA0F0L,
            "seibu__8.u077.5r" to 0x68F7DDDCL
        )

        ZipFile(file).use { zip ->
            for ((name, expectedCrc) in expected) {
                val entry = zip.getEntry(name)
                    ?: throw IllegalArgumentException("File ROM kurang: $name")

                val checksum = CRC32()
                zip.getInputStream(entry).use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        checksum.update(buffer, 0, count)
                    }
                }

                if (checksum.value != expectedCrc) {
                    throw IllegalArgumentException("Checksum ROM tidak cocok: $name")
                }
            }
        }
    }

    private fun launchGame(rom: File) {
        val core = File(applicationInfo.nativeLibraryDir, "libfbneo_libretro_android.so")
        if (!core.isFile) {
            throw IllegalStateException("Core FBNeo tidak ditemukan di APK.")
        }

        val systemDir = File(filesDir, "system").apply { mkdirs() }
        val savesDir = File(filesDir, "saves").apply { mkdirs() }

        val data = GLRetroViewData(this).apply {
            coreFilePath = core.absolutePath
            gameFilePath = rom.absolutePath
            systemDirectory = systemDir.absolutePath
            savesDirectory = savesDir.absolutePath
            shader = ShaderConfig.Sharp
            viewportAlignment = ViewportAlignment.CENTER
            rumbleEventsEnabled = true
            preferLowLatencyAudio = true
        }

        val root = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        val game = GLRetroView(this, data)
        retroView = game

        root.addView(
            game,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        addTouchControls(root, game)
        addControlsSettingsButton(root)
        setContentView(root)
        applyControlPreferences(root)
        lifecycle.addObserver(game)
    }

    private fun addTouchControls(root: FrameLayout, game: GLRetroView) {
        val dpad = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val middleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        dpad.addView(controlButton("UP", KeyEvent.KEYCODE_DPAD_UP, game))
        middleRow.addView(controlButton("LEFT", KeyEvent.KEYCODE_DPAD_LEFT, game))
        middleRow.addView(controlButton("DOWN", KeyEvent.KEYCODE_DPAD_DOWN, game))
        middleRow.addView(controlButton("RIGHT", KeyEvent.KEYCODE_DPAD_RIGHT, game))
        dpad.addView(middleRow)

        val dpadGroup = addControlGroup(
            root = root,
            content = dpad,
            key = "dpad",
            gravity = Gravity.START or Gravity.BOTTOM,
            leftMarginDp = 16,
            rightMarginDp = 0,
            bottomMarginDp = 14
        )

        val actionPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        val actionTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val actionBottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        actionTop.addView(controlButton("A", KeyEvent.KEYCODE_BUTTON_A, game))
        actionTop.addView(controlButton("B", KeyEvent.KEYCODE_BUTTON_B, game))
        actionTop.addView(controlButton("X", KeyEvent.KEYCODE_BUTTON_X, game))
        actionTop.addView(controlButton("Y", KeyEvent.KEYCODE_BUTTON_Y, game))
        actionBottom.addView(controlButton("COIN", KeyEvent.KEYCODE_BUTTON_SELECT, game, 76))
        actionBottom.addView(controlButton("START", KeyEvent.KEYCODE_BUTTON_START, game, 76))
        actionPanel.addView(actionTop)
        actionPanel.addView(actionBottom)

        val actionGroup = addControlGroup(
            root = root,
            content = actionPanel,
            key = "actions",
            gravity = Gravity.END or Gravity.BOTTOM,
            leftMarginDp = 0,
            rightMarginDp = 16,
            bottomMarginDp = 14
        )

        controlGroups = listOf(dpadGroup, actionGroup)
    }

    private fun addControlGroup(
        root: FrameLayout,
        content: View,
        key: String,
        gravity: Int,
        leftMarginDp: Int,
        rightMarginDp: Int,
        bottomMarginDp: Int
    ): ControlGroup {
        val container = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        container.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )

        // A dedicated visible drag handle is more reliable than placing a
        // transparent touch layer over the entire controller.
        val dragHandle = TextView(this).apply {
            text = "GESER POSISI"
            textSize = 10f
            setGravity(Gravity.CENTER)
            setTextColor(Color.BLACK)
            isClickable = true
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor(Color.rgb(255, 214, 64))
                setStroke(dp(2), Color.WHITE)
                cornerRadius = dp(7).toFloat()
            }
        }
        container.addView(
            dragHandle,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(38),
                Gravity.TOP or Gravity.CENTER_HORIZONTAL
            ).apply {
                leftMargin = dp(4)
                rightMargin = dp(4)
                topMargin = dp(4)
            }
        )

        val left = dp(leftMarginDp)
        val right = dp(rightMarginDp)
        val bottom = dp(bottomMarginDp)
        root.addView(
            container,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                gravity
            ).apply {
                leftMargin = left
                rightMargin = right
                bottomMargin = bottom
            }
        )

        val group = ControlGroup(
            container = container,
            content = content,
            dragHandle = dragHandle,
            preferenceKey = key,
            defaultGravity = gravity,
            defaultLeftMargin = left,
            defaultRightMargin = right,
            defaultBottomMargin = bottom
        )
        dragHandle.setOnTouchListener { _, event ->
            handleControlDrag(root, group, event)
        }
        return group
    }

    private fun addControlsSettingsButton(root: FrameLayout) {
        controlsSettingsButton = Button(this).apply {
            text = "SET"
            isAllCaps = false
            textSize = 10f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(190, 35, 40, 48))
            minWidth = dp(48)
            minHeight = dp(38)
            setPadding(dp(5), 0, dp(5), 0)
            setOnClickListener {
                if (isEditingControls) {
                    setControlEditMode(false)
                } else {
                    showControlSettings()
                }
            }
        }
        root.addView(
            controlsSettingsButton,
            FrameLayout.LayoutParams(
                dp(52),
                dp(40),
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = dp(8)
                rightMargin = dp(10)
            }
        )
    }

    private fun applyControlPreferences(root: FrameLayout) {
        controlScale = controlPrefs.getFloat("scale", 1.0f).coerceIn(0.65f, 1.30f)
        controlOpacity = controlPrefs.getFloat("opacity", 0.88f).coerceIn(0.25f, 1.0f)
        controlGroups.forEach { group ->
            group.content.scaleX = controlScale
            group.content.scaleY = controlScale
            group.content.alpha = controlOpacity
        }

        root.post {
            controlGroups.forEach { group ->
                val xKey = "${group.preferenceKey}_x"
                val yKey = "${group.preferenceKey}_y"
                if (controlPrefs.contains(xKey) && controlPrefs.contains(yKey)) {
                    val maxX = (root.width - group.container.width).coerceAtLeast(0)
                    val maxY = (root.height - group.container.height).coerceAtLeast(0)
                    val x = (controlPrefs.getFloat(xKey, 0f) * maxX).toInt()
                    val y = (controlPrefs.getFloat(yKey, 0f) * maxY).toInt()
                    moveGroupTo(group, x, y)
                }
            }
        }
    }

    private fun handleControlDrag(
        root: FrameLayout,
        group: ControlGroup,
        event: MotionEvent
    ): Boolean {
        if (!isEditingControls) return false
        val rootLocation = IntArray(2)
        root.getLocationOnScreen(rootLocation)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val groupLocation = IntArray(2)
                group.container.getLocationOnScreen(groupLocation)
                group.dragOffsetX = event.rawX - groupLocation[0]
                group.dragOffsetY = event.rawY - groupLocation[1]
                root.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val maxX = (root.width - group.container.width).coerceAtLeast(0)
                val maxY = (root.height - group.container.height).coerceAtLeast(0)
                val x = (event.rawX - rootLocation[0] - group.dragOffsetX)
                    .toInt().coerceIn(0, maxX)
                val y = (event.rawY - rootLocation[1] - group.dragOffsetY)
                    .toInt().coerceIn(0, maxY)
                moveGroupTo(group, x, y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                saveGroupPosition(root, group)
                root.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    private fun moveGroupTo(group: ControlGroup, x: Int, y: Int) {
        val params = group.container.layoutParams as FrameLayout.LayoutParams
        params.gravity = Gravity.TOP or Gravity.START
        params.leftMargin = x
        params.topMargin = y
        params.rightMargin = 0
        params.bottomMargin = 0
        group.container.layoutParams = params
    }

    private fun saveGroupPosition(root: FrameLayout, group: ControlGroup) {
        val maxX = (root.width - group.container.width).coerceAtLeast(1)
        val maxY = (root.height - group.container.height).coerceAtLeast(1)
        val params = group.container.layoutParams as FrameLayout.LayoutParams
        controlPrefs.edit()
            .putFloat("${group.preferenceKey}_x", params.leftMargin.toFloat() / maxX)
            .putFloat("${group.preferenceKey}_y", params.topMargin.toFloat() / maxY)
            .apply()
    }

    private fun showControlSettings() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(4))
        }

        val heading = TextView(this).apply {
            text = "Atur tombol permainan"
            textSize = 18f
            setTextColor(Color.BLACK)
        }
        val help = TextView(this).apply {
            text = "Ubah ukuran dan transparansi. Untuk memindahkan kelompok tombol, pilih Atur posisi."
            textSize = 13f
            setTextColor(Color.DKGRAY)
            setPadding(0, dp(6), 0, dp(12))
        }
        panel.addView(heading)
        panel.addView(help)

        val sizeLabel = TextView(this).apply {
            text = "Ukuran tombol: ${(controlScale * 100).toInt()}%"
            setTextColor(Color.BLACK)
        }
        val sizeSeek = SeekBar(this).apply {
            max = 130
            progress = (controlScale * 100).toInt().coerceIn(65, 130)
        }
        panel.addView(sizeLabel)
        panel.addView(sizeSeek)

        val opacityLabel = TextView(this).apply {
            text = "Transparansi: ${(controlOpacity * 100).toInt()}%"
            setTextColor(Color.BLACK)
            setPadding(0, dp(10), 0, 0)
        }
        val opacitySeek = SeekBar(this).apply {
            max = 100
            progress = (controlOpacity * 100).toInt().coerceIn(25, 100)
        }
        panel.addView(opacityLabel)
        panel.addView(opacitySeek)

        val editButton = Button(this).apply {
            text = "ATUR POSISI TOMBOL"
            setOnClickListener {
                setControlEditMode(true)
            }
        }
        val resetButton = Button(this).apply {
            text = "RESET TATA LETAK"
            setOnClickListener {
                resetControlLayout()
            }
        }
        panel.addView(editButton)
        panel.addView(resetButton)

        val dialog = AlertDialog.Builder(this)
            .setView(panel)
            .setNegativeButton("TUTUP", null)
            .create()

        sizeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                controlScale = progress.coerceIn(65, 130) / 100f
                sizeLabel.text = "Ukuran tombol: ${(controlScale * 100).toInt()}%"
                applyControlScale()
                controlPrefs.edit().putFloat("scale", controlScale).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        opacitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                controlOpacity = progress.coerceIn(25, 100) / 100f
                opacityLabel.text = "Transparansi: ${(controlOpacity * 100).toInt()}%"
                applyControlOpacity()
                controlPrefs.edit().putFloat("opacity", controlOpacity).apply()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        editButton.setOnClickListener {
            dialog.dismiss()
            setControlEditMode(true)
        }
        resetButton.setOnClickListener {
            resetControlLayout()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun applyControlScale() {
        controlGroups.forEach { group ->
            group.content.scaleX = controlScale
            group.content.scaleY = controlScale
        }
    }

    private fun applyControlOpacity() {
        controlGroups.forEach { group ->
            group.content.alpha = controlOpacity
        }
    }

    private fun setControlEditMode(enabled: Boolean) {
        isEditingControls = enabled
        controlGroups.forEach { group ->
            group.dragHandle.visibility = if (enabled) View.VISIBLE else View.GONE
            if (enabled) group.container.bringToFront()
        }
        if (enabled) controlsSettingsButton.bringToFront()
        controlsSettingsButton.text = if (enabled) "SIMPAN" else "SET"
        Toast.makeText(
            this,
            if (enabled) "Geser kotak kuning untuk memindahkan tombol. Tekan SIMPAN setelah selesai."
            else "Posisi tombol disimpan.",
            Toast.LENGTH_LONG
        ).show()
    }

    private fun resetControlLayout() {
        controlPrefs.edit().clear().apply()
        controlScale = 1.0f
        controlOpacity = 0.88f
        controlGroups.forEach { group ->
            group.content.scaleX = controlScale
            group.content.scaleY = controlScale
            group.content.alpha = controlOpacity
            val params = group.container.layoutParams as FrameLayout.LayoutParams
            params.gravity = group.defaultGravity
            params.leftMargin = group.defaultLeftMargin
            params.rightMargin = group.defaultRightMargin
            params.topMargin = 0
            params.bottomMargin = group.defaultBottomMargin
            group.container.layoutParams = params
        }
        Toast.makeText(this, "Tata letak tombol dikembalikan.", Toast.LENGTH_SHORT).show()
    }

    private fun controlButton(
        label: String,
        keyCode: Int,
        game: GLRetroView,
        widthDp: Int = 58
    ): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.argb(185, 35, 40, 48))
            alpha = 0.88f
            minWidth = dp(widthDp)
            minHeight = dp(50)
            setPadding(dp(4), dp(2), dp(4), dp(2))
            setOnTouchListener { _, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        game.sendKeyEvent(KeyEvent.ACTION_DOWN, keyCode)
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        game.sendKeyEvent(KeyEvent.ACTION_UP, keyCode)
                        true
                    }
                    else -> true
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val game = retroView
        if (game != null && isGameKey(keyCode)) {
            game.sendKeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val game = retroView
        if (game != null && isGameKey(keyCode)) {
            game.sendKeyEvent(KeyEvent.ACTION_UP, keyCode)
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val game = retroView
        if (game != null) {
            game.sendMotionEvent(
                GLRetroView.MOTION_SOURCE_DPAD,
                event.getAxisValue(MotionEvent.AXIS_HAT_X),
                event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            )
            game.sendMotionEvent(
                GLRetroView.MOTION_SOURCE_ANALOG_LEFT,
                event.getAxisValue(MotionEvent.AXIS_X),
                event.getAxisValue(MotionEvent.AXIS_Y)
            )
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun isGameKey(keyCode: Int): Boolean = keyCode in setOf(
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT,
        KeyEvent.KEYCODE_BUTTON_A,
        KeyEvent.KEYCODE_BUTTON_B,
        KeyEvent.KEYCODE_BUTTON_X,
        KeyEvent.KEYCODE_BUTTON_Y,
        KeyEvent.KEYCODE_BUTTON_START,
        KeyEvent.KEYCODE_BUTTON_SELECT
    )

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
