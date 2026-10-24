package com.stryker.terminal.frontend.session.terminal

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import com.stryker.terminal.backend.TerminalSession
import com.stryker.terminal.component.config.NeoPreference
import com.stryker.terminal.frontend.session.view.TerminalView
import com.stryker.terminal.frontend.session.view.TerminalViewClient

open class BasicSessionCallback(var terminalView: TerminalView) : TerminalSession.SessionChangedCallback {
  override fun onTextChanged(changedSession: TerminalSession?) {
    if (changedSession != null) {
      terminalView.onScreenUpdated()
    }
  }

  override fun onTitleChanged(changedSession: TerminalSession?) {
  }

  override fun onSessionFinished(finishedSession: TerminalSession?) {
  }

  override fun onClipboardText(session: TerminalSession?, text: String?) {
  }

  override fun onBell(session: TerminalSession?) {
  }

  override fun onColorsChanged(session: TerminalSession?) {
    if (session != null) {
      terminalView.onScreenUpdated()
    }
  }
}

class BasicViewClient(val terminalView: TerminalView) :
    com.stryker.terminal.frontend.session.view.TerminalViewClient {
  override fun onScale(scale: Float): Float {
    if (scale < 0.9f || scale > 1.1f) {
      val increase = scale > 1f
      val changedSize = (if (increase) 1 else -1) * 2
      val fontSize = NeoPreference.validateFontSize(terminalView.textSize + changedSize)
      terminalView.textSize = fontSize
      return 1.0f
    }
    return scale
  }

  override fun onSingleTapUp(e: MotionEvent?) {
    // Ketuk di luar jendela overlay = input pindah ke terminal
    // (lebih natural daripada tombol keyboard): bersihkan router
    // bar supaya tombol bar ikut pulang, bukan cuma ketikan.
    // Temuan Hawx: EK pindah (router), tapi KEYBOARD cuma bisa
    // nulis di jendela — karena showSoftInput terminal gagal
    // selama overlay masih pegang fokus window. Jadi kalau
    // router sempat aktif (overlay pernah naik), lepas dulu
    // fokus overlay via broadcast, baru tampilkan keyboard
    // (tunda 150 md, pola sama kaya tombol keyboard bar).
    val router = com.stryker.terminal.frontend.session.view.extrakey.XKeyRouter
    val hadRouter = router.keySink != null
    router.clear()
    val imm = (terminalView.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
    if (terminalView.isFocusable && terminalView.isFocusableInTouchMode) {
      if (hadRouter) {
        terminalView.context.sendBroadcast(android.content.Intent(
          "id.hawx.astro.RELEASE_OVERLAY_FOCUS").setPackage(terminalView.context.packageName))
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
          terminalView.requestFocus()
          imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
        }, 150)
      } else {
        imm.showSoftInput(terminalView, InputMethodManager.SHOW_IMPLICIT)
      }
    }
  }

  override fun shouldBackButtonBeMappedToEscape(): Boolean {
    return false
  }

  override fun copyModeChanged(copyMode: Boolean) {
  }

  override fun onKeyDown(keyCode: Int, e: KeyEvent?, session: TerminalSession?): Boolean {
    return false
  }

  override fun onKeyUp(keyCode: Int, e: KeyEvent?): Boolean {
    return false
  }

  override fun readControlKey(): Boolean {
    return false
  }

  override fun readAltKey(): Boolean {
    return false
  }

  override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: TerminalSession?): Boolean {
    return false
  }

  override fun readShiftKey(): Boolean {
    return false
  }

  override fun readFnKey(): Boolean {
    return false
  }

  override fun onLongPress(event: MotionEvent?): Boolean {
    return false
  }
}
