#!/usr/bin/env python3
"""Dump KismetDB devices for the Astro Kismet -> Atlas importer.

Stdlib only. Run inside the chroot by KismetImporter, which copies this
asset there first (same delivery as the Frida bridge asset).

Usage: python3 astro_kismet_devices.py <logdir>
Prints one line per device: DEVMAC|AVG_LAT|AVG_LON
(only the three columns the on-device PoC proved exist in KismetDB v11;
SSID is deliberately NOT read — no unproven column is queried, and the
importer falls back to the BSSID as the pin title when SSID is empty).

Markers on stdout (the importer parses stdout only, never exit codes,
because Core.customChrootCommand returns output lines):
  NOFILE          no *.kismet file in the log dir yet
  DBERROR:<msg>   the database could not be read

The database is opened read-only with a 5 s busy timeout, so reading
while Kismet is still capturing does not disturb the writer; the
auto-import path runs after Stop anyway, when the DB is quiescent.
"""

import glob
import os
import sqlite3
import sys


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
            for devmac, avg_lat, avg_lon in con.execute(
                "SELECT devmac, avg_lat, avg_lon FROM devices"
            ):
                if devmac is None or str(devmac).strip() == "":
                    continue
                print("%s|%s|%s" % (devmac, avg_lat, avg_lon))
        finally:
            con.close()
    except sqlite3.Error as e:
        print("DBERROR:%s" % e)


if __name__ == "__main__":
    main()
