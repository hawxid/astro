package id.hawx.astro.keysmouse;

import java.io.IOException;

/** Live keyboard+mouse transport: USB gadget today, BlueZ HID next. */
public interface KeysMouseTransport {

    String name();

    void open() throws IOException;

    void close();

    boolean isOpen();

    /** False when the gadget/profile has no mouse node: keyboard still works. */
    boolean hasMouse();

    void sendKey(int modifier, int keycode) throws IOException;

    void sendMouseMove(int dx, int dy) throws IOException;

    void sendMouseButton(int button, boolean pressed) throws IOException;

    void sendMouseClick(int button) throws IOException;

    void sendMouseWheel(int delta) throws IOException;
}
