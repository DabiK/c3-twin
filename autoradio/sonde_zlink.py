#!/usr/bin/env python3
"""Lit quelques secondes de chaque canal Fox de ZLink, à travers `adb forward`.

Appelé par sonde_qf001.sh. Comme C3 Twin, il n'envoie que « REQUEST FRAME » sur les canaux
vidéo et audio, et rien du tout sur les autres : jamais de tactile, jamais de touche.

Usage : sonde_zlink.py <dossier de sortie> <port local de base>
Le port Fox 518xx est joint sur <base> + (518xx - 51800).
"""
import json
import os
import re
import socket
import sys
import time

FOX = {
    51808: ("contrôle", False),
    51818: ("vidéo", True),
    51828: ("contrôle 2", False),
    51858: ("audio principal", True),
    51868: ("audio alternatif", True),
    51878: ("audio nuit", True),
    51888: ("audio système", True),
}
# lignes d'en-tête ASCII de 128 octets : VIDEO#…, AUDIO#…, ou lignes d'état
HEADER = re.compile(rb"[A-Z][A-Z0-9 _]{2,}#[ -~]{3,124}")


def packet(text):
    raw = text.encode("ascii")
    return raw + b"\0" * (128 - len(raw))


def probe(port, name, ask, local, out):
    result = {"port": port, "canal": name}
    try:
        s = socket.create_connection(("127.0.0.1", local), timeout=2)
    except OSError as e:
        result["erreur"] = "connexion refusée (%s)" % e
        return result
    s.settimeout(0.5)
    data = bytearray()
    try:
        if ask:
            s.sendall(packet("REQUEST FRAME"))
        until = time.time() + (5 if name == "vidéo" else 3)
        while time.time() < until and len(data) < 4_000_000:
            try:
                chunk = s.recv(65536)
            except socket.timeout:
                continue
            if not chunk:
                result["fermé_par_zlink"] = True
                break
            data += chunk
    except OSError as e:
        result["erreur"] = str(e)
    finally:
        s.close()
    result["octets"] = len(data)
    headers = []
    for m in HEADER.finditer(bytes(data[:400_000])):
        line = m.group(0).decode("ascii", "replace")
        if line not in headers:
            headers.append(line)
        if len(headers) >= 8:
            break
    result["en-têtes"] = headers
    if data:
        with open(os.path.join(out, "fox_%d.bin" % port), "wb") as f:
            f.write(data[:2_000_000])
    return result


def main():
    out, base = sys.argv[1], int(sys.argv[2])
    report = []
    for port, (name, ask) in FOX.items():
        r = probe(port, name, ask, base + port - 51800, out)
        report.append(r)
        status = r.get("erreur") or "%d octets, %s" % (r["octets"], (r["en-têtes"] or ["aucun en-tête"])[0][:70])
        print("   %d %-17s %s" % (port, name, status))
    with open(os.path.join(out, "fox_sonde.json"), "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)


if __name__ == "__main__":
    main()
