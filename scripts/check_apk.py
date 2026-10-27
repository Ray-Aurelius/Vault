#!/usr/bin/env python3
"""Security gate for every Bulwark APK. Fails (exit 1) if the APK:

  * requests any permission outside the allowlist (above all, INTERNET or any network permission);
  * exports any component other than the allowed entry points;
  * is debuggable (release builds only).

Usage: check_apk.py <path-to-apk> [--release]
Needs ANDROID_HOME (uses cmdline-tools' apkanalyzer).
"""
import os
import subprocess
import sys
import xml.etree.ElementTree as ET

A = "{http://schemas.android.com/apk/res/android}"

# Permissions the spec allows. Each is requested at runtime only when its feature is first used.
ALLOWED_PERMISSIONS = {
    "android.permission.USE_BIOMETRIC",
    "android.permission.NFC",
    "android.permission.CAMERA",
    "android.permission.RECORD_AUDIO",
}
# AndroidX core defines this signature-level permission so the app's own receivers stay private.
ALLOWED_SUFFIXES = (".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",)

# The only components other apps may reach.
ALLOWED_EXPORTED = {
    "app.bulwark.android.MainActivity",
}


def manifest(apk: str) -> ET.Element:
    tool = os.path.join(os.environ["ANDROID_HOME"], "cmdline-tools", "latest", "bin", "apkanalyzer")
    out = subprocess.run([tool, "manifest", "print", apk], check=True, capture_output=True, text=True).stdout
    return ET.fromstring(out[out.index("<"):])


def main() -> int:
    apk = sys.argv[1]
    release = "--release" in sys.argv
    root = manifest(apk)
    package = root.get("package")
    problems = []

    for el in root.iter():
        if el.tag in ("uses-permission", "uses-permission-sdk-23"):
            name = el.get(A + "name")
            if name not in ALLOWED_PERMISSIONS and not name.endswith(ALLOWED_SUFFIXES):
                problems.append(f"Disallowed permission: {name}")

    app = root.find("application")
    for comp in app:
        if comp.tag not in ("activity", "activity-alias", "service", "receiver", "provider"):
            continue
        name = comp.get(A + "name")
        if name.startswith("."):
            name = package.split(".debug")[0] + name
        exported = comp.get(A + "exported")
        if exported is None:
            # Pre-Android-12 default: exported if it has an intent filter. Treat as exported.
            exported = "true" if comp.find("intent-filter") is not None else "false"
        if exported == "true" and name not in ALLOWED_EXPORTED:
            problems.append(f"Exported component not on the allowlist: {comp.tag} {name}")

    if app.get(A + "allowBackup") != "false":
        problems.append("allowBackup must be false")
    if release and app.get(A + "debuggable") == "true":
        problems.append("Release build is debuggable")

    if problems:
        print("APK security check FAILED:")
        for p in problems:
            print("  - " + p)
        return 1
    print(f"APK security check passed: {apk}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
