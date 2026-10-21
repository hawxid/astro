package com.stryker.terminal.component.colorscheme

import android.os.Build
import io.neolang.frontend.ConfigVisitor
import com.stryker.terminal.backend.TerminalColorScheme
import com.stryker.terminal.backend.TerminalColors
import com.stryker.terminal.component.ConfigFileBasedObject
import com.stryker.terminal.component.codegen.CodeGenObject
import com.stryker.terminal.component.codegen.CodeGenParameter
import com.stryker.terminal.component.codegen.CodeGenerator
import com.stryker.terminal.component.codegen.NeoColorGenerator
import com.stryker.terminal.frontend.session.view.TerminalView
import com.stryker.terminal.frontend.session.view.extrakey.ExtraKeysView
import com.stryker.terminal.utils.NLog

open class NeoColorScheme : CodeGenObject, ConfigFileBasedObject {
  companion object {
    const val COLOR_PREFIX = "color"
    const val CONTEXT_COLOR_NAME = "colors"
    const val CONTEXT_META_NAME = "color-scheme"

    const val COLOR_META_NAME = "name"
    const val COLOR_META_VERSION = "version"
    const val COLOR_DEF_BACKGROUND = "background"
    const val COLOR_DEF_FOREGROUND = "foreground"
    const val COLOR_DEF_CURSOR = "cursor"

    val COLOR_META_PATH = arrayOf(CONTEXT_META_NAME)
    val COLOR_PATH = arrayOf(CONTEXT_META_NAME, CONTEXT_COLOR_NAME)

    // Cache bitmap bg image (pre-scale COVER layar)
    private var cachedBgBitmap: android.graphics.Bitmap? = null
    private var cachedBgKey: String = ""

    const val COLOR_TYPE_BEGIN = -3
    const val COLOR_TYPE_END = 15

    const val COLOR_BACKGROUND = -3
    const val COLOR_FOREGROUND = -2
    const val COLOR_CURSOR = -1

    const val COLOR_DIM_BLACK = 0
    const val COLOR_DIM_RED = 1
    const val COLOR_DIM_GREEN = 2
    const val COLOR_DIM_YELLOW = 3
    const val COLOR_DIM_BLUE = 4
    const val COLOR_DIM_MAGENTA = 5
    const val COLOR_DIM_CYAN = 6
    const val COLOR_DIM_WHITE = 7

    const val COLOR_BRIGHT_BLACK = 8
    const val COLOR_BRIGHT_RED = 9
    const val COLOR_BRIGHT_GREEN = 10
    const val COLOR_BRIGHT_YELLOW = 11
    const val COLOR_BRIGHT_BLUE = 12
    const val COLOR_BRIGHT_MAGENTA = 13
    const val COLOR_BRIGHT_CYAN = 14
    const val COLOR_BRIGHT_WHITE = 15
  }

  lateinit var colorName: String
  var colorVersion: String? = null

  var foregroundColor: String? = null
  var backgroundColor: String? = null
  var cursorColor: String? = null
  var color: MutableMap<Int, String> = mutableMapOf()

  fun setColor(type: Int, color: String) {
    if (type < 0) {
      when (type) {
        COLOR_BACKGROUND -> backgroundColor = color
        COLOR_FOREGROUND -> foregroundColor = color
        COLOR_CURSOR -> cursorColor = color
      }
      return
    }
    this.color[type] = color
  }

  fun getColor(type: Int): String? {
    validateColors()
    return when (type) {
      COLOR_BACKGROUND -> backgroundColor
      COLOR_FOREGROUND -> foregroundColor
      COLOR_CURSOR -> cursorColor
      else -> {
        if (type in (0 until color.size)) {
          color[type]
        } else {
          ""
        }
      }
    }
  }

  fun copy(): NeoColorScheme {
    val copy = NeoColorScheme()
    copy.colorName = colorName
    copy.backgroundColor = backgroundColor
    copy.foregroundColor = foregroundColor
    copy.cursorColor = cursorColor
    this.color.forEach { copy.color.put(it.key, it.value) }
    return copy
  }

