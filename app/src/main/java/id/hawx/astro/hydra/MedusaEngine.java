package id.hawx.astro.hydra;

import android.app.Activity;
import android.content.Context;

import id.hawx.astro.utils.AdvancedProcess;
import id.hawx.astro.utils.Core;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Medusa engine — mirrors HydraEngine (same shape: Spec, Credential,
 * State, Listener, transcript, AdvancedProcess). Medusa speaks flags
 * for host (-h) and module (-M); there are no positionals.
 *
 * Command built (flags only, extras appended verbatim last):
 *   medusa -h &lt;host&gt; [-n &lt;port&gt;] [-u &lt;user&gt; | -U &lt;userlist&gt;]
 *          [-p &lt;pass&gt; | -P &lt;passlist&gt;] -M &lt;module&gt;
 *          [-t &lt;threads&gt;] [-f] [-v] [-O &lt;outfile&gt;] [extra flags]
 * Empty user / password fields are omitted (e.g. vnc takes no -u).
 */
public final class MedusaEngine {

    public enum State { IDLE, RUNNING, FOUND, NONE, ERROR, KILLED }

    public static final class Spec {
        public final String service;
        public final String target;
        public final String port;
        public final String loginWordlist;
        public final String passwordWordlist;
        public final String singleLogin;
        public final String singlePassword;
        public final int threads;
        public final boolean verbose;
        public final boolean stopOnFirst;
        public final String outputFile;
        public final String extraFlags;

        public Spec(String service, String target, String port,
                    String loginWordlist, String passwordWordlist,
                    String singleLogin, String singlePassword,
                    int threads, boolean verbose, boolean stopOnFirst,
                    String outputFile, String extraFlags) {
            this.service = service;
            this.target = target;
            this.port = port;
            this.loginWordlist = loginWordlist;
            this.passwordWordlist = passwordWordlist;
            this.singleLogin = singleLogin;
            this.singlePassword = singlePassword;
            this.threads = threads;
            this.verbose = verbose;
            this.stopOnFirst = stopOnFirst;
            this.outputFile = outputFile;
            this.extraFlags = extraFlags;
        }
    }

    public static final class Credential {
        public final String host;
        public final String port;
        public final String login;
        public final String password;
        public final String service;

        public Credential(String host, String port, String login, String password, String service) {
            this.host = host;
            this.port = port;
            this.login = login;
            this.password = password;
            this.service = service;
        }
    }

    public interface Listener {
        void onState(State state, String reason);
        void onLine(String line);
        void onCredentialFound(Credential credential);
        void onProgress(int tried, int total);
    }

    private static final Pattern CREDENTIAL =
            Pattern.compile("ACCOUNT FOUND: \\[(\\S+)\\] Host: (\\S+) User: (.*?) Password: (.*) \\[SUCCESS\\]");

    private final Activity activity;
    private final Context context;
    private final Core core;
    private final Listener listener;

    private AdvancedProcess process;
    private State state = State.IDLE;
    private FileWriter transcript;
    private File transcriptFile;
    private final ArrayList<Credential> credentials = new ArrayList<>();

    public MedusaEngine(Activity activity, Context context, Core core, Listener listener) {
        this.activity = activity;
        this.context = context;
        this.core = core;
        this.listener = listener;
    }

    public State state() {
        return state;
    }

    public ArrayList<Credential> credentials() {
        return credentials;
    }

    public File transcriptFile() {
        return transcriptFile;
    }

    public synchronized void start(Spec spec) {
        if (state == State.RUNNING) return;
        credentials.clear();
        openTranscript(spec);

        String cmd = buildCommand(spec);
        appendTranscript("$ " + cmd);
        setState(State.RUNNING, "Starting medusa");

        process = new AdvancedProcess(activity, context, cmd, true) {
            @Override
            public void onFinished(ArrayList<String> outputList) {
                closeTranscript();
                if (state == State.KILLED) {
                    return;
                }
                if (!credentials.isEmpty()) {
                    setState(State.FOUND, "Found " + credentials.size() + " credential"
                            + (credentials.size() == 1 ? "" : "s"));
                } else {
                    setState(State.NONE, "No credentials found");
                }
            }

            @Override
            public void onNewLine(String line) {
                handleLine(line);
            }

            @Override
            public void onEvent(String line) {
            }
        };
    }

    public synchronized void kill() {
        if (state != State.RUNNING) return;
        setState(State.KILLED, "Stopped by user");
        if (process != null) {
            try {
                process.kill();
            } catch (Exception ignored) {
            }
        }
        closeTranscript();
    }

    /** The exact command that will run — also used for the UI preview. */
    public static String buildCommand(Spec spec) {
        StringBuilder cmd = new StringBuilder("medusa ");
        cmd.append("-h ").append(spec.target).append(' ');
        if (notEmpty(spec.port)) cmd.append("-n ").append(spec.port).append(' ');
        if (notEmpty(spec.singleLogin)) {
            cmd.append("-u ").append(HydraEngine.shellQuote(spec.singleLogin)).append(' ');
        } else if (notEmpty(spec.loginWordlist)) {
            cmd.append("-U ").append(spec.loginWordlist).append(' ');
        }
        if (notEmpty(spec.singlePassword)) {
            cmd.append("-p ").append(HydraEngine.shellQuote(spec.singlePassword)).append(' ');
        } else if (notEmpty(spec.passwordWordlist)) {
            cmd.append("-P ").append(spec.passwordWordlist).append(' ');
        }
        cmd.append("-M ").append(spec.service).append(' ');
        if (spec.threads > 0) cmd.append("-t ").append(spec.threads).append(' ');
        if (spec.stopOnFirst) cmd.append("-f ");
        if (spec.verbose) cmd.append("-v ");
        if (notEmpty(spec.outputFile)) cmd.append("-O ").append(spec.outputFile).append(' ');
        if (notEmpty(spec.extraFlags)) cmd.append(spec.extraFlags.trim()).append(' ');
        return cmd.toString().trim();
    }

    private void handleLine(String rawLine) {
        if (rawLine == null) return;
        appendTranscript(rawLine);
        listener.onLine(rawLine);

        Matcher m = CREDENTIAL.matcher(rawLine);
        if (m.find()) {
            String service = m.group(1);
            String host = m.group(2);
            String login = m.group(3) == null ? "" : m.group(3).trim();
            String password = m.group(4) == null ? "" : m.group(4).trim();
            Credential cred = new Credential(host, "", login, password, service);
            credentials.add(cred);
            listener.onCredentialFound(cred);
        }
    }

    private void openTranscript(Spec spec) {
        try {
            File dir = new File(core.getShareRoot(), "medusa");
            dir.mkdirs();
            String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            String safeTarget = spec.target.replaceAll("[^A-Za-z0-9._-]", "_");
            transcriptFile = new File(dir, safeTarget + "_" + spec.service + "_" + ts + ".log");
            transcript = new FileWriter(transcriptFile, true);
        } catch (Exception e) {
            transcript = null;
            transcriptFile = null;
        }
    }

    private void appendTranscript(String line) {
        if (transcript == null) return;
        try {
            transcript.write(line);
            transcript.write('\n');
            transcript.flush();
        } catch (Exception ignored) {
        }
    }

    private void closeTranscript() {
        if (transcript == null) return;
        try {
            transcript.close();
        } catch (Exception ignored) {
        }
        transcript = null;
    }

    private void setState(State newState, String reason) {
        state = newState;
        if (activity != null) activity.runOnUiThread(() -> listener.onState(newState, reason));
        else listener.onState(newState, reason);
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }
}
