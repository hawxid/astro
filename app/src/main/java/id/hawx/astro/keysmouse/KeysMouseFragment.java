package id.hawx.astro.keysmouse;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import id.hawx.astro.MainActivity;
import id.hawx.astro.R;
import id.hawx.astro.bluetooth.BluebinderController;
import id.hawx.astro.hid.report.MouseReport;
import id.hawx.astro.hid.report.UsbHid;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Live Keys+Mouse: one touchpad+keyboard UI, pluggable transports.
 * Slice 1: USB gadget transport (live) + BlueZ card (status + inline
 * Bluebinder activation). The BlueZ HID transport itself lands in slice 2.
 */
public class KeysMouseFragment extends Fragment {

    private static final int MODE_USB = 0;
    private static final int MODE_BLUEZ = 1;

    private final Handler main = new Handler(Looper.getMainLooper());
    private ExecutorService io;

    private BluebinderController bluebinder;
    private KeysMouseTransport active;
    private int mode = MODE_USB;

    private TextView statusView;
    private TextView bluezStatusView;
    private LinearLayout bluezCard;
    private Button connectBtn;
    private Button disconnectBtn;
    private Button usbBtn;
    private Button bluezBtn;
    private EditText input;

    private String lastText = "";
    private boolean updatingText;

    private float lastX;
    private float lastY;
    private long downTime;
    private float moveAccum;
    private boolean moved;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_keysmouse, container, false);
        io = Executors.newSingleThreadExecutor();
        bluebinder = new BluebinderController(requireContext());

        statusView = v.findViewById(R.id.km_status);
        bluezStatusView = v.findViewById(R.id.km_bluez_status);
        bluezCard = v.findViewById(R.id.km_bluez_card);
        connectBtn = v.findViewById(R.id.km_btn_connect);
        disconnectBtn = v.findViewById(R.id.km_btn_disconnect);
        usbBtn = v.findViewById(R.id.km_btn_usb);
        bluezBtn = v.findViewById(R.id.km_btn_bluez);
        input = v.findViewById(R.id.km_input);
        View touchpad = v.findViewById(R.id.km_touchpad);

        usbBtn.setOnClickListener(x -> setMode(MODE_USB));
        bluezBtn.setOnClickListener(x -> setMode(MODE_BLUEZ));
        connectBtn.setOnClickListener(x -> connect());
        disconnectBtn.setOnClickListener(x -> disconnect());
        v.findViewById(R.id.km_btn_arsenal).setOnClickListener(x ->
                new MainActivity.Receiver().changeFragment(R.id.usb_arsenal_item));
        v.findViewById(R.id.km_btn_activate_bluebinder).setOnClickListener(x -> activateBluebinder());
        v.findViewById(R.id.km_btn_check_env).setOnClickListener(x -> checkEnvironment());
        v.findViewById(R.id.km_btn_show_keyboard).setOnClickListener(x -> showKeyboard());

        bindMouseButton(v, R.id.km_btn_left, MouseReport.BTN_LEFT);
        bindMouseButton(v, R.id.km_btn_right, MouseReport.BTN_RIGHT);
        bindMouseButton(v, R.id.km_btn_middle, MouseReport.BTN_MIDDLE);
        v.findViewById(R.id.km_btn_wheel_up).setOnClickListener(x -> postWheel(-1));
        v.findViewById(R.id.km_btn_wheel_down).setOnClickListener(x -> postWheel(1));

        bindKeyButton(v, R.id.km_key_esc, UsbHid.KEY_ESC);
        bindKeyButton(v, R.id.km_key_tab, UsbHid.KEY_TAB);
        bindKeyButton(v, R.id.km_key_enter, UsbHid.KEY_ENTER);
        bindKeyButton(v, R.id.km_key_backspace, UsbHid.KEY_BACKSPACE);
        bindKeyButton(v, R.id.km_key_left, UsbHid.KEY_LEFT);
        bindKeyButton(v, R.id.km_key_up, UsbHid.KEY_UP);
        bindKeyButton(v, R.id.km_key_down, UsbHid.KEY_DOWN);
        bindKeyButton(v, R.id.km_key_right, UsbHid.KEY_RIGHT);

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override public void afterTextChanged(Editable s) {
                if (updatingText) return;
                String now = s.toString();
                if (now.length() > lastText.length()) {
                    sendText(now.substring(lastText.length()));
                } else if (now.length() < lastText.length()) {
                    int deleted = lastText.length() - now.length();
                    for (int i = 0; i < deleted; i++) {
                        postKey(0, UsbHid.KEY_BACKSPACE);
                    }
                }
                lastText = now;
                if (lastText.length() > 200) {
                    updatingText = true;
                    input.setText("");
                    updatingText = false;
                    lastText = "";
                }
            }
        });
        input.setOnEditorActionListener((tv, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                postKey(0, UsbHid.KEY_ENTER);
                return true;
            }
            return false;
        });

        touchpad.setOnTouchListener((view, ev) -> {
            if (!isConnected()) return true;
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = ev.getX();
                    lastY = ev.getY();
                    downTime = ev.getEventTime();
                    moveAccum = 0;
                    moved = false;
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = ev.getX() - lastX;
                    float dy = ev.getY() - lastY;
                    lastX = ev.getX();
                    lastY = ev.getY();
                    moveAccum += Math.abs(dx) + Math.abs(dy);
                    if (moveAccum > 12) moved = true;
                    int ix = Math.round(dx);
                    int iy = Math.round(dy);
                    if (ix != 0 || iy != 0) postMouseMove(ix, iy);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (!moved && ev.getEventTime() - downTime < 400) {
                        postMouseClick(MouseReport.BTN_LEFT);
                    }
                    return true;
                default:
                    return true;
            }
        });

        setMode(MODE_USB);
        setStatus("Pilih transport, lalu Hubungkan. USB: colok kabel ke host dulu. "
                + "Bluetooth: pairing dari host ke 'Astro Keys'.");
        return v;
    }

    @Override
    public void onDestroyView() {
        KeysMouseTransport t = active;
        active = null;
        if (t != null) {
            io.execute(t::close);
        }
        io.shutdown();
        super.onDestroyView();
    }

    private void setMode(int newMode) {
        mode = newMode;
        bluezCard.setVisibility(mode == MODE_BLUEZ ? View.VISIBLE : View.GONE);
        if (mode == MODE_BLUEZ) refreshBluezCard();
        updateButtons();
    }

    private void refreshBluezCard() {
        bluezStatusView.setText("Memeriksa Bluebinder...");
        bluebinder.checkStatus((running, hciUp, detail) -> {
            String text;
            if (running && hciUp) {
                text = "Bluebinder aktif, hci0 UP. Siap Hubungkan via Bluetooth.";
            } else if (running) {
                text = "Bluebinder jalan, hci0 DOWN — pencet Aktifkan untuk menaikkan hci0.";
            } else {
                text = "Bluebinder belum aktif — wireless butuh Bluebinder.";
            }
            bluezStatusView.setText(text + " (" + detail + ")");
        });
    }

    private void activateBluebinder() {
        bluezStatusView.setText("Mengaktifkan Bluebinder...");
        bluebinder.ensureActive((ok, detail) -> {
            bluezStatusView.setText(detail);
            setStatus(ok ? "Bluebinder siap." : "Bluebinder gagal: " + detail);
            refreshBluezCard();
        });
    }

    private void showKeyboard() {
        input.requestFocus();
        InputMethodManager imm = (InputMethodManager)
                requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(input, 0);
    }

    private void connect() {
        if (isConnected()) return;
        connectBtn.setEnabled(false);
        if (mode == MODE_BLUEZ) {
            connectBluez();
            return;
        }
        setStatus("Menghubungkan USB...");
        Context appCtx = requireContext().getApplicationContext();
        io.execute(() -> {
            try {
                UsbKeysMouseTransport transport = new UsbKeysMouseTransport(appCtx);
                transport.open();
                active = transport;
                main.post(() -> {
                    String note = transport.hasMouse()
                            ? "Terhubung (USB) — keyboard + mouse aktif."
                            : "Terhubung (USB) — keyboard saja, mouse tidak tersedia (hidg1 hilang).";
                    setStatus(note);
                    updateButtons();
                });
            } catch (IOException e) {
                main.post(() -> {
                    setStatus("Gagal: " + e.getMessage());
                    updateButtons();
                });
            }
        });
    }

    private void connectBluez() {
        setStatus("Mengaktifkan Bluebinder + memulai skrip BlueZ...");
        Context appCtx = requireContext().getApplicationContext();
        BluezKeysMouseTransport.StatusListener listener = (text, hostConnected) -> {
            setStatus(text);
            updateButtons();
        };
        io.execute(() -> {
            try {
                BluezKeysMouseTransport transport =
                        new BluezKeysMouseTransport(appCtx, listener);
                transport.open();
                active = transport;
                main.post(() -> {
                    setStatus("Bluetooth siap — pairing dari host ke 'Astro Keys'.");
                    updateButtons();
                });
            } catch (IOException e) {
                main.post(() -> {
                    setStatus("Gagal: " + e.getMessage());
                    updateButtons();
                });
            }
        });
    }

    private void checkEnvironment() {
        setStatus("Memeriksa lingkungan BlueZ...");
        BluezKeysMouseTransport probe = new BluezKeysMouseTransport(
                requireContext().getApplicationContext(),
                (text, hostConnected) -> { });
        probe.checkEnvironment(report -> setStatus(report));
    }

    private void disconnect() {
        KeysMouseTransport t = active;
        active = null;
        if (t != null) {
            io.execute(t::close);
        }
        setStatus("Terputus.");
        updateButtons();
    }

    private boolean isConnected() {
        return active != null && active.isOpen();
    }

    private void updateButtons() {
        boolean connected = isConnected();
        connectBtn.setEnabled(!connected);
        disconnectBtn.setEnabled(connected);
    }

    private void setStatus(String text) {
        statusView.setText(text);
    }

    private void bindMouseButton(View root, int viewId, int button) {
        root.findViewById(viewId).setOnClickListener(x -> postMouseClick(button));
    }

    private void bindKeyButton(View root, int viewId, int keycode) {
        root.findViewById(viewId).setOnClickListener(x -> postKey(0, keycode));
    }

    private void sendText(String text) {
        if (!isConnected() || text == null || text.isEmpty()) return;
        KeysMouseTransport t = active;
        io.execute(() -> {
            try {
                for (int i = 0; i < text.length(); i++) {
                    LiveKeymap.Entry e = LiveKeymap.lookup(text.charAt(i));
                    if (e != null) t.sendKey(e.modifier, e.keycode);
                }
            } catch (IOException e) {
                onIoError(e);
            }
        });
    }

    private void postKey(int modifier, int keycode) {
        if (!isConnected()) return;
        KeysMouseTransport t = active;
        io.execute(() -> {
            try {
                t.sendKey(modifier, keycode);
            } catch (IOException e) {
                onIoError(e);
            }
        });
    }

    private void postMouseMove(int dx, int dy) {
        if (!isConnected()) return;
        KeysMouseTransport t = active;
        io.execute(() -> {
            try {
                t.sendMouseMove(dx, dy);
            } catch (IOException e) {
                onIoError(e);
            }
        });
    }

    private void postMouseClick(int button) {
        if (!isConnected()) return;
        KeysMouseTransport t = active;
        io.execute(() -> {
            try {
                t.sendMouseClick(button);
            } catch (IOException e) {
                onIoError(e);
            }
        });
    }

    private void postWheel(int delta) {
        if (!isConnected()) return;
        KeysMouseTransport t = active;
        io.execute(() -> {
            try {
                t.sendMouseWheel(delta);
            } catch (IOException e) {
                onIoError(e);
            }
        });
    }

    private void onIoError(IOException e) {
        KeysMouseTransport t = active;
        active = null;
        if (t != null) {
            try {
                t.close();
            } catch (Exception ignored) {
            }
        }
        main.post(() -> {
            setStatus("Transport putus: " + e.getMessage());
            updateButtons();
        });
    }
}
