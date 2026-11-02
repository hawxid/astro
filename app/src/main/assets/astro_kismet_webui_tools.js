/* ASTRO_KISMET_WEBUI_TOOLS_VERSION = 2 */
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
 * Interaksi (sesuai spek Hawx): LONG-PRESS pada baris perangkat
 * (touch ~600 ms; klik kanan sebagai fallback non-touch) membuka
 * menu aksi; menu memanggil bridge Astro di 127.0.0.1:18082
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

    function openMenu(tr) {
        ensureMenu();
        if (typeof window.jQuery === 'undefined' ||
            typeof window.jQuery.fn.DataTable === 'undefined') {
            document.getElementById('astro-tools-title').textContent = 'Astro Tools';
            document.getElementById('astro-tools-btns').innerHTML = '';
            showResult('DataTables tidak tersedia di halaman ini.');
            menuEl.style.display = 'block';
            return;
        }
        var data;
        try {
            data = window.jQuery(tr).closest('table').DataTable().row(tr).data();
        } catch (e) {
            data = null;
        }
        if (!data) {
            document.getElementById('astro-tools-title').textContent = 'Astro Tools';
            document.getElementById('astro-tools-btns').innerHTML = '';
            showResult('Data baris tidak terbaca.');
            menuEl.style.display = 'block';
            return;
        }
        var mac = data['kismet.device.base.macaddr'] ||
                  data['kismet.device.base.mac'] || '';
        var channel = data['kismet.device.base.channel'];
        var phy = data['kismet.device.base.phyname'] || '';
        if (!mac) {
            document.getElementById('astro-tools-title').textContent = 'Astro Tools';
            document.getElementById('astro-tools-btns').innerHTML = '';
            showResult('MAC tidak terbaca dari baris ini.');
            menuEl.style.display = 'block';
            return;
        }
        var wifi = (phy === 'IEEE802.11');
        current = { mac: mac, channel: channel, phy: phy, wifi: wifi };
        document.getElementById('astro-tools-title').textContent =
            'Astro Tools — ' + mac + (wifi ? ' (WiFi, ch ' + channel + ')' : ' (BT)');
        var btns = document.getElementById('astro-tools-btns');
        btns.innerHTML = '';
        lastJobId = null;
        if (wifi) {
            if (!channel) {
                showResult('Channel tidak diketahui untuk perangkat ini — aksi WiFi butuh channel.');
            } else {
                addBtn('Deauth mdk4 (30 dtk)', function () {
                    return call('/wifi/deauth/mdk4', {
                        bssid: mac, channel: channel, duration: 30
                    });
                });
                addBtn('Deauth aireplay (30 dtk)', function () {
                    return call('/wifi/deauth/aireplay', {
                        bssid: mac, channel: channel, duration: 30
                    });
                });
                addBtn('Tangkap airodump (60 dtk)', function () {
                    return call('/wifi/capture/airodump', {
                        bssid: mac, channel: channel, duration: 60
                    });
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
            return call('/jobs/stop', { id: lastJobId });
        });
        if (wifi && channel) showResult('Pilih aksi.');
        menuEl.style.display = 'block';
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
        if (!tr) return;
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
        if (pressRow && pressStart &&
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
        if (!tr) return;
        ev.preventDefault();
        openMenu(tr);
    }, { capture: true });
})();
