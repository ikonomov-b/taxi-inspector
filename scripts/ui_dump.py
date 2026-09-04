#!/usr/bin/env python3
"""UI Automator helper for scripts/simulate-drive.sh.

Drives the real on-screen UI via `adb shell input` and `uiautomator dump`,
matching elements by their visible text -- it has no knowledge of the app's
Kotlin internals. Every lookup re-dumps the tree immediately before acting,
because focusing a text field pops the soft keyboard, which makes Compose
auto-scroll the screen and turns coordinates from an earlier dump stale.
"""
import argparse
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

DUMP_REMOTE = "/sdcard/window_dump.xml"


def adb(serial, *args):
    return subprocess.run(
        ["adb", "-s", serial, *args], check=True, capture_output=True, text=True
    )


def dump(serial, local_path):
    adb(serial, "shell", "uiautomator", "dump", DUMP_REMOTE)
    subprocess.run(
        ["adb", "-s", serial, "pull", DUMP_REMOTE, local_path],
        check=True, capture_output=True,
    )
    return ET.parse(local_path).getroot()


def center(bounds):
    x1, y1, x2, y2 = map(int, re.findall(r"-?\d+", bounds))
    return (x1 + x2) // 2, (y1 + y2) // 2


def find_all(root, text=None, cls=None):
    matches = []
    for node in root.iter("node"):
        if text is not None and node.get("text", "") != text:
            continue
        if cls is not None and node.get("class", "") != cls:
            continue
        matches.append(node)
    return matches


def find_with_scroll(serial, local_path, label, max_attempts=4):
    for _ in range(max_attempts):
        root = dump(serial, local_path)
        matches = find_all(root, text=label)
        if matches:
            return matches[0]
        adb(serial, "shell", "input", "swipe", "540", "1500", "540", "800", "200")
        time.sleep(0.4)
    return None


def tap_text(serial, local_path, label):
    node = find_with_scroll(serial, local_path, label)
    if node is None:
        sys.exit(f"button/text not found: {label!r}")
    cx, cy = center(node.get("bounds"))
    adb(serial, "shell", "input", "tap", str(cx), str(cy))


def wait_for_focused_edit_text(serial, local_path, attempts=15, interval=0.2):
    """Polls until some EditText reports focused="true" instead of guessing
    a fixed settle time -- the soft keyboard's scroll-into-view animation
    has no fixed duration, especially under host load."""
    for _ in range(attempts):
        root = dump(serial, local_path)
        for node in find_all(root, cls="android.widget.EditText"):
            if node.get("focused") == "true":
                return node
        time.sleep(interval)
    return None


def fill_field(serial, local_path, label, value):
    node = find_with_scroll(serial, local_path, label)
    if node is None:
        sys.exit(f"field label not found: {label!r}")
    cx, cy = center(node.get("bounds"))
    adb(serial, "shell", "input", "tap", str(cx), str(cy))

    if wait_for_focused_edit_text(serial, local_path) is None:
        sys.exit(f"field {label!r} never gained focus after tap")

    # Clear any existing content regardless of prior text or cursor position.
    adb(serial, "shell", "input", "keyevent", "123")  # KEYCODE_MOVE_END
    for _ in range(20):
        adb(serial, "shell", "input", "keyevent", "67")  # KEYCODE_DEL
    adb(serial, "shell", "input", "text", value)
    time.sleep(0.5)

    root = dump(serial, local_path)
    values = [n.get("text") for n in find_all(root, cls="android.widget.EditText")]
    if value not in values:
        sys.exit(f"field {label!r} did not end up as {value!r}, found: {values}")


def dismiss_keyboard(serial, local_path, attempts=15, interval=0.2):
    """Closes the soft keyboard so elements it was covering (which
    uiautomator reports as zero-bounds) become reachable again."""
    adb(serial, "shell", "input", "keyevent", "4")  # KEYCODE_BACK
    for _ in range(attempts):
        root = dump(serial, local_path)
        if not any(n.get("focused") == "true" for n in find_all(root, cls="android.widget.EditText")):
            return
        time.sleep(interval)
    sys.exit("keyboard did not dismiss (a field is still focused)")


def list_nodes(serial, local_path):
    root = dump(serial, local_path)
    for node in root.iter("node"):
        text = node.get("text", "")
        cls = node.get("class", "")
        if text or "EditText" in cls or "Button" in cls:
            print(f"{cls} | {text!r} | {node.get('bounds')}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--serial", required=True)
    parser.add_argument("--dump-path", default="/tmp/taxi-inspector-ui-dump.xml")
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("list")
    sub.add_parser("dismiss-keyboard")

    p_fill = sub.add_parser("fill")
    p_fill.add_argument("label")
    p_fill.add_argument("value")

    p_tap = sub.add_parser("tap-text")
    p_tap.add_argument("label")

    args = parser.parse_args()

    if args.command == "list":
        list_nodes(args.serial, args.dump_path)
    elif args.command == "dismiss-keyboard":
        dismiss_keyboard(args.serial, args.dump_path)
        print("keyboard dismissed")
    elif args.command == "fill":
        fill_field(args.serial, args.dump_path, args.label, args.value)
        print(f"filled {args.label!r} = {args.value!r}")
    elif args.command == "tap-text":
        tap_text(args.serial, args.dump_path, args.label)
        print(f"tapped {args.label!r}")


if __name__ == "__main__":
    main()
