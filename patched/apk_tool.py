#!/usr/bin/env python3
"""Helpers for patched/build-release.sh.

DroidDeck's full build needs Docker and prepares its native runtime (proot, FEX preloads, audio,
packs) the way upstream CI does. DroidDeck Patched changes only Kotlin/Java code, resources and
session helper scripts, so a release is the official upstream APK with exactly those parts rebuilt
from this repository; every native library and runtime asset stays byte-for-byte upstream's.

  apk_tool.py scope UPSTREAM_TAG
      fails when the branch changes anything the graft below cannot carry into the APK
  apk_tool.py buildconfig OFFICIAL.apk DEXDUMP UPSTREAM_COMMIT SOURCE_COMMIT COMMIT_TIME LABEL
      prints the Gradle block that pins the official build's add-on packs and keys
  apk_tool.py graft OFFICIAL.apk DEX_DIR RES.ap_ UPSTREAM_TAG OUT.apk
      writes the unsigned release APK
"""
import fnmatch
import os
import re
import subprocess
import sys
import tempfile
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OVERLAY = "tools/linuxfs/overlay/"
# What app/build.gradle's stageSessionScripts copies from the overlay into assets/linuxfs.
STAGED = ("usr/local/bin/droiddeck-*", "usr/local/bin/steam-compatibility", "usr/bin/*")
ALLOWED = (
    "app/src/main/java/*", "app/src/main/res/*", "app/src/main/AndroidManifest.xml", "app/src/test/*",
    "app/build.gradle", "keystore/release-signer.sha256", "README.md", "patched/*", ".gitignore",
)
FROM_OFFICIAL = re.compile(r"^(CI_BUILD|SGDB_API_KEY|PACK_[A-Z]+_(SHA256|SIZE))$")
SIG = re.compile(r"^META-INF/([^/]+\.(SF|RSA|DSA|EC)|MANIFEST\.MF)$")
DEX = re.compile(r"^classes\d*\.dex$")


def git(*args):
    return subprocess.check_output(["git", "-C", ROOT, *args], text=True)


def changes(tag):
    """(status, path) of every file that differs between the upstream tag and the working tree."""
    out = git("diff", "--no-renames", "--name-status", tag, "--")
    rows = [line.split("\t", 1) for line in out.splitlines() if line]
    untracked = git("ls-files", "--others", "--exclude-standard").splitlines()
    return [(s, p) for s, p in rows] + [("A", p) for p in untracked]


def staged_asset(path):
    rel = path[len(OVERLAY):]
    return "assets/linuxfs/" + rel if any(fnmatch.fnmatchcase(rel, g) for g in STAGED) else None


def scope(tag):
    bad = []
    for status, path in changes(tag):
        if path.startswith(OVERLAY):
            if status != "M" or staged_asset(path) is None:
                bad.append("%s %s (only edits of helpers the APK ships can be carried)" % (status, path))
        elif not any(fnmatch.fnmatchcase(path, g) for g in ALLOWED):
            bad.append("%s %s" % (status, path))
    if bad:
        sys.exit("These changes would need DroidDeck's full build (tools/build_local.sh), not this one:\n  "
                 + "\n  ".join(bad))


