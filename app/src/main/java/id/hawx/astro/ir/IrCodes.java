package id.hawx.astro.ir;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TV remote code tables, v1 scope.
 *
 * LG: full NEC table, address 0x20DF (Tasmota "Codes for IR Remotes",
 * community-verified against real LG remotes).
 * Samsung: power only (0xE0E040BF, Arduino-forum verified). The rest of
 * the Samsung table is deliberately NOT shipped until verified — a wrong
 * table is worse than a disabled button. Samsung owners still get power
 * + the raw hex sender + the power wizard.
 */
public final class IrCodes {
    private IrCodes() {
    }

    public static final int PROTOCOL_NEC = 0;
    public static final int PROTOCOL_SAMSUNG = 1;

    public static final class Entry {
        public final String name;
        public final int code;
        public final int protocol;

        Entry(String name, int code, int protocol) {
            this.name = name;
            this.code = code;
            this.protocol = protocol;
        }
    }

    /** Buttons the pad wires per brand. LG = full pad; Samsung = power only. */
    public static Map<String, Entry> forBrand(String brand) {
        Map<String, Entry> m = new LinkedHashMap<>();
        if ("Samsung".equals(brand)) {
            m.put("power", new Entry("Power", 0xE0E040BF, PROTOCOL_SAMSUNG));
            return m;
        }
        // LG default.
        put(m, "power", "Power", 0x20DF10EF);
        put(m, "vol_up", "Vol +", 0x20DF40BF);
        put(m, "vol_down", "Vol -", 0x20DFC03F);
        put(m, "mute", "Mute", 0x20DF906F);
        put(m, "ch_up", "Ch +", 0x20DF00FF);
        put(m, "ch_down", "Ch -", 0x20DF807F);
        put(m, "up", "Atas", 0x20DF02FD);
        put(m, "down", "Bawah", 0x20DF827D);
        put(m, "left", "Kiri", 0x20DFE01F);
        put(m, "right", "Kanan", 0x20DF609F);
        put(m, "ok", "OK", 0x20DF22DD);
        put(m, "back", "Back", 0x20DF14EB);
        put(m, "exit", "Exit", 0x20DFDA25);
        put(m, "input", "Input", 0x20DFD02F);
        put(m, "settings", "Settings", 0x20DFC23D);
        put(m, "guide", "Guide", 0x20DFD52A);
        put(m, "info", "Info", 0x20DF55AA);
        put(m, "d0", "0", 0x20DF08F7);
        put(m, "d1", "1", 0x20DF8877);
        put(m, "d2", "2", 0x20DF48B7);
        put(m, "d3", "3", 0x20DFC837);
        put(m, "d4", "4", 0x20DF28D7);
        put(m, "d5", "5", 0x20DFA857);
        put(m, "d6", "6", 0x20DF6897);
        put(m, "d7", "7", 0x20DFE817);
        put(m, "d8", "8", 0x20DF18E7);
        put(m, "d9", "9", 0x20DF9867);
        put(m, "play", "Play", 0x20DF0DF2);
        put(m, "pause", "Pause", 0x20DF5DA2);
        put(m, "stop", "Stop", 0x20DF8D72);
        put(m, "red", "Merah", 0x20DF4EB1);
        put(m, "green", "Hijau", 0x20DF8E71);
        put(m, "yellow", "Kuning", 0x20DFC639);
        put(m, "blue", "Biru", 0x20DF8679);
        put(m, "hdmi1", "HDMI 1", 0x20DF738C);
        put(m, "hdmi2", "HDMI 2", 0x20DF33CC);
        put(m, "hdmi3", "HDMI 3", 0x20DF9768);
        return m;
    }

    private static void put(Map<String, Entry> m, String key, String name, int code) {
        m.put(key, new Entry(name, code, PROTOCOL_NEC));
    }

    /** Power-code wizard candidates, in try order. */
    public static final Entry[] POWER_WIZARD = {
            new Entry("LG", 0x20DF10EF, PROTOCOL_NEC),
            new Entry("Samsung", 0xE0E040BF, PROTOCOL_SAMSUNG),
    };
}
