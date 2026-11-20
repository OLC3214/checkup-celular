"""Acha um texto na tela do emulador (uiautomator) e imprime o centro "x y". Sai com 1 se não achar."""
import re, subprocess, sys, xml.etree.ElementTree as ET

def dump():
    for _ in range(5):
        subprocess.run(["adb", "shell", "uiautomator", "dump", "/sdcard/ui.xml"], capture_output=True)
        out = subprocess.run(["adb", "exec-out", "cat", "/sdcard/ui.xml"], capture_output=True).stdout
        try:
            return ET.fromstring(out)
        except ET.ParseError:
            pass
    return None

def find(text):
    root = dump()
    if root is None:
        return None
    for n in root.iter("node"):
        if text in (n.get("text") or ""):
            x1, y1, x2, y2 = map(int, re.findall(r"\d+", n.get("bounds")))
            return (x1 + x2) // 2, (y1 + y2) // 2
    return None

if __name__ == "__main__":
    if sys.argv[1] == "texts":
        root = dump()
        for n in (root.iter("node") if root is not None else []):
            t = n.get("text")
            if t:
                print(t.replace("\n", " | "))
        sys.exit(0)
    p = find(sys.argv[1])
    if p is None:
        sys.exit(1)
    print(p[0], p[1])
