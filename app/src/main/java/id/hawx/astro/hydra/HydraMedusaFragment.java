package id.hawx.astro.hydra;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.slider.Slider;
import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.textview.MaterialTextView;

import id.hawx.astro.R;
import id.hawx.astro.utils.Core;
import id.hawx.astro.utils.NoNestedScrollView;
import id.hawx.astro.wordlists.Wordlist;
import id.hawx.astro.wordlists.WordlistCategory;
import id.hawx.astro.wordlists.WordlistPickerDialog;
import id.hawx.astro.wordlists.WordlistStore;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Hydra &amp; Medusa — one menu, one tool at a time.
 * A ChipGroup "switch" picks Hydra or Medusa; the service/module
 * chip row, default port and default thread count adapt to the pick.
 * The form mirrors the Hydra dialog from Local Network: same SERVICE
 * chip row with a Custom chip, same TARGET, CREDENTIALS and TUNING
 * sections, same console with the fullscreen log. Added on top of
 * the dialog: the tool switch, output file, extra flags, and the
 * live command preview.
 * A live command preview shows the exact command before Run.
 * Run executes it in the chroot (AdvancedProcess, live output);
 * Stop kills it. Nothing runs until Run is tapped.
 *
 * Threads: a visible slider (1-64). Defaults are the gentle per-tool
 * default on this phone: hydra 16 (its documented default),
 * medusa 4 (small and safe against the tiny DroidSpace containers;
 * the medusa default is 16 per its tutorials, but 16 concurrent
 * logins can trip the MaxStartups limit of a small sshd — the slider goes to 64).
 */
public class HydraMedusaFragment extends Fragment {

    private static final int THREADS_MIN = 1;
    private static final int THREADS_MAX = 64;
    private static final int THREADS_DEFAULT_HYDRA = 16;
    private static final int THREADS_DEFAULT_MEDUSA = 4;

    /** Real medusa modules (medusa -d). No rdp, no redis — medusa has neither. */
    private static final String[] MEDUSA_SERVICES = {
            "ssh", "ftp", "telnet", "http", "smbnt", "mysql", "postgres", "mssql",
            "vnc", "pop3", "imap", "smtp", "snmp", "ldap", "cvs", "svn",
            "web-form", "nntp", "rexec", "rlogin", "rsh", "pcanywhere", "afp", "smtp-vrfy"
    };

    private static final Map<String, String> MEDUSA_DEFAULT_PORTS = new LinkedHashMap<>();

    static {
        MEDUSA_DEFAULT_PORTS.put("ssh", "22");
        MEDUSA_DEFAULT_PORTS.put("ftp", "21");
        MEDUSA_DEFAULT_PORTS.put("telnet", "23");
        MEDUSA_DEFAULT_PORTS.put("http", "80");
        MEDUSA_DEFAULT_PORTS.put("smbnt", "445");
        MEDUSA_DEFAULT_PORTS.put("mysql", "3306");
        MEDUSA_DEFAULT_PORTS.put("postgres", "5432");
        MEDUSA_DEFAULT_PORTS.put("mssql", "1433");
        MEDUSA_DEFAULT_PORTS.put("vnc", "5900");
        MEDUSA_DEFAULT_PORTS.put("pop3", "110");
        MEDUSA_DEFAULT_PORTS.put("imap", "143");
        MEDUSA_DEFAULT_PORTS.put("smtp", "25");
        MEDUSA_DEFAULT_PORTS.put("smtp-vrfy", "25");
        MEDUSA_DEFAULT_PORTS.put("snmp", "161");
        MEDUSA_DEFAULT_PORTS.put("ldap", "389");
        MEDUSA_DEFAULT_PORTS.put("cvs", "2401");
        MEDUSA_DEFAULT_PORTS.put("svn", "3690");
        MEDUSA_DEFAULT_PORTS.put("web-form", "80");
        MEDUSA_DEFAULT_PORTS.put("nntp", "119");
        MEDUSA_DEFAULT_PORTS.put("rexec", "512");
        MEDUSA_DEFAULT_PORTS.put("rlogin", "513");
        MEDUSA_DEFAULT_PORTS.put("rsh", "514");
        MEDUSA_DEFAULT_PORTS.put("pcanywhere", "5631");
        MEDUSA_DEFAULT_PORTS.put("afp", "548");
    }