  @Throws(RuntimeException::class)
  override fun onConfigLoaded(configVisitor: ConfigVisitor) {
    val colorName = getMetaByVisitor(configVisitor, COLOR_META_NAME)
      ?: throw RuntimeException("ColorScheme must have a name")

    this.colorName = colorName
    this.colorVersion = getMetaByVisitor(configVisitor, COLOR_META_VERSION)

    backgroundColor = getColorByVisitor(configVisitor, "background")
    foregroundColor = getColorByVisitor(configVisitor, "foreground")
    cursorColor = getColorByVisitor(configVisitor, "cursor")
    configVisitor.getContext(COLOR_PATH).getAttributes().forEach {
      if (it.key.startsWith(COLOR_PREFIX)) {
        val colorIndex = try {
          it.key.substringAfter(COLOR_PREFIX).toInt()
        } catch (e: Exception) {
          -1
        }

        if (colorIndex == -1) {
          NLog.w("ColorScheme", "Invalid color type: ${it.key}")
        } else {
          setColor(colorIndex, it.value.asString())
        }
      }
    }

    validateColors()
  }

  internal fun applyColorScheme(view: TerminalView?, extraKeysView: ExtraKeysView?) {
    validateColors()

    // Terminal background image: reads /sdcard/Astro/terminal/term_bg.png if present.
    // Bitmap di-pre-scale COVER ukuran layar, gravity TOP|CENTER_HORIZONTAL,
    // di-cache biar nggak decode ulang tiap apply.
    var bgImageOk = false
    if (view != null) {
      try {
        val bgFile = java.io.File("/sdcard/Astro/terminal/term_bg.png")
        val dm = view.resources.displayMetrics
        val key = if (bgFile.exists() && bgFile.canRead()) {
          "${bgFile.absolutePath}:${bgFile.length()}:${bgFile.lastModified()}:${dm.widthPixels}x${dm.heightPixels}"
        } else ""
        var bitmap = if (key.isNotEmpty() && key == cachedBgKey) cachedBgBitmap else null
        if (bitmap == null && key.isNotEmpty()) {
          val raw = android.graphics.BitmapFactory.decodeFile(bgFile.absolutePath)
          if (raw != null) {
            val scale = maxOf(
              dm.widthPixels / raw.width.toFloat(),
              dm.heightPixels / raw.height.toFloat()
            )
            bitmap = android.graphics.Bitmap.createScaledBitmap(
              raw,
              (raw.width * scale).toInt(),
              (raw.height * scale).toInt(),
              true
            )
            bitmap.density = android.graphics.Bitmap.DENSITY_NONE
            raw.recycle()
            cachedBgBitmap = bitmap
            cachedBgKey = key
          }
        } else if (bitmap != null) {
        }
        val parent = view.getParent() as? android.view.View
        if (bitmap != null && parent != null) {
          val drawable = android.graphics.drawable.BitmapDrawable(view.resources, bitmap)
          drawable.gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
          parent.background = drawable
          view.setBackgroundColor(android.graphics.Color.TRANSPARENT)
          bgImageOk = true
        } else {
          if (parent != null) {
            parent.background = null
          }
        }
      } catch (_: Exception) {
      }
    }

    if (view != null) {
      val scheme = TerminalColorScheme()
      scheme.updateWith(foregroundColor, backgroundColor, cursorColor, color)
      val session = view.currentSession
      if (session != null && session.emulator != null) {
        session.emulator.setColorScheme(scheme)
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        // Skip solid color kalau gambar kepasang (view sudah transparan)
        if (!bgImageOk) {
          view.setBackgroundColor(TerminalColors.parse(backgroundColor))
        }
      }
    }

    if (extraKeysView != null) {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        extraKeysView.setBackgroundColor(TerminalColors.parse(backgroundColor))
        extraKeysView.setTextColor(TerminalColors.parse(foregroundColor))
      }
    }
  }

  override fun getCodeGenerator(parameter: CodeGenParameter): CodeGenerator {
    return NeoColorGenerator(parameter)
  }

  private fun validateColors() {
    backgroundColor = backgroundColor ?: DefaultColorScheme.backgroundColor
    foregroundColor = foregroundColor ?: DefaultColorScheme.foregroundColor
    cursorColor = cursorColor ?: DefaultColorScheme.cursorColor
  }

  private fun getMetaByVisitor(visitor: ConfigVisitor, metaName: String): String? {
    return visitor.getStringValue(COLOR_META_PATH, metaName)
  }

  private fun getColorByVisitor(visitor: ConfigVisitor, colorName: String): String? {
    return visitor.getStringValue(COLOR_PATH, colorName)
  }
}

object DefaultColorScheme : NeoColorScheme() {
  init {
    colorName = "Dracula"
    backgroundColor = "#282a36"
    foregroundColor = "#f8f8f2"
    cursorColor = "#f8f8f2"
  }
}
