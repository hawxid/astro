#!/usr/bin/env python3
"""Dump KismetDB devices for the Astro Kismet -> Atlas importer.

Stdlib only. Run inside the chroot by KismetImporter, which copies this
asset there first (same delivery as the Frida bridge asset).

Usage: python3 astro_kismet_devices.py <logdir>
Prints one line per device: DEVMAC|AVG_LAT|AVG_LON|SSID
SSID is the WiFi AP name when Kismet knows it, else empty (the pin
then falls back to the BSSID title, as before).

Where the name comes from (discovered, never assumed — the column
list is read from the real DB first, so an unknown schema can never
break the import; worst case the name is empty, exactly like before):
  1. The `json` column of the devices table, when present: the full
     device record. Name = kismet.device.base.name, used ONLY when
     kismet.device.base.phyname == "IEEE802.11" (WiFi). A name equal
     to the MAC itself means "SSID unknown" and is treated as empty.
     Bluetooth and other phys keep the MAC title on purpose.
  2. A plain name column (devname, name, ssid), when present and the
     json column is absent: used as-is.
  3. Neither -> empty name.

SSID newlines are flattened (one device = one output line); a pipe
inside an SSID survives because the importer splits with limit 4.

Markers on stdout (the importer parses stdout only, never exit codes,
because Core.customChrootCommand returns output lines):
  NOFILE          no *.kismet file in the log dir yet
  DBERROR:<msg>   the database could not be read

The database is opened read-only with a 5 s busy timeout, so reading
while Kismet is still capturing does not disturb the writer; the
auto-import path runs after Stop anyway, when the DB is quiescent.
"""

import glob
import json
import os
import sqlite3
import sys


NAME_COLUMNS = ("devname", "name", "ssid")


def device_name_from_json(raw, devmac):
    try:
        if isinstance(raw, bytes):
            raw = raw.decode("utf-8", "replace")
        d = json.loads(raw)
    except Exception:
        return ""
    if not isinstance(d, dict):
        return ""
    if d.get("kismet.device.base.phyname") != "IEEE802.11":
        return ""
    name = d.get("kismet.device.base.name") or ""
    name = str(name).replace("\n", " ").replace("\r", " ").strip()
    if not name or name.upper() == devmac.upper():
        return ""
    return name


def clean_name(value):
    if value is None:
        return ""
    return str(value).replace("\n", " ").replace("\r", " ").strip()


def main():
    logdir = sys.argv[1] if len(sys.argv) > 1 else "/tmp/astro_kismet"
    files = sorted(
        glob.glob(os.path.join(logdir, "*.kismet")), key=os.path.getmtime
    )
    if not files:
        print("NOFILE")
        return
    try:
        con = sqlite3.connect(
            "file:%s?mode=ro" % files[-1], uri=True, timeout=5.0
        )
        try:
            con.execute("PRAGMA busy_timeout = 5000")
            cols = [r[1] for r in con.execute("PRAGMA table_info(devices)")]
            use_json = "json" in cols
            name_col = None
            if not use_json:
                for c in NAME_COLUMNS:
                    if c in cols:
                        name_col = c
                        break
            if use_json:
                query = "SELECT devmac, avg_lat, avg_lon, json FROM devices"
            elif name_col:
                query = ("SELECT devmac, avg_lat, avg_lon, %s FROM devices"
                         % name_col)
            else:
                query = "SELECT devmac, avg_lat, avg_lon FROM devices"
            for row in con.execute(query):
                devmac = row[0]
                if devmac is None or str(devmac).strip() == "":
                    continue
                devmac = str(devmac)
                name = ""
                if use_json and len(row) > 3:
                    name = device_name_from_json(row[3], devmac)
                elif name_col and len(row) > 3:
                    name = clean_name(row[3])
                print("%s|%s|%s|%s" % (devmac, row[1], row[2], name))
        finally:
            con.close()
    except sqlite3.Error as e:
        print("DBERROR:%s" % e)


if __name__ == "__main__":
    main()