    private static final class FormValues {
        String service = "";
        String target = "";
        String port = "";
        String loginWordlist = "";
        String passwordWordlist = "";
        String singleLogin = "";
        String singlePassword = "";
        int threads = THREADS_DEFAULT_HYDRA;
        boolean verbose;
        boolean stopOnFirst;
        boolean loopUsers;
        String outputFile = "";
        String extraFlags = "";
    }

    private static final String SERVICE_CUSTOM_TAG = "__custom__";

    private Activity activity;
    private Context context;
    private Core core;

    private MaterialTextView statusText;
    private ChipGroup toolGroup;
    private ChipGroup serviceGroup;
    private TextInputLayout customSvcLayout;
    private MaterialAutoCompleteTextView customSvcField;
    private TextInputEditText targetField;
    private TextInputEditText portField;
    private ChipGroup loginModeGroup;
    private ChipGroup passwordModeGroup;
    private View loginWlLayout;
    private View passwordWlLayout;
    private View singleLoginLayout;
    private View singlePasswordLayout;
    private MaterialAutoCompleteTextView loginWlField;
    private MaterialAutoCompleteTextView passwordWlField;
    private TextInputEditText singleLoginField;
    private TextInputEditText singlePasswordField;
    private Slider threadsSlider;
    private MaterialTextView threadsValue;
    private Chip verboseChip;
    private Chip firstChip;
    private Chip loopChip;
    private TextInputEditText outputFileField;
    private TextInputEditText extraFlagsField;
    private MaterialTextView previewText;
    private MaterialButton copyPreviewBtn;
    private MaterialButton runBtn;
    private MaterialButton stopBtn;
    private LinearProgressIndicator progress;
    private MaterialCardView foundCard;
    private MaterialTextView foundText;
    private View foundCopy;
    private View terminalContainer;
    private View terminalBar;
    private View terminalExpand;
    private NoNestedScrollView terminalScroll;
    private MaterialTextView terminalText;

    private boolean isMedusa;
    private String selectedService = "ssh";
    private boolean serviceIsCustom;
    private boolean serviceChipsRebuilding;
    private Chip customServiceChip;
    private HydraEngine hydraEngine;
    private MedusaEngine medusaEngine;
    private WordlistStore wordlistStore;
    private Wordlist pickedLoginList;
    private Wordlist pickedPasswordList;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        activity = getActivity();
        context = getContext();
        core = new Core(context);
        wordlistStore = new WordlistStore(core);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_hydramedusa, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        statusText = view.findViewById(R.id.hm_status);
        toolGroup = view.findViewById(R.id.hm_tool_group);
        serviceGroup = view.findViewById(R.id.hm_service_group);
        customSvcLayout = view.findViewById(R.id.hm_service_custom_layout);
        customSvcField = view.findViewById(R.id.hm_service_custom);
        targetField = view.findViewById(R.id.hm_target);
        portField = view.findViewById(R.id.hm_port);
        loginModeGroup = view.findViewById(R.id.hm_login_mode_group);
        passwordModeGroup = view.findViewById(R.id.hm_password_mode_group);
        loginWlLayout = view.findViewById(R.id.hm_login_wl_layout);
        passwordWlLayout = view.findViewById(R.id.hm_password_wl_layout);
        singleLoginLayout = view.findViewById(R.id.hm_single_login_layout);
        singlePasswordLayout = view.findViewById(R.id.hm_single_password_layout);
        loginWlField = view.findViewById(R.id.hm_login_wl);
        passwordWlField = view.findViewById(R.id.hm_password_wl);
        singleLoginField = view.findViewById(R.id.hm_single_login);
        singlePasswordField = view.findViewById(R.id.hm_single_password);
        threadsSlider = view.findViewById(R.id.hm_threads_slider);
        threadsValue = view.findViewById(R.id.hm_threads_value);
        verboseChip = view.findViewById(R.id.hm_chip_verbose);
        firstChip = view.findViewById(R.id.hm_chip_first);
        loopChip = view.findViewById(R.id.hm_chip_loop);
        outputFileField = view.findViewById(R.id.hm_output_file);
        extraFlagsField = view.findViewById(R.id.hm_extra_flags);
        previewText = view.findViewById(R.id.hm_preview);
        copyPreviewBtn = view.findViewById(R.id.hm_btn_copy_preview);
        runBtn = view.findViewById(R.id.hm_btn_run);
        stopBtn = view.findViewById(R.id.hm_btn_stop);
        progress = view.findViewById(R.id.hm_progress);
        foundCard = view.findViewById(R.id.hm_found_card);
        foundText = view.findViewById(R.id.hm_found_text);
        foundCopy = view.findViewById(R.id.hm_found_copy);
        terminalContainer = view.findViewById(R.id.hm_terminal_container);
        terminalBar = view.findViewById(R.id.hm_terminal_bar);
        terminalExpand = view.findViewById(R.id.hm_terminal_expand);
        terminalScroll = view.findViewById(R.id.hm_terminal_scroll);
        terminalText = view.findViewById(R.id.hm_terminal_text);

