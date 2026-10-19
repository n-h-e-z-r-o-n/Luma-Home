"""Build Luma Home with an installed Android SDK, on Windows, macOS or Linux."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import secrets
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parent


def run(args, **kwargs):
    subprocess.run([str(a) for a in args], check=True, **kwargs)


def version(path):
    return tuple(int(x) for x in path.name.replace("android-", "").split(".") if x.isdigit())


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--debug", action="store_true", help="Build for local device testing")
    parser.add_argument("--bundle", action="store_true", help="Also build and validate a signed release AAB")
    args = parser.parse_args()
    if args.bundle and args.debug:
        parser.error("Play release bundles must not be debuggable")
    candidates = [
        os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT"),
        str(Path.home() / "AppData/Local/Android/Sdk"),
        str(Path.home() / "Library/Android/sdk"), str(Path.home() / "Android/Sdk"),
    ]
    sdk = next((Path(p) for p in candidates if p and (Path(p) / "platforms").is_dir()), None)
    if not sdk:
        raise SystemExit("Android SDK not found. Set ANDROID_HOME to an installed SDK.")
    tools = sorted((sdk / "build-tools").iterdir(), key=version)
    tool = next((p for p in reversed(tools) if (p / ("aapt2.exe" if os.name == "nt" else "aapt2")).is_file()), None)
    platforms = sorted((p for p in (sdk / "platforms").iterdir() if (p / "android.jar").is_file()), key=version)
    if tool is None or not platforms:
        raise SystemExit("Install Android build-tools and an SDK platform.")
    jar = platforms[-1] / "android.jar"
    java_candidates = [os.environ.get("JAVA_HOME"), "C:/Program Files/Android/Android Studio/jbr"]
    java = next((Path(p) / "bin" for p in java_candidates if p and (Path(p) / "bin").is_dir()), None)
    suffix = ".exe" if os.name == "nt" else ""
    def java_tool(name):
        found = java / (name + suffix) if java else None
        if found is not None and found.is_file():
            return found
        path = shutil.which(name)
        if not path:
            raise SystemExit(f"{name} not found. Set JAVA_HOME.")
        return Path(path)

    out = ROOT / "out"
    build = out / "build"
    out.mkdir(exist_ok=True)
    # Validate the exact absolute target before clearing generated classes.
    assert build.resolve().is_relative_to(ROOT.resolve()) and build.name == "build"
    if build.exists():
        shutil.rmtree(build)
    classes = build / "classes"
    generated = build / "gen/com/lumahome/launcher"
    dex = build / "dex"
    legal = build / "legal"
    for folder in (classes, generated, dex, legal):
        folder.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(ROOT / "LICENSE", legal / "LICENSE")
    apps_file = Path(os.environ.get("APPS", str(ROOT / "apps.txt")))
    apps = [line.strip() for line in apps_file.read_text().splitlines() if line.strip() and not line.lstrip().startswith("#")]
    if any(not all(c.isalnum() or c in "._" for c in pkg) for pkg in apps):
        raise SystemExit("apps.txt contains an invalid package name")
    (generated / "Apps.java").write_text(
        "package com.lumahome.launcher;\nfinal class Apps { static final String[] LIST = {"
        + ",".join(json.dumps(pkg) for pkg in apps) + "}; }\n", encoding="utf-8")

    aapt = Path(os.environ.get("AAPT2", str(ROOT / "tools" / ("aapt2.exe" if os.name == "nt" else "aapt2"))))
    if not aapt.is_file():
        aapt = tool / ("aapt2.exe" if os.name == "nt" else "aapt2")
    compiled_resources = build / "resources.zip"
    run([aapt, "compile", "--dir", ROOT / "res", "-o", compiled_resources])
    base = build / "base.apk"
    link = [aapt, "link", "-o", base, "-I", jar, "-A", legal,
            "--manifest", ROOT / "AndroidManifest.xml", "--min-sdk-version", "21", "--target-sdk-version", "34", "-R", compiled_resources]
    if args.debug:
        link.append("--debug-mode")
    run(link)
    run([java_tool("javac"), "-nowarn", "-Xlint:-options", "-source", "1.8", "-target", "1.8",
         "-bootclasspath", str(jar) + os.pathsep + str(tool / "core-lambda-stubs.jar"),
         "-d", classes, *sorted((ROOT / "src").rglob("*.java")), generated / "Apps.java"])
    child_env = os.environ.copy()
    if java:
        child_env["JAVA_HOME"] = str(java.parent)
        child_env["PATH"] = str(java) + os.pathsep + child_env.get("PATH", "")
    d8 = tool / ("d8.bat" if os.name == "nt" else "d8")
    run([d8, "--min-api", "21", "--release", "--lib", jar, "--output", dex,
         *sorted(classes.rglob("*.class"))], env=child_env)
    unsigned = build / "unsigned.apk"
    shutil.copyfile(base, unsigned)
    with zipfile.ZipFile(unsigned, "a") as archive:
        for file in dex.glob("*.dex"):
            archive.write(file, file.name)
    aligned = build / "aligned.apk"
    run([tool / ("zipalign.exe" if os.name == "nt" else "zipalign"), "-f", "4", unsigned, aligned])
    keystore = Path(os.environ.get("KEYSTORE", str(ROOT / "key.jks")))
    password_file = ROOT / ".signing-password"
    supplied_password = os.environ.get("KEYSTORE_PASS")
    if not keystore.exists():
        if not supplied_password:
            password_file.write_text(secrets.token_urlsafe(32), encoding="utf-8")
        secret = "env:KEYSTORE_PASS" if supplied_password else "file:" + str(password_file)
        key_args = ["-storepass:env", "KEYSTORE_PASS", "-keypass:env", "KEYSTORE_PASS"] if supplied_password else [
            "-storepass:file", password_file, "-keypass:file", password_file]
        run([java_tool("keytool"), "-genkeypair", "-keystore", keystore, "-storetype", "JKS", *key_args,
             "-alias", "lumahome", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
             "-dname", "CN=Luma Home"], env=child_env, stdout=subprocess.DEVNULL)
    elif not supplied_password and not password_file.exists():
        raise SystemExit("Signing key exists. Supply KEYSTORE_PASS; its password will not be replaced.")
    password = "env:KEYSTORE_PASS" if supplied_password else "file:" + str(password_file)
    signer = tool / ("apksigner.bat" if os.name == "nt" else "apksigner")
    apk = out / ("luma-home-release.apk" if args.bundle else "luma-home.apk")
    run([signer, "sign", "--ks", keystore, "--ks-pass", password, "--out", apk, aligned], env=child_env)
    run([signer, "verify", apk], env=child_env)
    with zipfile.ZipFile(apk) as archive:
        assert archive.read("assets/LICENSE") == (ROOT / "LICENSE").read_bytes()
        assert "classes.dex" in archive.namelist()
    bundle = None
    if args.bundle:
        bundletool = Path(os.environ.get("BUNDLETOOL", str(ROOT / "tools/bundletool-all-1.18.3.jar")))
        if not bundletool.is_file():
            raise SystemExit("Set BUNDLETOOL to Google's bundletool-all JAR; see docs/play-release.md")
        proto = build / "proto.apk"
        proto_link = [str(value) for value in link]
        proto_link[proto_link.index(str(base))] = str(proto)
        run([*proto_link, "--proto-format"])
        module = build / "base.zip"
        with zipfile.ZipFile(proto) as source, zipfile.ZipFile(module, "w", zipfile.ZIP_DEFLATED) as target:
            for name in source.namelist():
                if name == "AndroidManifest.xml":
                    target.writestr("manifest/AndroidManifest.xml", source.read(name))
                elif name == "resources.pb" or name.startswith(("res/", "assets/")):
                    target.writestr(name, source.read(name))
            for file in dex.glob("*.dex"):
                target.write(file, "dex/" + file.name)
        unsigned_bundle = build / "unsigned.aab"
        run([java_tool("java"), "-jar", bundletool, "build-bundle", "--modules=" + str(module), "--output=" + str(unsigned_bundle), "--overwrite"], env=child_env)
        bundle = out / "luma-home.aab"
        jar_password = ["-storepass:env", "KEYSTORE_PASS"] if supplied_password else ["-storepass:file", password_file]
        run([java_tool("jarsigner"), "-keystore", keystore, *jar_password, "-signedjar", bundle, unsigned_bundle, "lumahome"], env=child_env, stdout=subprocess.DEVNULL)
        run([java_tool("jarsigner"), "-verify", bundle], env=child_env, stdout=subprocess.DEVNULL)
        run([java_tool("java"), "-jar", bundletool, "validate", "--bundle=" + str(bundle)], env=child_env, stdout=subprocess.DEVNULL)
        with zipfile.ZipFile(bundle) as archive:
            assert archive.read("base/assets/LICENSE") == (ROOT / "LICENSE").read_bytes()
            assert not any(name.endswith(".so") for name in archive.namelist())
        print(f"Built, signed and bundletool-validated: {bundle}")
    (out / ("release-build-info.json" if args.bundle else "build-info.json")).write_text(json.dumps({
        "name": "Luma Home", "debug": args.debug,
        "bundle_sha256": hashlib.sha256(bundle.read_bytes()).hexdigest() if bundle else None,
        "sdk_platform": jar.parent.name, "build_tools": tool.name,
        "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
    }, indent=2) + "\n")
    print(f"Built and signature-verified: {apk}")


if __name__ == "__main__":
    main()
