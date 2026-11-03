/* ASTRO_KISMET_WEBUI_TOOLS_VERSION = 6 */
/* Astro Tools untuk WebUI Kismet — injeksi Fase 2 (2026-11-02).
 *
 * Dipasang BUKAN sebagai modul /dynamic.js Kismet (mekanisme
 * registrasi modul tidak terdokumentasi di doc resmi), melainkan
 * sebagai <script> biasa yang ditambahkan ke index.html WebUI
 * oleh skrip patch (backup + pasang-ulang otomatis tiap start
 * tools bridge — tahan terhadap update apt Kismet).
 *
 * Yang dipakai dari API resmi Kismet (doc "Extending the WebUI"):
 * - Daftar perangkat = jQuery DataTables; data baris diambil lewat
 *   API DataTable (.row(tr).data()), BUKAN scraping teks sel —
 *   jadi tahan terhadap sorting/paging/render ulang.
 * - Field record: kismet.device.base.macaddr (MAC),
 *   kismet.device.base.channel (channel WiFi),
 *   kismet.device.base.phyname ('IEEE802.11' = WiFi).
 *
 * Interaksi: LONG-PRESS (touch ~600 ms; klik kanan sebagai
 * fallback non-touch) membuka menu aksi; menu memanggil bridge
 * Astro di 127.0.0.1:18082. v3 (pivot): long-press di daftar
 * terbukti tidak jalan di perangkat (v1+v2), jadi sumber data
 * utama = DOM halaman detail (ketuk baris → detail → long-press
 * di detail); baris DataTables tetap didukung bila event-nya
 * sampai.
 * v4: aksi WiFi mengunci channel lewat API bawaan Kismet
 * (set_channel.cmd) SEBELUM memanggil bridge — TANPA SIGSTOP,
 * TANPA iw, TANPA down interface (interface tidak pernah
 * di-down). Sesudah job selesai/dihentikan, JS menghidupkan
 * hopping lagi (set_hop.cmd). Kunci memakai sesi login WebUI
 * (admin) sehingga tidak butuh password.
 * v5 (bundle SSID+mdk4+handshake, 2026-11-03): menu membedakan
 * AP vs client — client hanya dapat deauth aireplay surgical
 * (-a AP -c client, AP dibaca dari halaman detail, gagal
 * terlihat bila tidak ada) dan ambil handshake; mdk4 tidak
 * ditawarkan untuk client. Tombol "Ambil handshake" memanggil
 * endpoint bridge baru yang mengekstrak handshake dari pcapng
 * Kismet ke /sdcard/Astro/captured (daftar handshake Astro).
 * v6 (durasi adjustable, 2026-11-03): tombol berdurasi
 * (mdk4/aireplay/airodump) punya kolom angka sendiri (detik,
 * 1..300 sesuai clamp bridge) — tidak lagi hardcode 30/60.
 * Nilai terakhir diingat per aksi via localStorage (guard
 * try/catch: WebView tanpa DOM storage tetap bisa disetel,
 * hanya tidak diingat). Label tombol ikut durasi terpilih.
 * dengan token dari cookie astro_tools_token (dipasang panel
 * Kismet saat membuka WebUI — buka WebUI dari panel, bukan dari
 * browser langsung, supaya cookie ada).
 *
 * Keamanan: hanya localhost; token wajib (bridge menolak tanpa
 * token); aksi WiFi hanya pada MAC baris yang ditekan; tidak ada
 * aksi otomatis tanpa tap pengguna.
 */
