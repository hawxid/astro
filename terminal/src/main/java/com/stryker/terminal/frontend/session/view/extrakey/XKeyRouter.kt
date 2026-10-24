package com.stryker.terminal.frontend.session.view.extrakey

import java.util.function.BiConsumer
import java.util.function.Consumer

/**
 * Router bar tombol ekstra terminal -> jendela X overlay (keputusan
 * Hawx 2026-10-24: strip jendela overlay = bar bawaan terminal,
 * bukan strip kedua). Diisi sisi aplikasi (XOverlayWindows) selama
 * ada jendela overlay tampil; semua null = bar 100% normal buat
 * terminal. keySink: keyCode one-shot (panah/Esc/Tab/dll).
 * textSink: teks mentah tombol user. modifierSink: (nama, turun?)
 * buat Ctrl/Alt/LShift latch — diterjemahkan aplikasi jadi key
 * event X asli.
 */
object XKeyRouter {
  @JvmField var keySink: Consumer<Int>? = null
  @JvmField var textSink: Consumer<String>? = null
  @JvmField var modifierSink: BiConsumer<String, Boolean>? = null

  @JvmStatic
  fun clear() {
    keySink = null
    textSink = null
    modifierSink = null
  }
}
