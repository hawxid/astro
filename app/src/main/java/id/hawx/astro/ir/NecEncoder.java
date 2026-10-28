package id.hawx.astro.ir;

/**
 * Builds ConsumerIrManager transmit patterns for NEC-family protocols.
 *
 * Pattern semantics (android.hardware.ConsumerIrManager): alternating
 * on/off durations in microseconds, starting with ON. Bit order is
 * LSB-first of the 32-bit code, matching IRremote's sendNEC/sendSAMSUNG:
 * address, ~address, command, ~command for NEC; same layout for Samsung.
 *
 * NEC: leader 9000/4500. Samsung: leader 4500/4500. Both at 38 kHz.
 * Per bit: 560 on, then 1690 off for 1, 560 off for 0. Trailing 560 on.
 */
public final class NecEncoder {
    private NecEncoder() {
    }

    public static final int FREQUENCY_HZ = 38000;

    public static int[] nec(int code) {
        return build(code, 9000, 4500);
    }

    public static int[] samsung(int code) {
        return build(code, 4500, 4500);
    }

    private static int[] build(int code, int leaderOn, int leaderOff) {
        int[] pattern = new int[2 + 32 * 2 + 1];
        pattern[0] = leaderOn;
        pattern[1] = leaderOff;
        int idx = 2;
        for (int i = 0; i < 32; i++) {
            pattern[idx++] = 560;
            pattern[idx++] = ((code >>> i) & 1) == 1 ? 1690 : 560;
        }
        pattern[idx] = 560;
        return pattern;
    }
}
