#!/usr/bin/env python3
"""String-catalogue parity gate: for every module, the translated `values-*` directories must carry
exactly the string and plurals names of the default `values` directory (all resource XML files of a
directory are merged), with the same format placeholders, and every plurals must have an `other` item.

    python3 tools/check-strings.py            # all modules with a res/values/strings.xml
    python3 tools/check-strings.py --locales  # also print the locale set per module

Exit 1 on any mismatch. Identical (untranslated) values are reported as warnings, not failures,
because a few strings (app name, file-name pattern, weekday abbreviations) are legitimately shared.
"""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PLACEHOLDER = re.compile(r"%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[sdf]|%%")
# values-ja, values-ko, values-zh-rTW, values-b+zh+Hant …; never values-night / values-v31 / values-sw600dp.
LOCALE_DIR = re.compile(r"values-(?!(?:tv|car|desk|watch|vrheadset|night|land|port)$)(b\+[A-Za-z0-9+]+|[a-z]{2,3}(-r[A-Z]{2})?)")
SHARED_OK = {"app_name", "backup_file_name", "analytics_share", "analytics_range_line", "analytics_emoji_title"}


def catalogue(directory: Path):
    """Merges every resource XML file of one values directory."""
    strings, plurals = {}, {}
    for path in sorted(directory.glob("*.xml")):
        root = ET.parse(path).getroot()
        if root.tag != "resources":
            continue
        for el in root:
            if el.get("translatable") == "false":
                continue
            if el.tag == "string":
                strings[el.get("name")] = el.text or ""
            elif el.tag == "plurals":
                plurals[el.get("name")] = {i.get("quantity"): (i.text or "") for i in el.findall("item")}
    return strings, plurals


def placeholders(text: str):
    return sorted(m.group(0) for m in PLACEHOLDER.finditer(text))


def main() -> int:
    show = "--locales" in sys.argv
    failures, warnings = [], []
    base_dirs = sorted(set(ROOT.glob("*/src/main/res/values")) | set(ROOT.glob("*/*/src/main/res/values")))
    for base_dir in base_dirs:
        module = base_dir.parents[3]
        base_s, base_p = catalogue(base_dir)
        if not base_s and not base_p:
            continue
        # Only locale-qualified directories are catalogues; values-night / values-v31 restyle, they do not translate.
        locales = sorted(d for d in base_dir.parent.glob("values-*") if LOCALE_DIR.fullmatch(d.name) and any(catalogue(d)))
        if show:
            print(f"{module.relative_to(ROOT)}: {[d.name for d in locales]}")
        for loc in locales:
            rel = loc.relative_to(ROOT)
            s, p = catalogue(loc)
            for missing in sorted(set(base_s) - set(s)):
                failures.append(f"{rel}: missing string {missing}")
            for extra in sorted(set(s) - set(base_s)):
                failures.append(f"{rel}: unknown string {extra}")
            for name in sorted(set(base_s) & set(s)):
                if placeholders(base_s[name]) != placeholders(s[name]):
                    failures.append(f"{rel}: placeholders differ for {name}: {placeholders(base_s[name])} vs {placeholders(s[name])}")
                if s[name] == base_s[name] and name not in SHARED_OK and re.search(r"[A-Za-z]{3,}", base_s[name]):
                    warnings.append(f"{rel}: {name} is identical to the default catalogue")
            for missing in sorted(set(base_p) - set(p)):
                failures.append(f"{rel}: missing plurals {missing}")
            for extra in sorted(set(p) - set(base_p)):
                failures.append(f"{rel}: unknown plurals {extra}")
            for name, items in p.items():
                if "other" not in items:
                    failures.append(f"{rel}: plurals {name} has no `other` item")
                for q, text in items.items():
                    if placeholders(text) != placeholders(base_p.get(name, {}).get("other", "")):
                        failures.append(f"{rel}: placeholders differ for plurals {name}/{q}")
    for w in warnings:
        print(f"warning: {w}")
    for e in failures:
        print(f"error: {e}")
    print(f"{'FAIL' if failures else 'OK'}: {len(failures)} error(s), {len(warnings)} warning(s)")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
