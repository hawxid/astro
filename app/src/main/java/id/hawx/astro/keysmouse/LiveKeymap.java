package id.hawx.astro.keysmouse;

import androidx.annotation.Nullable;

import id.hawx.astro.hid.report.UsbHid;

/** Minimal char -> HID usage map for live typing (US layout). */
public final class LiveKeymap {

    public static final class Entry {
        public final int modifier;
        public final int keycode;

        public Entry(int modifier, int keycode) {
            this.modifier = modifier;
            this.keycode = keycode;
        }
    }

    private LiveKeymap() {}

    @Nullable
    public static Entry lookup(char c) {
        if (c >= 'a' && c <= 'z') return new Entry(0, 0x04 + (c - 'a'));
        if (c >= 'A' && c <= 'Z') return new Entry(UsbHid.MOD_LEFT_SHIFT, 0x04 + (c - 'A'));
        if (c >= '1' && c <= '9') return new Entry(0, 0x1E + (c - '1'));
        if (c == '0') return new Entry(0, 0x27);
        switch (c) {
            case ' ':  return new Entry(0, UsbHid.KEY_SPACE);
            case '\n': return new Entry(0, UsbHid.KEY_ENTER);
            case '-':  return new Entry(0, 0x2D);
            case '=':  return new Entry(0, 0x2E);
            case '[':  return new Entry(0, 0x2F);
            case ']':  return new Entry(0, 0x30);
            case '\\': return new Entry(0, 0x31);
            case ';':  return new Entry(0, 0x33);
            case '\'': return new Entry(0, 0x34);
            case '`':  return new Entry(0, 0x35);
            case ',':  return new Entry(0, 0x36);
            case '.':  return new Entry(0, 0x37);
            case '/':  return new Entry(0, 0x38);
            case '!':  return new Entry(UsbHid.MOD_LEFT_SHIFT, 0x1E);
            case '@':  return new Entry(UsbHid.MOD_LEFT_SHIFT, 0x1F);
            case '#':  return new Entry(UsbHid.MOD_LEFT_SHIFT, 0x20);
            case '$':  return new Entry(UsbHid.MOD_LEFT_SHIFT, 0x21);
            case '%':  return new Entry(UsbHid.MOD_LEFT_SHIFT, 0x22);
            case '^':  return new Entry(UsbHid.MOD_LEFT_SHIFT, 0x23);
            case '&':  return new Entry(UsbHid.MOD_LEFT_SHIFT, 0x24);
            case '*':  return new Entry(UsbHid.MOD_LEFT_SHIFT, 0x25);
            case '(':  return new Entry(UsbHid.MOD_LEFT_SHIFT, 0x26);
            case ')':  return new Entry(UsbHid.MOD_LEFT_SHIFT, 0x27);
            case '_':  return new Entry(UsbHid.MOD_LEFT_SHIFT, 0x2D);
            case '+':  return new Entry(UsbHid.MOD_LEFT_SHIFT, 0x2E);
            case '{':  return new Entry(UsbHid.MOD_LEFT_SHIFT, 0x2F);
            case '}':  return new Entry(UsbHid.MOD_LEFT_SHIFT, 0x30);
            case '|':  return new Entry(UsbHid.MOD_LEFT_SHIFT, 0x31);
            case ':':  return new Entry(UsbHid.MOD_LEFT_SHIFT, 0x33);
            case '"':  return new Entry(UsbHid.MOD_LEFT_SHIFT, 0x34);
            case '~':  return new Entry(UsbHid.MOD_LEFT_SHIFT, 0x35);
            case '<':  return new Entry(UsbHid.MOD_LEFT_SHIFT, 0x36);
            case '>':  return new Entry(UsbHid.MOD_LEFT_SHIFT, 0x37);
            case '?':  return new Entry(UsbHid.MOD_LEFT_SHIFT, 0x38);
            default:   return null;
        }
    }
}
