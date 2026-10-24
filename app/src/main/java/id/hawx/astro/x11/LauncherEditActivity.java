package id.hawx.astro.x11;

import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Form editor launcher ala XFCE (permintaan Hawx): nama + ikon + perintah
 * chroot; tap ikon di daftar = perintah dijalankan (CLI -> terminal,
 * GUI -> Layar X lewat XSessionManager).
 *
 * Validasi ringan saja (nama & perintah tidak kosong). Perintah TIDAK
 * disaring — ini perangkat root milik user sendiri; editor adalah alatnya,
 * bukan penjaga. Entri bawaan tidak bisa dihapus, hanya disembunyikan.
 */
public class LauncherEditActivity extends AppCompatActivity {

    private LauncherStore store;
    private XSessionManager mgr;
    private LauncherEntry existing;

    private EditText fName, fIcon, fCommand, fWorkdir, fEnv;
    private Spinner fType;
    private TextView fCommandLabel;
    private CheckBox fMultiple;
    private CheckBox fFloating;

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new LauncherStore(this);
        mgr = XSessionManager.get(this);
        String id = getIntent().getStringExtra("entry_id");
        existing = id != null ? store.get(id) : null;

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(16), dp(16), dp(16));

        TextView title = new TextView(this);
        title.setText(existing == null ? "Launcher Baru" : "Edit Launcher");
        title.setTextSize(20);
        title.setTypeface(null, Typeface.BOLD);
        root.addView(title);

        fName = field(root, "Nama", "mis. Wireshark");
        fIcon = field(root, "Ikon (emoji, kosong = huruf pertama; atau file:<nama.png> di /sdcard/Astro/icons/)", "🖥");
        fCommand = field(root, "Perintah (dijalankan di chroot, apa adanya)", "mis. wireshark -i wlan0");
        fCommandLabel = (TextView) root.getChildAt(root.indexOfChild(fCommand) - 1);
        fCommand.setSingleLine(false);
        fCommand.setMinLines(2);

        TextView typeLabel = label("Tipe");
        root.addView(typeLabel);
        fType = new Spinner(this);
        ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                new String[]{"GUI — tampil di Layar X", "CLI — jalan di terminal chroot",
                        "WebView — jendela web kustom (HTML lokal)"});
        fType.setAdapter(ad);
        root.addView(fType);
        fType.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent,
                                                 android.view.View view, int pos, long id) {
                if (fCommandLabel == null) return;
                if (pos == 2) {
                    fCommandLabel.setText("Berkas HTML (path lengkap berkas web lokal)");
                    fCommand.setHint("mis. /sdcard/Astro/tools/hitung.html");
                    fFloating.setText("Buka sebagai jendela melayang");
                } else {
                    fCommandLabel.setText("Perintah (dijalankan di chroot, apa adanya)");
                    fCommand.setHint("mis. wireshark -i wlan0");
                    fFloating.setText("Buka sebagai jendela floating (display sendiri, bukan layar penuh)");
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        fWorkdir = field(root, "Folder kerja di chroot (kosong = /root)", "/root");
        fEnv = field(root, "Env tambahan (KUNCI=nilai, satu per baris; DISPLAY diatur otomatis untuk GUI)", "GTK_THEME=Adwaita:dark");
        fEnv.setSingleLine(false);
        fEnv.setMinLines(2);

        fMultiple = new CheckBox(this);
        fMultiple.setText("Boleh banyak jendela (tap saat jalan = luncurkan lagi, bukan fokus)");
        root.addView(fMultiple);

        fFloating = new CheckBox(this);
        fFloating.setText("Buka sebagai jendela floating (display sendiri, bukan layar penuh)");
        root.addView(fFloating);

        if (existing != null) {
            fName.setText(existing.name);
            fIcon.setText(existing.icon);
            fCommand.setText(existing.command);
            fType.setSelection("cli".equals(existing.type) ? 1
                    : "webview".equals(existing.type) ? 2 : 0);
            fWorkdir.setText(existing.workdir);
            fEnv.setText(existing.env);
            fMultiple.setChecked(existing.allowMultiple);
            fFloating.setChecked(existing.floating);
        }

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setPadding(0, dp(14), 0, 0);
        row1.addView(btn("Simpan", v -> save()));
        row1.addView(btn("Tes Perintah", v -> testRun()));
        row1.addView(btn("Batal", v -> finish()));
        root.addView(row1);

        if (existing != null) {
            LinearLayout row2 = new LinearLayout(this);
            row2.setOrientation(LinearLayout.HORIZONTAL);
            if (existing.builtin) {
                row2.addView(btn(existing.hidden ? "Tampilkan Lagi" : "Sembunyikan", v -> {
                    existing.hidden = !existing.hidden;
                    store.upsert(existing);
                    toast(existing.hidden ? "Disembunyikan" : "Ditampilkan lagi");
                    finish();
                }));
            } else {
                row2.addView(btn("Hapus Launcher", v -> {
                    store.remove(existing.id);
                    toast("Dihapus");
                    finish();
                }));
            }
            root.addView(row2);
        }

        ScrollView outer = new ScrollView(this);
        outer.addView(root);
        setContentView(outer);
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setPadding(0, dp(12), 0, dp(2));
        t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private EditText field(LinearLayout root, String labelText, String hint) {
        root.addView(label(labelText));
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        root.addView(e);
        return e;
    }

    private Button btn(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setOnClickListener(l);
        return b;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private LauncherEntry collect(String id) {
        LauncherEntry e = new LauncherEntry();
        e.id = id;
        e.name = fName.getText().toString().trim();
        e.icon = fIcon.getText().toString().trim();
        e.command = fCommand.getText().toString().trim();
        int tp = fType.getSelectedItemPosition();
        e.type = tp == 1 ? "cli" : tp == 2 ? "webview" : "gui";
        e.workdir = fWorkdir.getText().toString().trim();
        e.env = fEnv.getText().toString();
        e.allowMultiple = fMultiple.isChecked();
        e.floating = fFloating.isChecked();
        e.builtin = existing != null && existing.builtin;
        e.hidden = existing != null && existing.hidden;
        return e;
    }

    private boolean valid(LauncherEntry e) {
        if (e.name.isEmpty()) { toast("Nama tidak boleh kosong"); return false; }
        if (e.command.isEmpty()) {
            toast("webview".equals(e.type) ? "Berkas HTML tidak boleh kosong"
                    : "Perintah tidak boleh kosong");
            return false;
        }
        return true;
    }

    private void save() {
        String id = existing != null ? existing.id : store.uniqueId(LauncherEntry.slug(fName.getText().toString()));
        LauncherEntry e = collect(id);
        if (!valid(e)) return;
        store.upsert(e);
        toast("Tersimpan: " + e.name);
        finish();
    }

    private void testRun() {
        LauncherEntry e = collect(existing != null ? existing.id : "tes");
        if (!valid(e)) return;
        if ("webview".equals(e.type)) {
            if (e.floating) {
                boolean shown = id.hawx.astro.x11.window.WebOverlayWindows
                        .show(this, e.name, e.command);
                if (shown) return;
            }
            android.content.Intent i = new android.content.Intent(
                    this, id.hawx.astro.goose.GhostVizActivity.class);
            i.putExtra(id.hawx.astro.goose.GhostVizActivity.EXTRA_PATH, e.command);
            i.putExtra(id.hawx.astro.goose.GhostVizActivity.EXTRA_TITLE, e.name);
            startActivity(i);
            return;
        }
        mgr.launch(e, (ok, msg) -> toast(msg));
    }
}