(function () {
    'use strict';

    var BRIDGE = 'http://127.0.0.1:18082';
    var LONGPRESS_MS = 600;

    function token() {
        var m = document.cookie.match(/(?:^|; )astro_tools_token=([^;]*)/);
        return m ? decodeURIComponent(m[1]) : null;
    }

    function call(path, body) {
        var t = token();
        if (!t) {
            return Promise.reject(new Error(
                'Token bridge tidak ada — buka WebUI dari panel ' +
                'Kismet (tombol Buka WebUI), bukan dari browser.'));
        }
        return fetch(BRIDGE + path, {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'X-Astro-Token': t
            },
            body: JSON.stringify(body || {})
        }).then(function (res) {
            return res.json().then(function (obj) {
                if (!res.ok || !obj.ok) {
                    throw new Error(obj.error || ('HTTP ' + res.status));
                }
                return obj;
            });
        });
    }

    /* ---------------- API bawaan Kismet (v4) ---------------- */
    /* Dijalankan dari origin WebUI (127.0.0.1:2501) sehingga
     * cookie sesi login admin ikut terkirim — tidak butuh
     * password. Channel dikunci Kismet sendiri selagi source
     * tetap terbuka; interface TIDAK PERNAH di-down. */

    function kismetFetch(path, body) {
        return fetch(path, {
            method: body ? 'POST' : 'GET',
            headers: { 'Content-Type': 'application/json' },
            body: body ? JSON.stringify(body) : undefined
        }).then(function (res) {
            return res.json().then(function (obj) {
                if (!res.ok) {
                    throw new Error('Kismet HTTP ' + res.status);
                }
                return obj;
            });
        });
    }

    function findWifiSourceUuid() {
        return kismetFetch('/datasource/all_sources.json').then(function (data) {
            var list = Array.isArray(data) ? data :
                (data && Array.isArray(data.sources) ? data.sources :
                Object.keys(data || {}).map(function (k) { return data[k]; }));
            var fallback = null;
            for (var i = 0; i < list.length; i++) {
                var r = list[i] || {};
                var uuid = r['kismet.datasource.uuid'] || '';
                if (!uuid) continue;
                var name = r['kismet.datasource.name'] ||
                           r['kismet.datasource.interface'] || '';
                var type = r['kismet.datasource.type'] || '';
                if (name === 'wlan0') return uuid;
                if (!fallback && /wifi/i.test(type) &&
                    JSON.stringify(r).indexOf('wlan0') !== -1) {
                    fallback = uuid;
                }
            }
            if (fallback) return fallback;
            throw new Error('Source wlan0 tidak ditemukan di Kismet.');
        });
    }

    function lockChannel(uuid, channel) {
        return kismetFetch('/datasource/by-uuid/' +
            encodeURIComponent(uuid) + '/set_channel.cmd',
            { channel: String(channel) });
    }

    function resumeHopping(uuid) {
        return kismetFetch('/datasource/by-uuid/' +
            encodeURIComponent(uuid) + '/set_hop.cmd', {});
    }

    function bridgeGet(path) {
        var t = token();
        if (!t) return Promise.reject(new Error('Token bridge tidak ada.'));
        return fetch(BRIDGE + path, {
            headers: { 'X-Astro-Token': t }
        }).then(function (res) {
            return res.json().then(function (obj) {
                if (!res.ok || !obj.ok) {
                    throw new Error(obj.error || ('HTTP ' + res.status));
                }
                return obj;
            });
        });
    }

    /* Job WiFi aktif (v4): hanya satu yang memegang kunci channel.
     * Loop polling menghidupkan hopping lagi HANYA bila job yang
     * dipoll masih yang aktif — job baru yang menimpa mengambil
     * alih kunci (tanpa set_hop prematur dari loop lama). */
    var activeWifi = null; /* {job, uuid} */

    function pollWifiJob(jobId) {
        var fails = 0;
        var timer = setInterval(function () {
            bridgeGet('/jobs').then(function (obj) {
                fails = 0;
                /* Entri job TIDAK dihapus bridge saat selesai —
                 * yang menandai selesai adalah running=false. */
                var alive = false;
                var jobs = obj.jobs || [];
                for (var i = 0; i < jobs.length; i++) {
                    if (jobs[i].id === jobId && jobs[i].running) {
                        alive = true; break;
                    }
                }
                if (!alive) {
                    clearInterval(timer);
                    if (activeWifi && activeWifi.job === jobId) {
                        var uuid = activeWifi.uuid;
                        activeWifi = null;
                        resumeHopping(uuid).catch(function () {});
                    }
                }
            }).catch(function () {
                fails++;
                if (fails >= 3) {
                    clearInterval(timer);
                    if (activeWifi && activeWifi.job === jobId) {
                        var uuid = activeWifi.uuid;
                        activeWifi = null;
                        resumeHopping(uuid).catch(function () {});
                    }
                }
            });
        }, 2000);
    }

    function runWifiAction(label, bridgePath, bridgeBody, channel) {
        showResult('Mengunci channel ' + channel + ' via Kismet…');
        var uuid = null;
        return findWifiSourceUuid().then(function (u) {
            uuid = u;
            return lockChannel(uuid, channel);
        }).then(function () {
            showResult('Channel ' + channel + ' terkunci, menjalankan ' +
                label + '…');
            var body = {};
            for (var k in bridgeBody) body[k] = bridgeBody[k];
            body.kismet_locked = true;
            return call(bridgePath, body).catch(function (e) {
                /* Kunci sudah didapat tapi tool gagal start:
                 * kembalikan hopping, jangan tinggal terkunci. */
                resumeHopping(uuid).catch(function () {});
                throw e;
            });
        }).then(function (obj) {
            if (obj.job) {
                lastJobId = obj.job;
                activeWifi = { job: obj.job, uuid: uuid };
                pollWifiJob(obj.job);
            }
            return obj;
        });
    }

    /* ---------------- menu ---------------- */

    var menuEl = null;
    var resultEl = null;
    var lastJobId = null;
    var current = null; /* {mac, channel, phy, wifi} */

    function ensureMenu() {
        if (menuEl) return;
        menuEl = document.createElement('div');
        menuEl.id = 'astro-tools-menu';
        menuEl.style.cssText = [
            'position:fixed', 'left:8px', 'right:8px', 'bottom:8px',
            'z-index:99999', 'background:#0B0B10', 'color:#E8E8EA',
            'border:1px solid #D4AF37', 'border-radius:10px',
            'padding:12px', 'font-family:sans-serif', 'font-size:14px',
            'display:none'
        ].join(';');
        menuEl.innerHTML =
            '<div id="astro-tools-title" style="font-weight:bold;color:#D4AF37"></div>' +
            '<div id="astro-tools-btns" style="margin:8px 0"></div>' +
            '<pre id="astro-tools-result" style="white-space:pre-wrap;word-break:break-word;max-height:160px;overflow:auto;margin:0"></pre>' +
            '<button id="astro-tools-close" style="margin-top:8px">Tutup</button>';
        document.body.appendChild(menuEl);
        resultEl = document.getElementById('astro-tools-result');
        document.getElementById('astro-tools-close').addEventListener('click', hideMenu);
    }

    function showResult(text) {
        if (resultEl) resultEl.textContent = text;
    }

    function hideMenu() {
        if (menuEl) menuEl.style.display = 'none';
        current = null;
    }

    function addBtn(label, fn) {
        var b = document.createElement('button');
        b.textContent = label;
        b.style.cssText = 'margin:2px;padding:6px 10px';
        b.addEventListener('click', function () {
            b.disabled = true;
            showResult('Menjalankan ' + label + '…');
            fn().then(function (obj) {
                if (obj.job) lastJobId = obj.job;
                showResult(JSON.stringify(obj, null, 2));
            }).catch(function (e) {
                showResult('Gagal: ' + e.message);
            }).finally(function () {
                b.disabled = false;
            });
        });
        document.getElementById('astro-tools-btns').appendChild(b);
    }

    /* Durasi adjustable (v6): kolom angka per tombol berdurasi.
     * Batas 1..300 = clamp bridge (DEFAULT 30, MAX 300); nilai
     * di luar batas dijepit, bukan ditolak diam-diam. */
    var DUR_MIN = 1;
    var DUR_MAX = 300;

    function clampDur(v, defVal) {
        var n = parseInt(v, 10);
        if (isNaN(n)) return defVal;
        if (n < DUR_MIN) return DUR_MIN;
        if (n > DUR_MAX) return DUR_MAX;
        return n;
    }

    function durStored(key, defVal) {
        try {
            var v = window.localStorage.getItem('astro_tools_dur_' + key);
            if (v !== null) return clampDur(v, defVal);
        } catch (e) {}
        return defVal;
    }

    function durSave(key, val) {
        try {
            window.localStorage.setItem('astro_tools_dur_' + key, String(val));
        } catch (e) {}
    }

    /* Baris: [kolom detik] [tombol]. Label tombol ikut durasi
     * terpilih; fn menerima durasi (detik) saat tap. */
    function addTimedBtn(labelBase, key, defVal, fn) {
        var row = document.createElement('div');
        row.style.cssText = 'margin:2px 0';
        var input = document.createElement('input');
        input.type = 'number';
        input.min = String(DUR_MIN);
        input.max = String(DUR_MAX);
        input.value = String(durStored(key, defVal));
        input.style.cssText = 'width:64px;padding:6px;margin-right:4px';
        input.title = 'Durasi (detik, 1-300)';
        var b = document.createElement('button');
        b.style.cssText = 'padding:6px 10px';
        function syncLabel() {
            b.textContent = labelBase + ' (' +
                clampDur(input.value, defVal) + ' dtk)';
        }
        input.addEventListener('change', function () {
            var d = clampDur(input.value, defVal);
            input.value = String(d);
            durSave(key, d);
            syncLabel();
        });
        syncLabel();
        b.addEventListener('click', function () {
            var d = clampDur(input.value, defVal);
            input.value = String(d);
            durSave(key, d);
            syncLabel();
            b.disabled = true;
            showResult('Menjalankan ' + labelBase + ' (' + d + ' dtk)…');
            fn(d).then(function (obj) {
                if (obj.job) lastJobId = obj.job;
                showResult(JSON.stringify(obj, null, 2));
            }).catch(function (e) {
                showResult('Gagal: ' + e.message);
            }).finally(function () {
                b.disabled = false;
            });
        });
        row.appendChild(input);
        row.appendChild(b);
        document.getElementById('astro-tools-btns').appendChild(row);
    }

    /* ---------------- sumber data perangkat ---------------- */
    /* Jalur 1: baris DataTables (daftar). Jalur 2 (v3, pivot):
     * DOM halaman detail — dibaca dari label yang sudah tampil
     * (MAC Address / Channel / Type), tanpa butuh DataTables dan
     * tanpa butuh jQuery. */

    var MAC_RE = /^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$/;

    function detailField(label) {
        var all = document.getElementsByTagName('*');
        for (var i = 0; i < all.length; i++) {
            var el = all[i];
            var t = (el.firstChild && el.firstChild.nodeType === 3)
                ? el.firstChild.nodeValue.trim() : '';
            if (t !== label) continue;
            var sib = el.nextElementSibling;
            if (sib && sib.textContent.trim()) return sib.textContent.trim();
            var p = el.parentNode;
            if (p) {
                var kids = p.children;
                for (var j = 0; j < kids.length; j++) {
                    if (kids[j] !== el && kids[j].textContent.trim()) {
                        return kids[j].textContent.trim();
                    }
                }
            }
        }
        return '';
    }

    function readDetailDevice() {
        var mac = detailField('MAC Address');
        if (!mac || !MAC_RE.test(mac)) return null;
        var ch = parseInt(detailField('Channel'), 10);
        if (isNaN(ch)) ch = null;
        var type = detailField('Type') || '';
        var wifi = /wi-fi/i.test(type) || (ch !== null);
        var known = wifi || /bluetooth|btle|\bble\b/i.test(type);
        /* AP vs client: hanya dari label Type halaman detail
         * (client dicek dulu supaya string gabungan tidak salah
         * jadi AP). AP-asosiasi untuk client: coba beberapa
         * label, yang pertama lolos regex MAC yang dipakai;
         * bila tidak ada, ap = '' dan tombol client fail-visible
         * (tidak ditebak). */
        var kind = 'unknown';
        if (/wi-fi/i.test(type)) {
            if (/client/i.test(type)) kind = 'client';
            else if (/\bAP\b/i.test(type)) kind = 'ap';
        }
        var ap = '';
        if (kind === 'client') {
            var labels = ['Associated AP', 'AP', 'BSSID', 'Associated BSSID'];
            for (var i = 0; i < labels.length; i++) {
                var v = detailField(labels[i]);
                if (v && MAC_RE.test(v)) { ap = v; break; }
            }
        } else if (kind === 'ap') {
            ap = mac;
        }
        return { mac: mac, channel: ch, wifi: wifi, known: known,
            kind: kind, ap: ap };
    }

    function isDetailPage() {
        return !!document.body &&
            document.body.textContent.indexOf('MAC Address') !== -1;
    }

    function showMessage(text) {
        document.getElementById('astro-tools-title').textContent = 'Astro Tools';
        document.getElementById('astro-tools-btns').innerHTML = '';
        showResult(text);
        menuEl.style.display = 'block';
    }

    function showDeviceMenu(mac, channel, wifi, kind, ap) {
        current = { mac: mac, channel: channel, wifi: wifi };
        var titleKind = wifi ? (kind === 'ap' ? 'WiFi AP' :
            kind === 'client' ? 'WiFi client' : 'WiFi') : 'BT';
        document.getElementById('astro-tools-title').textContent =
            'Astro Tools — ' + mac + ' (' + titleKind +
            (wifi ? ', ch ' + channel : '') + ')';
        var btns = document.getElementById('astro-tools-btns');
        btns.innerHTML = '';
        lastJobId = null;
        if (wifi) {
            if (!channel) {
                showResult('Channel tidak diketahui untuk perangkat ini — aksi WiFi butuh channel.');
            } else if (kind === 'client') {
                /* Client: HANYA surgical — aireplay -a AP -c
                 * client (satu client itu saja yang jatuh) dan
                 * ambil handshake. mdk4/airodump tidak ditawarkan
                 * untuk client (tidak surgical). Tanpa AP yang
                 * terbaca, kedua tombol dibatalkan fail-visible. */
                if (!ap) {
                    showResult('AP tidak dikenali dari halaman detail ini — deauth client dan ambil handshake dibatalkan (tidak ditebak).');
                } else {
                    addTimedBtn('Deauth client (aireplay)', 'client_deauth', 30, function (d) {
                        return runWifiAction('Deauth client (aireplay, ' + d + ' dtk)',
                            '/wifi/deauth/aireplay',
                            { bssid: ap, client: mac, channel: channel, duration: d },
                            channel);
                    });
                    addBtn('Ambil handshake', function () {
                        return call('/wifi/handshake/extract',
                            { bssid: ap, client: mac });
                    });
                }
            } else {
                /* AP atau WiFi tak dikenal: perilaku lama (mdk4
                 * terskop -b, aireplay broadcast ke client AP itu,
                 * airodump) + tombol ambil handshake. */
                addTimedBtn('Deauth mdk4', 'mdk4', 30, function (d) {
                    return runWifiAction('Deauth mdk4 (' + d + ' dtk)',
                        '/wifi/deauth/mdk4',
                        { bssid: mac, channel: channel, duration: d },
                        channel);
                });
                addTimedBtn('Deauth aireplay', 'ap_aireplay', 30, function (d) {
                    return runWifiAction('Deauth aireplay (' + d + ' dtk)',
                        '/wifi/deauth/aireplay',
                        { bssid: mac, channel: channel, duration: d },
                        channel);
                });
                addTimedBtn('Tangkap airodump', 'airodump', 60, function (d) {
                    return runWifiAction('Tangkap airodump (' + d + ' dtk)',
                        '/wifi/capture/airodump',
                        { bssid: mac, channel: channel, duration: d },
                        channel);
                });
                addBtn('Ambil handshake', function () {
                    return call('/wifi/handshake/extract', { bssid: mac });
                });
            }
        } else {
            addBtn('l2ping', function () {
                return call('/bt/l2ping', { mac: mac, count: 4 });
            });
            addBtn('SDP browse', function () {
                return call('/bt/sdptool', { mac: mac });
            });
        }
        addBtn('Berhenti job terakhir', function () {
            if (!lastJobId) {
                return Promise.reject(new Error('Belum ada job dari menu ini.'));
            }
            var stopped = lastJobId;
            return call('/jobs/stop', { id: stopped }).then(function (obj) {
                if (activeWifi && activeWifi.job === stopped) {
                    var uuid = activeWifi.uuid;
                    activeWifi = null;
                    resumeHopping(uuid).catch(function () {});
                }
                return obj;
            });
        });
        if (wifi && channel) showResult('Pilih aksi.');
        menuEl.style.display = 'block';
    }

    function openMenu(tr) {
        ensureMenu();
        /* Jalur 1: baris DataTables — hanya bila tabelnya memang
         * sudah jadi DataTable (isDataTable, tanpa menginisiasi
         * tabel biasa milik Kismet). */
        if (tr && typeof window.jQuery !== 'undefined' &&
            typeof window.jQuery.fn.DataTable !== 'undefined') {
            var data = null;
            try {
                var tbl = window.jQuery(tr).closest('table');
                if (window.jQuery.fn.dataTable.isDataTable(tbl)) {
                    data = tbl.DataTable().row(tr).data();
                }
            } catch (e) {
                data = null;
            }
            if (data) {
                var mac = data['kismet.device.base.macaddr'] ||
                          data['kismet.device.base.mac'] || '';
                if (!mac) {
                    showMessage('MAC tidak terbaca dari baris ini.');
                    return;
                }
                var channel = data['kismet.device.base.channel'];
                var phy = data['kismet.device.base.phyname'] || '';
                var isWifi = (phy === 'IEEE802.11');
                showDeviceMenu(mac, channel, isWifi,
                    isWifi ? 'unknown' : 'bt', mac);
                return;
            }
            /* data null → jatuh ke jalur detail (press bisa saja
             * di baris tabel halaman detail). */
        }
        /* Jalur 2: DOM halaman detail. */
        var d = readDetailDevice();
        if (d) {
            if (!d.known) {
                showMessage('Tipe perangkat tidak dikenali dari halaman detail ini.');
                return;
            }
            showDeviceMenu(d.mac, d.channel, d.wifi, d.kind, d.ap);
            return;
        }
        /* Jalur 3: fail-visible. */
        if (!tr) {
            showMessage('Buka halaman detail device dulu (ketuk baris), lalu long-press di sana.');
        } else if (typeof window.jQuery === 'undefined' ||
            typeof window.jQuery.fn.DataTable === 'undefined') {
            showMessage('DataTables tidak tersedia di halaman ini.');
        } else {
            showMessage('Data baris tidak terbaca.');
        }
    }

    /* ---------------- long-press ---------------- */
    /* Delegasi di document (fase capture, jadi handler halaman
     * tidak bisa membungkamnya dengan stopPropagation) — tahan
     * terhadap render ulang DataTables. Touch: timer 600 ms;
     * gerak (scroll) = batal; touchcancel hanya mematikan timer,
     * sedangkan touchend tetap membuka menu bila press sudah
     * >= 600 ms (jaga-jaga timer dibunuh WebView saat long-press).
     * Non-touch: klik kanan (contextmenu) pada baris. */

    var pressTimer = null;
    var pressRow = null;
    var pressStart = 0;

    function rowOf(target) {
        var el = target;
        if (!el) return null;
        if (el.closest) {
            var tr = el.closest('tr');
            if (tr) return tr;
        }
        while (el && el.tagName !== 'TR') el = el.parentNode;
        return el || null;
    }

    function clearPress() {
        if (pressTimer) {
            clearTimeout(pressTimer);
            pressTimer = null;
        }
        pressRow = null;
        pressStart = 0;
    }

    function clearTimerOnly() {
        if (pressTimer) {
            clearTimeout(pressTimer);
            pressTimer = null;
        }
    }

    document.addEventListener('touchstart', function (ev) {
        clearPress();
        var tr = ev.target ? rowOf(ev.target) : null;
        if (!tr && !isDetailPage()) return;
        pressRow = tr;
        pressStart = Date.now();
        pressTimer = setTimeout(function () {
            pressTimer = null;
            openMenu(pressRow);
            pressRow = null;
            pressStart = 0;
        }, LONGPRESS_MS);
    }, { passive: true, capture: true });

    document.addEventListener('touchend', function () {
        if (pressStart &&
            (Date.now() - pressStart >= LONGPRESS_MS)) {
            var tr = pressRow;
            clearPress();
            openMenu(tr);
            return;
        }
        clearPress();
    }, { passive: true, capture: true });
    document.addEventListener('touchmove', clearPress, { passive: true, capture: true });
    document.addEventListener('touchcancel', clearTimerOnly, { passive: true, capture: true });

    document.addEventListener('contextmenu', function (ev) {
        var tr = ev.target ? rowOf(ev.target) : null;
        if (!tr && !isDetailPage()) return;
        ev.preventDefault();
        openMenu(tr);
    }, { capture: true });
})();
