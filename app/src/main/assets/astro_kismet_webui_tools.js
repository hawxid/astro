/* ASTRO_KISMET_WEBUI_TOOLS_VERSION = 3 */
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
        return { mac: mac, channel: ch, wifi: wifi, known: known };
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

    function showDeviceMenu(mac, channel, wifi) {
        current = { mac: mac, channel: channel, wifi: wifi };
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
                showDeviceMenu(mac, channel, (phy === 'IEEE802.11'));
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
            showDeviceMenu(d.mac, d.channel, d.wifi);
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