        wireToolSwitch();
        wireServiceField();
        wireCredentialModes();
        wireInputs();
        wireWordlistPickers();
        wireButtons();
        applyToolDefaults();
    }

    @Override
    public void onDestroyView() {
        stopRunningEngine();
        hydraEngine = null;
        medusaEngine = null;
        super.onDestroyView();
    }

    private void wireToolSwitch() {
        toolGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            boolean medusa = checkedIds.get(0) == R.id.hm_chip_medusa;
            if (medusa == isMedusa) return;
            isMedusa = medusa;
            applyToolDefaults();
        });
    }

    /** Service chip row, exactly like the dialog: one chip per
     *  service/module plus a Custom chip that reveals the free-text
     *  field. Picking a chip sets the service and auto-fills the
     *  port from the per-tool default-port map. */
    private void wireServiceField() {
        serviceGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (serviceChipsRebuilding || checkedIds.isEmpty()) return;
            Chip chip = group.findViewById(checkedIds.get(0));
            if (chip == null) return;
            if (chip == customServiceChip) {
                serviceIsCustom = true;
                customSvcLayout.setVisibility(View.VISIBLE);
            } else {
                serviceIsCustom = false;
                selectedService = String.valueOf(chip.getTag());
                customSvcLayout.setVisibility(View.GONE);
                String defPort = defaultPortFor(selectedService);
                if (!defPort.isEmpty()) portField.setText(defPort);
            }
            updatePreview();
        });
        customSvcField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (!serviceIsCustom) return;
                String svc = s.toString().trim();
                String defPort = defaultPortFor(svc);
                if (!defPort.isEmpty() && !defPort.equals(textOf(portField))) {
                    portField.setText(defPort);
                }
                updatePreview();
            }
        });
    }

    /** Rebuild the service chip row for the picked tool. The guard
     *  flag keeps the programmatic checks during the rebuild from
     *  firing the group listener. */
    private void buildServiceChips() {
        serviceChipsRebuilding = true;
        serviceGroup.removeAllViews();
        customServiceChip = null;
        String[] services = isMedusa ? MEDUSA_SERVICES : HydraDialog.HYDRA_SERVICES;
        Chip toCheck = null;
        Chip sshChip = null;
        for (String svc : services) {
            Chip chip = makeServiceChip(svc, serviceLabel(svc));
            serviceGroup.addView(chip);
            if (svc.equals(selectedService)) toCheck = chip;
            if (svc.equals("ssh")) sshChip = chip;
        }
        Chip custom = makeServiceChip(SERVICE_CUSTOM_TAG, getString(R.string.hydra_svc_custom));
        serviceGroup.addView(custom);
        customServiceChip = custom;
        if (serviceIsCustom) {
            custom.setChecked(true);
        } else {
            if (toCheck == null) {
                selectedService = "ssh";
                toCheck = sshChip;
            }
            if (toCheck != null) toCheck.setChecked(true);
        }
        serviceChipsRebuilding = false;
    }

    private Chip makeServiceChip(String service, String label) {
        Chip chip = new Chip(context);
        chip.setId(View.generateViewId());
        chip.setText(label);
        chip.setTag(service);
        chip.setCheckable(true);
        return chip;
    }

    /** Display label for a service/module value. Short acronyms stay
     *  uppercase, the rest get a capital first letter; the few names
     *  the dialog spells differently are mapped to the dialog spelling. */
    private static String serviceLabel(String service) {
        switch (service) {
            case "mysql": return "MySQL";
            case "postgres": return "PostgreSQL";
            case "mssql": return "MSSQL";
            case "smbnt": return "SMB";
            case "ldap2": return "LDAP2";
            case "ldap2s": return "LDAP2S";
            case "ldap3": return "LDAP3";
            case "smtp-vrfy": return "SMTP-VRFY";
            case "pcanywhere": return "pcAnywhere";
            default: break;
        }
        if (service.length() <= 4) return service.toUpperCase(Locale.US);
        return service.substring(0, 1).toUpperCase(Locale.US) + service.substring(1);
    }

    /** Each credential picks its own mode: the login field and the
     *  password field each have a List | Single pair, so a single
     *  username can be combined with a password list (and the other
     *  three combinations). Toggling one field leaves the other alone. */
    private void wireCredentialModes() {
        loginModeGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            boolean list = checkedIds.get(0) == R.id.hm_login_mode_list;
            loginWlLayout.setVisibility(list ? View.VISIBLE : View.GONE);
            singleLoginLayout.setVisibility(list ? View.GONE : View.VISIBLE);
            updatePreview();
        });
        passwordModeGroup.setOnCheckedStateChangeListener((group, checkedIds) -> {
            if (checkedIds.isEmpty()) return;
            boolean list = checkedIds.get(0) == R.id.hm_password_mode_list;
            passwordWlLayout.setVisibility(list ? View.VISIBLE : View.GONE);
            singlePasswordLayout.setVisibility(list ? View.GONE : View.VISIBLE);
            updatePreview();
        });
    }

    /** Wordlists come from the same picker HydraDialog uses (WordlistStore).
     *  Tap a list field -> the picker; the chroot path of the picked list
     *  (already shell-quoted by the store) goes into the command.
     *  The Manage button in the picker jumps to the Wordlists menu, where
     *  lists are downloaded / generated / imported. */
    private void wireWordlistPickers() {
        loginWlField.setInputType(android.text.InputType.TYPE_NULL);
        loginWlField.setKeyListener(null);
        loginWlField.setOnClickListener(v -> {
            if (context == null || activity == null) return;
            WordlistPickerDialog.show(context, activity, core,
                    context.getString(R.string.hydra_pick_logins),
                    new WordlistCategory[] { WordlistCategory.USERNAME, WordlistCategory.PASSWORD },
                    pickedLoginList == null ? null : pickedLoginList.getName(),
                    wl -> {
                        pickedLoginList = wl;
                        loginWlField.setText(wl.getName(), false);
                        updatePreview();
                    });
        });

        passwordWlField.setInputType(android.text.InputType.TYPE_NULL);
        passwordWlField.setKeyListener(null);
        passwordWlField.setOnClickListener(v -> {
            if (context == null || activity == null) return;
            WordlistPickerDialog.show(context, activity, core,
                    context.getString(R.string.hydra_pick_passwords),
                    new WordlistCategory[] { WordlistCategory.PASSWORD, WordlistCategory.WIFI },
                    pickedPasswordList == null ? null : pickedPasswordList.getName(),
                    wl -> {
                        pickedPasswordList = wl;
                        passwordWlField.setText(wl.getName(), false);
                        updatePreview();
                    });
        });
    }

    private void wireInputs() {
        TextWatcher previewWatcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                updatePreview();
            }
        };
        targetField.addTextChangedListener(previewWatcher);
        portField.addTextChangedListener(previewWatcher);
        singleLoginField.addTextChangedListener(previewWatcher);
        singlePasswordField.addTextChangedListener(previewWatcher);
        outputFileField.addTextChangedListener(previewWatcher);
        extraFlagsField.addTextChangedListener(previewWatcher);

        threadsSlider.addOnChangeListener((slider, value, fromUser) -> {
            int v = clampThreads(Math.round(value));
            threadsValue.setText(String.valueOf(v));
            updatePreview();
        });
        verboseChip.setOnCheckedChangeListener((button, checked) -> updatePreview());
        firstChip.setOnCheckedChangeListener((button, checked) -> updatePreview());
        loopChip.setOnCheckedChangeListener((button, checked) -> updatePreview());
    }

    private void wireButtons() {
        runBtn.setOnClickListener(v -> runTool());
        stopBtn.setOnClickListener(v -> stopRunningEngine());
        copyPreviewBtn.setOnClickListener(v -> copyText(previewText.getText().toString()));
        foundCopy.setOnClickListener(v -> copyText(foundText.getText().toString()));
        terminalExpand.setOnClickListener(v -> {
            if (context != null && activity != null) {
                HydraDialog.openFullscreen(context, activity, terminalText);
            }
        });
    }

    /** Tool switch resets the form to the defaults for that tool. */
    private void applyToolDefaults() {
        selectedService = "ssh";
        serviceIsCustom = false;
        buildServiceChips();
        String[] services = isMedusa ? MEDUSA_SERVICES : HydraDialog.HYDRA_SERVICES;
        customSvcField.setSimpleItems(services);
        customSvcField.setText("", false);
        customSvcLayout.setVisibility(View.GONE);
        portField.setText(defaultPortFor("ssh"));
        int defThreads = isMedusa ? THREADS_DEFAULT_MEDUSA : THREADS_DEFAULT_HYDRA;
        threadsSlider.setValue(defThreads);
        threadsValue.setText(String.valueOf(defThreads));
        updatePreview();
    }

    private String defaultPortFor(String service) {
        Map<String, String> map = isMedusa ? MEDUSA_DEFAULT_PORTS : HydraDialog.DEFAULT_PORTS;
        String port = map.get(service);
        return port == null ? "" : port;
    }

    private FormValues collectForm() {
        FormValues f = new FormValues();
        f.service = serviceIsCustom ? textOf(customSvcField).trim() : selectedService;
        f.target = textOf(targetField).trim();
        f.port = textOf(portField).trim();
        // Picked from the wordlist store; the guest path comes back
        // already shell-quoted — do not quote it again.
        f.loginWordlist = pickedLoginList == null ? "" : wordlistStore.guestPathQuoted(pickedLoginList);
        f.passwordWordlist = pickedPasswordList == null ? "" : wordlistStore.guestPathQuoted(pickedPasswordList);
        f.singleLogin = textOf(singleLoginField).trim();
        f.singlePassword = textOf(singlePasswordField).trim();
        f.threads = clampThreads(Math.round(threadsSlider.getValue()));
        f.verbose = verboseChip.isChecked();
        f.stopOnFirst = firstChip.isChecked();
        f.loopUsers = loopChip.isChecked();
        f.outputFile = quotePath(textOf(outputFileField).trim());
        f.extraFlags = textOf(extraFlagsField).trim();
        return f;
    }

    private HydraEngine.Spec buildHydraSpec(FormValues f) {
        boolean loginList = loginWlLayout.getVisibility() == View.VISIBLE;
        boolean passwordList = passwordWlLayout.getVisibility() == View.VISIBLE;
        return new HydraEngine.Spec(
                f.service, f.target, f.port,
                loginList ? f.loginWordlist : "",
                passwordList ? f.passwordWordlist : "",
                loginList ? "" : f.singleLogin,
                passwordList ? "" : f.singlePassword,
                f.threads, f.verbose, f.stopOnFirst,
                f.outputFile, f.extraFlags, f.loopUsers
        );
    }

    private MedusaEngine.Spec buildMedusaSpec(FormValues f) {
        boolean loginList = loginWlLayout.getVisibility() == View.VISIBLE;
        boolean passwordList = passwordWlLayout.getVisibility() == View.VISIBLE;
        return new MedusaEngine.Spec(
                f.service, f.target, f.port,
                loginList ? f.loginWordlist : "",
                passwordList ? f.passwordWordlist : "",
                loginList ? "" : f.singleLogin,
                passwordList ? "" : f.singlePassword,
                f.threads, f.verbose, f.stopOnFirst,
                f.outputFile, f.extraFlags, f.loopUsers
        );
    }

    private void updatePreview() {
        if (previewText == null) return;
        FormValues f = collectForm();
        String cmd = isMedusa
                ? MedusaEngine.buildCommand(buildMedusaSpec(f))
                : HydraEngine.buildCommand(buildHydraSpec(f));
        previewText.setText(cmd);
    }

    private void runTool() {
        FormValues f = collectForm();
        boolean passwordList = passwordWlLayout.getVisibility() == View.VISIBLE;
        if (f.service.isEmpty() || f.service.length() < 2) {
            toast(R.string.hydra_validation_target);
            return;
        }
        if (f.target.isEmpty() || f.target.length() < 3) {
            toast(R.string.hydra_validation_target);
            return;
        }
        // A password must be supplied in whatever form its own field
        // is in; the login is optional (some services take none).
        if (passwordList) {
            if (f.passwordWordlist.isEmpty()) {
                toast(R.string.hydra_validation_wordlist);
                return;
            }
        } else if (f.singlePassword.isEmpty()) {
            toast(R.string.hydra_validation_creds);
            return;
        }

        setRunningUi(true);
        statusText.setText(R.string.hm_status_running);
        terminalText.setText("");
        terminalContainer.setVisibility(View.VISIBLE);
        terminalBar.setVisibility(View.VISIBLE);
        foundCard.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        progress.setIndeterminate(true);

        if (isMedusa) {
            MedusaEngine.Spec spec = buildMedusaSpec(f);
            MedusaEngine.Listener listener = new MedusaEngine.Listener() {
                @Override
                public void onState(MedusaEngine.State state, String reason) {
                    handleEngineState(state.name(), reason);
                }

                @Override
                public void onLine(String line) {
                    postUi(() -> appendLine(line));
                }

                @Override
                public void onCredentialFound(MedusaEngine.Credential credential) {
                    postUi(() -> showFound(credential.host, credential.login, credential.password));
                }

                @Override
                public void onProgress(int tried, int total) {
                }
            };
            medusaEngine = new MedusaEngine(activity, context, core, listener);
            medusaEngine.start(spec);
        } else {
            HydraEngine.Spec spec = buildHydraSpec(f);
            HydraEngine.Listener listener = new HydraEngine.Listener() {
                @Override
                public void onState(HydraEngine.State state, String reason) {
                    handleEngineState(state.name(), reason);
                }

                @Override
                public void onLine(String line) {
                    postUi(() -> appendLine(line));
                }

                @Override
                public void onCredentialFound(HydraEngine.Credential credential) {
                    postUi(() -> showFound(credential.host, credential.login, credential.password));
                }

                @Override
                public void onProgress(int tried, int total) {
                    postUi(() -> {
                        if (total > 0) {
                            progress.setIndeterminate(false);
                            progress.setProgressCompat(Math.min(100, tried * 100 / total), true);
                        }
                    });
                }
            };
            hydraEngine = new HydraEngine(activity, context, core, listener);
            hydraEngine.start(spec);
        }
    }

    private void handleEngineState(String stateName, String reason) {
        switch (stateName) {
            case "RUNNING":
                setRunningUi(true);
                statusText.setText(R.string.hm_status_running);
                break;
            case "FOUND":
                setRunningUi(false);
                statusText.setText(R.string.hydra_dialog_status_found);
                break;
            case "NONE":
                setRunningUi(false);
                statusText.setText(R.string.hm_status_none);
                break;
            case "KILLED":
                setRunningUi(false);
                statusText.setText(R.string.hm_status_stopped);
                break;
            case "ERROR":
            default:
                setRunningUi(false);
                statusText.setText(reason == null || reason.isEmpty()
                        ? getString(R.string.hm_status_error) : reason);
                break;
        }
    }

    private void setRunningUi(boolean running) {
        runBtn.setVisibility(running ? View.GONE : View.VISIBLE);
        stopBtn.setVisibility(running ? View.VISIBLE : View.GONE);
        if (!running) progress.setVisibility(View.GONE);
    }

    private void stopRunningEngine() {
        if (hydraEngine != null && hydraEngine.state() == HydraEngine.State.RUNNING) {
            hydraEngine.kill();
        }
        if (medusaEngine != null && medusaEngine.state() == MedusaEngine.State.RUNNING) {
            medusaEngine.kill();
        }
    }

    private void appendLine(String line) {
        if (terminalText == null || line == null) return;
        terminalText.append(line);
        terminalText.append("\n");
        terminalScroll.post(() -> terminalScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void showFound(String host, String login, String password) {
        foundText.setText(host + " -> " + login + " : " + password);
        foundCard.setVisibility(View.VISIBLE);
    }

    private void postUi(Runnable r) {
        if (activity != null) activity.runOnUiThread(r);
        else r.run();
    }

    private void copyText(String text) {
        if (text == null || text.isEmpty() || context == null) return;
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return;
        cm.setPrimaryClip(ClipData.newPlainText("hydra-medusa", text));
        toast(R.string.hydra_toast_copied);
    }

    private void toast(int resId) {
        if (context != null) Toast.makeText(context, resId, Toast.LENGTH_SHORT).show();
    }

    private static String textOf(TextInputEditText field) {
        return field.getText() == null ? "" : field.getText().toString();
    }

    private static String textOf(MaterialAutoCompleteTextView field) {
        return field.getText() == null ? "" : field.getText().toString();
    }

    /** Quote a user-typed file path so spaces / shell metachars cannot split it. */
    private static String quotePath(String path) {
        if (path.isEmpty()) return "";
        return HydraEngine.shellQuote(path);
    }

    private static int clampThreads(int value) {
        if (value < THREADS_MIN) return THREADS_MIN;
        if (value > THREADS_MAX) return THREADS_MAX;
        return value;
    }
}