def buildconfig(apk, dexdump, upstream_commit, commit, commit_time, label):
    fields = {}
    with zipfile.ZipFile(apk) as z, tempfile.TemporaryDirectory() as tmp:
        for name in z.namelist():
            if not DEX.match(name):
                continue
            path = z.extract(name, tmp)
            dump = subprocess.run([dexdump, "-l", "plain", path], capture_output=True, encoding="utf-8", errors="replace").stdout
            block = re.search(r"Class descriptor\s*: 'Lcom/droiddeck/launcher/BuildConfig;'(.*?)(?=Class #|\Z)", dump, re.S)
            if not block:
                continue
            for m in re.finditer(r"name\s*: '([A-Z0-9_]+)'\s*\n\s*type\s*: '([^']+)'\s*\n\s*access[^\n]*\n\s*value\s*: ([^\n]*)", block.group(1)):
                fields[m.group(1)] = (m.group(2), m.group(3).strip())
    built_from = fields.get("SOURCE_COMMIT", ("", '""'))[1].strip('"')
    if built_from != upstream_commit:
        sys.exit("%s was built from %s, not the upstream tag's %s" % (apk, built_from or "?", upstream_commit))
    picked = {k: v for k, v in fields.items() if FROM_OFFICIAL.match(k)}
    for need in ("CI_BUILD", "SGDB_API_KEY", "PACK_PROOT_SHA256", "PACK_XBOX_SHA256"):
        if need not in picked:
            sys.exit("BuildConfig.%s not found in %s" % (need, apk))
    gradle_types = {"Z": "boolean", "J": "long", "I": "int", "Ljava/lang/String;": "String"}

    def literal(kind, value):
        if kind == "Ljava/lang/String;":
            text = value[1:-1] if value.startswith('"') and value.endswith('"') else value
            return '"%s"' % text.replace("\\", "\\\\").replace('"', '\\"')
        return value + ("L" if kind == "J" else "")

    lines = ["", "// patched/build-release.sh: the official upstream build's packs and keys, this source's identity.",
             "android.defaultConfig {"]
    own = {
        "BUILD_LABEL": ("Ljava/lang/String;", label),
        "SOURCE_COMMIT": ("Ljava/lang/String;", commit),
        "COMMIT_TIME": ("J", commit_time + "000"),
        "PR_NUMBER": ("I", "0"),
    }
    for name, (kind, value) in sorted(picked.items()) + sorted(own.items()):
        def esc(v):
            return v.replace("\\", "\\\\").replace("'", "\\'")
        lines.append("    buildConfigField '%s', '%s', '%s'" % (gradle_types[kind], name, esc(literal(kind, value))))
    lines.append("}")
    print("\n".join(lines))


def graft(official, dex_dir, res_ap, tag, out):
    helpers = {staged_asset(p): os.path.join(ROOT, p) for s, p in changes(tag) if p.startswith(OVERLAY)}
    src = zipfile.ZipFile(official)
    res = zipfile.ZipFile(res_ap)
    names = set(src.namelist())
    missing = [a for a in helpers if a not in names]
    if missing:
        sys.exit("helpers not in the official APK: %s" % missing)
    dexes = sorted((n for n in os.listdir(dex_dir) if DEX.match(n)), key=lambda n: (len(n), n))
    if not dexes:
        sys.exit("no classes*.dex in " + dex_dir)
    methods = {i.filename: i.compress_type for i in src.infolist()}

    def info(name, like=None, fallback=zipfile.ZIP_DEFLATED):
        zi = zipfile.ZipInfo(name, date_time=(1981, 1, 1, 1, 1, 0))
        zi.compress_type = methods.get(like or name, fallback)
        return zi

    written = set()
    with zipfile.ZipFile(out, "w") as dst:
        dst.writestr(info("AndroidManifest.xml"), res.read("AndroidManifest.xml"))
        written.add("AndroidManifest.xml")
        for item in src.infolist():
            name = item.filename
            if name in written or SIG.match(name):
                continue
            if DEX.match(name):
                if name == "classes.dex":
                    for d in dexes:
                        with open(os.path.join(dex_dir, d), "rb") as f:
                            dst.writestr(info(d, "classes.dex"), f.read())
                        written.add(d)
                continue
            if name == "resources.arsc" or name.startswith("res/"):
                if name == "resources.arsc":
                    for r in res.infolist():
                        if r.filename in written:
                            continue
                        dst.writestr(info(r.filename, fallback=r.compress_type), res.read(r.filename))
                        written.add(r.filename)
                continue
            data = src.read(name)
            if name in helpers:
                with open(helpers[name], "rb") as f:
                    data = f.read()
            dst.writestr(info(name), data)
            written.add(name)
    left = [n for n in res.namelist() if n not in written]
    if left:
        sys.exit("resources not written: %s" % left[:10])
    print("grafted %d dex, %d resource entries, %d helper(s)" % (len(dexes), len(res.namelist()), len(helpers)))


if __name__ == "__main__":
    cmd, args = sys.argv[1], sys.argv[2:]
    {"scope": scope, "buildconfig": buildconfig, "graft": graft}[cmd](*args)
