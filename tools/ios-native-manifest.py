"""Generate the iOS build's source lists from the checked-in Android vendor manifests."""

import re
import sys
from pathlib import Path

from pdfium_vendor import EXTRA_LICENSES, LICENSE_RE


def write_changed(path, text):
    if not path.exists() or path.read_text() != text:
        path.write_text(text)


cpp, output = map(Path, sys.argv[1:])
manifest = (cpp / "pdfium.cmake").read_text()
revision = re.search(r"set\(PDFIUM_REVISION (\w+)\)", manifest)[1]
if revision != "8ca5b735df4263f43c830479b78213854a392c22":
    sys.exit("Refresh the matching Apple sources and revalidate iOS before updating PDFium.")

arm = manifest.split('if(ANDROID_ABI STREQUAL "arm64-v8a")\n', 1)[1].split("elseif(", 1)[0]
common = manifest.split("\nendif()\n\npdfium_library(", 1)[1]
calls = re.findall(r"(?ms)^ *pdfium_library\(\w+ .*?^ *\)", arm + "\npdfium_library(" + common)
if not calls or not any("pdfium_library(fpdfsdk " in call for call in calls):
    sys.exit("Unrecognized PDFium manifest")

android = (cpp / "CMakeLists.txt").read_text()
grammar_sources = re.search(r"set\(GRAMMAR_SOURCES\b(.*?)\)", android, re.S)[1].split()
grammars = {source.split("/")[1] for source in grammar_sources if source.endswith("/parser.c")}
table = (cpp / "ts_jni.c").read_text().split("static const LangEntry LANGS[] = {", 1)[1].split("};", 1)[0]
languages = re.findall(r'\{"(\w+)", (tree_sitter_\w+)\}', table)
if grammars != {name for name, symbol in languages}:
    sys.exit("Grammar sources and language table disagree")
write_changed(output / "manifest.cmake",
              "\n".join(calls) + "\nset(GRAMMAR_SOURCES\n" + "\n".join(grammar_sources) + "\n)\n")
write_changed(output / "languages.inc",
              "".join(f'XNOTES_LANGUAGE("{name}", {symbol})\n' for name, symbol in languages))

notices = [cpp / "THIRD_PARTY", cpp / "tree-sitter/LICENSE"]
notices.extend(sorted((cpp / "grammars").glob("*/LICENSE")))
pdfium = cpp / "pdfium"
notices.extend(sorted(file for file in pdfium.rglob("*") if file.is_file() and
                      (LICENSE_RE.fullmatch(file.name) or file.relative_to(pdfium).as_posix() in EXTRA_LICENSES)))
bundle = b"\n\n".join(str(file.relative_to(cpp)).encode() + b"\n\n" + file.read_bytes() for file in notices)
notice_file = output / "THIRD_PARTY.txt"
if not notice_file.exists() or notice_file.read_bytes() != bundle:
    notice_file.write_bytes(bundle)
