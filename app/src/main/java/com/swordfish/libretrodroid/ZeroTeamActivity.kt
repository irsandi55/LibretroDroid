package com.swordfish.libretrodroid

import android.content.pm.ActivityInfo
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.android.libretrodroid.R
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipFile

class ZeroTeamActivity : AppCompatActivity() {
    private var retroView: GLRetroView? = null

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

        val root = FrameLayout(this)
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
        setContentView(root)
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

        root.addView(
            dpad,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.BOTTOM
            ).apply {
                leftMargin = dp(16)
                bottomMargin = dp(14)
            }
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

        root.addView(
            actionPanel,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.BOTTOM
            ).apply {
                rightMargin = dp(16)
                bottomMargin = dp(14)
            }
        )
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
