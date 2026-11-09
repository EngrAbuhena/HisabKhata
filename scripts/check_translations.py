#!/usr/bin/env python3
"""Check Bengali resource coverage, format arguments, arrays and Latin-digit policy."""
from collections import Counter
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1] / "app/src/main/res"
FORMAT = re.compile(r"%(?:\d+\$)?[a-zA-Z]")


def load(folder):
    root = ET.parse(ROOT / folder / "strings.xml").getroot()
    result = {}
    for item in root:
        key = (item.tag, item.get("name"))
        if key in result:
            raise ValueError(f"duplicate {folder} resource {key}")
        result[key] = item
    return result


def formats(text):
    return Counter(FORMAT.findall(text or ""))


def main():
    english, bengali = load("values"), load("values-bn")
    errors = []
    if english.keys() != bengali.keys():
        errors.append(f"missing Bengali: {sorted(english.keys() - bengali.keys())}; extra: {sorted(bengali.keys() - english.keys())}")
    for key in english.keys() & bengali.keys():
        en, bn = english[key], bengali[key]
        if key[0] == "string" and formats(en.text) != formats(bn.text):
            errors.append(f"format arguments differ: {key}")
        if key[0] == "string-array" and len(en) != len(bn):
            errors.append(f"array length differs: {key}")
        if key[0] == "plurals":
            a = {e.get("quantity"): formats(e.text) for e in en}
            b = {e.get("quantity"): formats(e.text) for e in bn}
            if a != b:
                errors.append(f"plural arguments differ: {key}")
    raw = (ROOT / "values-bn/strings.xml").read_text(encoding="utf-8")
    if any("০" <= c <= "৯" for c in raw):
        errors.append("Bengali resources must use 0–9 for app-generated numbers")
    # A plain Bengali Configuration is necessary for correct lookup on Android 7,
    # but its default %d formatting uses Bengali digits. Numeric templates must
    # go through the Latin formatter instead of the platform's default formatter.
    numeric = set()
    for (kind, name), elem in english.items():
        texts = [elem.text or ""] + [child.text or "" for child in elem]
        if kind in {"string", "plurals"} and any(re.search(r"%(?:\d+\$)?d", text) for text in texts):
            numeric.add((kind, name))
    direct = re.compile(r"\b(?:stringResource|pluralStringResource|getString|getQuantityString)\s*\(\s*R\.(string|plurals)\.(\w+)\s*,")
    for source in (ROOT.parent / "java").rglob("*.kt"):
        for match in direct.finditer(source.read_text(encoding="utf-8")):
            if (match.group(1), match.group(2)) in numeric:
                errors.append(f"numeric template bypasses Latin formatting: {source.name}: {match.group(2)}")
    if errors:
        print("\n".join(errors), file=sys.stderr)
        return 1
    print(f"Bengali translation: {len(bengali)} resources; placeholders and Latin digits verified")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
