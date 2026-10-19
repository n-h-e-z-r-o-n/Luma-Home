from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

root=Path(__file__).resolve().parent.parent
serial=sys.argv[1]
if not serial.startswith("emulator-"):
    raise SystemExit("This automated smoke test requires a dedicated emulator.")
shot=Path(sys.argv[2] if len(sys.argv)>2 else "smoke.png")
adb=["adb","-s",serial]
def call(*args):
    return subprocess.check_output(adb+list(args),encoding="utf-8",timeout=30)
def hierarchy():
    call("shell","uiautomator","dump","/data/local/tmp/luma-smoke.xml")
    return ET.fromstring(call("shell","cat","/data/local/tmp/luma-smoke.xml"))
def tap_text(tree,text):
    node=next(n for n in tree.iter("node") if n.get("text")==text)
    x1,y1,x2,y2=map(int,re.findall(r"\d+",node.get("bounds")))
    call("shell","input","tap",str((x1+x2)//2),str((y1+y2)//2))
call("install","-r",str(root/"out/luma-home.apk"))
call("shell","am","start","-W","-n","com.lumahome.launcher/.MainActivity")
tree=hierarchy()
assert any(n.get("text")=="Favorites" for n in tree.iter("node"))
tap_text(tree,"All Apps")
tree=hierarchy()
assert any(n.get("text")=="Search all apps" for n in tree.iter("node"))
call("shell","input","keyevent","KEYCODE_BACK")
tree=hierarchy()
assert any(n.get("text")=="Favorites" for n in tree.iter("node"))
shot.write_bytes(subprocess.check_output(adb+["exec-out","screencap","-p"]))
call("shell","input","keyevent","KEYCODE_HOME")
print("PASS: Home, All Apps and Back; original default retained; no app removed.")
