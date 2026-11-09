#!/usr/bin/env python3
"""Build isolated native-storage benchmark sources under this repository's ignored tmp/."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import shutil
import subprocess
import tarfile

HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
BENCH = REPO / "tmp/native-storage-benchmark"
DEFAULTS = {
    "master-fdc1f0fe": "fdc1f0fe17415788cecfe63b08945f50031d325f",
    "branch-before": "a571080c3512ee6bc6888423551608a1a97ce76b",
}
PIPELINE = [
    "app/src/main/java/com/xnotes/storage/NotebookStorage.kt",
    "app/src/main/java/com/xnotes/ui/Editor.kt",
    "app/src/main/java/com/xnotes/storage/MigrationPublication.kt",
    "app/src/main/java/com/xnotes/storage/OwnedRecoveryTarget.kt",
    "app/src/main/java/com/xnotes/storage/NativeSourceVersion.kt",
    "app/src/main/java/com/xnotes/format/BundleSnapshot.kt",
]

def git(*args):
    return subprocess.check_output(["git", *args], cwd=REPO)

def digest(file):
    return hashlib.sha256(file.read_bytes()).hexdigest()

def prepare(name, ref, overlay, replace):
    if name in {"results", "fixture"} or name.startswith("cache"):
        raise ValueError("Archive names must not replace shared results, fixture, or caches")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", name):
        raise ValueError("Archive name must be a simple directory name")
    if (REPO / "tmp").is_symlink() or BENCH.is_symlink():
        raise ValueError("Benchmark tmp directories must not be symlinks")
    BENCH.mkdir(parents=True, exist_ok=True)
    destination = BENCH / name
    if destination.resolve().parent != BENCH.resolve():
        raise ValueError("Archives must stay directly under repository tmp/native-storage-benchmark")
    if destination.is_symlink():
        raise ValueError("Refusing a symlink archive directory")
    if destination.exists():
        if not replace:
            raise FileExistsError(f"Archive exists: {destination}; use --replace only to rebuild this generated archive")
        if destination.is_symlink():
            raise ValueError("Refusing a symlink archive directory")
        shutil.rmtree(destination)
    commit = git("rev-parse", "--verify", ref + "^{commit}").decode().strip()
    destination.mkdir()
    archive = git("archive", "--format=tar", commit)
    with tarfile.open(fileobj=io.BytesIO(archive), mode="r:") as bundle:
        for member in bundle.getmembers():
            path = (destination / member.name).resolve()
            if destination.resolve() not in path.parents and path != destination.resolve():
                raise ValueError("Archive member escapes generated directory")
            if member.issym() or member.islnk():
                raise ValueError("Benchmark source archives do not accept links")
        bundle.extractall(destination)
    overlaid = []
    if overlay:
        changed = git("ls-files", "--modified", "--others", "--exclude-standard", "-z").decode().split("\0")
        for relative in changed:
            if not relative.startswith("app/src/main/"):
                continue
            source = REPO / relative
            target = destination / relative
            if source.is_file():
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, target)
            elif target.is_file():
                target.unlink()
            overlaid.append(relative)
    # The only machine-specific input copied is the SDK location, never signing keys/configuration.
    sdk = REPO / "local.properties"
    if sdk.is_file():
        sdk_lines = [line for line in sdk.read_text().splitlines() if re.match(r"^\s*sdk\.dir\s*[:=]", line)]
        if sdk_lines:
            (destination / "local.properties").write_text("\n".join(sdk_lines) + "\n")
    before = {path: digest(destination / path) for path in PIPELINE if (destination / path).is_file()}
    # Compile the same benchmark with each revision's existing test fakes, excluding unrelated suites.
    fakes = destination / "app/src/test/java/com/xnotes/core/Fakes.kt"
    fake_bytes = fakes.read_bytes()
    shutil.rmtree(destination / "app/src/test")
    fakes.parent.mkdir(parents=True)
    fakes.write_bytes(fake_bytes)
    shared = destination / "app/src/sharedTest"
    if shared.is_dir():
        shutil.rmtree(shared)
    test = destination / "app/src/test/java/com/xnotes/storage/NativeStorageBenchmarkTest.kt"
    test.parent.mkdir(parents=True)
    shutil.copyfile(HERE / "fixture.kt", test)
    helper = destination / "app/src/main/java/com/xnotes/storage/BenchmarkStorageLockTiming.kt"
    if helper.exists():
        raise ValueError("Production must not contain the benchmark timing helper")
    shutil.copyfile(HERE / "helper.kt", helper)
    storage = destination / PIPELINE[0]
    code = storage.read_text()
    lock_count = code.count("synchronized(lock)")
    if not lock_count:
        raise ValueError("NotebookStorage's actual storage monitor was not found")
    code = code.replace("synchronized(lock)", 'BenchmarkStorageLockTiming.locked("storage",lock)')
    code = code.replace("return@synchronized", "return@locked")
    storage.write_text(code)
    editor = destination / PIPELINE[1]
    code, editor_count = re.subn(
        r"(^    private fun writeNoteSafely\([^\n]*= )synchronized\(saveLock\)",
        r'\1com.xnotes.storage.BenchmarkStorageLockTiming.locked("editor-save",saveLock)',
        editor.read_text(), flags=re.M,
    )
    if editor_count != 1:
        raise ValueError("Expected one actual Editor.writeNoteSafely monitor")
    editor.write_text(code)
    manifest = {
        "name": name, "commit": commit, "worktree_overlay": overlaid,
        "production_sha256_before_hooks": before,
        "production_sha256_after_hooks": {path: digest(destination / path) for path in before},
        "harness_sha256": {file: digest(HERE / file) for file in ["fixture.kt", "helper.kt", "init.gradle"]},
        "storage_lock_wrappers": lock_count, "editor_lock_wrappers": editor_count,
    }
    (destination / "benchmark-source-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(destination)
    print("Source commit:", commit, "Overlay files:", len(overlaid))

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--name", help="Generated archive name; no name creates both historical baselines")
    parser.add_argument("--ref", help="Git commit/reference, required for non-baseline archive names")
    parser.add_argument("--overlay-worktree", action="store_true", help="Include current modified/untracked production app sources")
    parser.add_argument("--replace", action="store_true", help="Replace only the named generated source archive, retaining shared results/fixture")
    args = parser.parse_args()
    if args.ref and not args.name:
        parser.error("--ref requires --name")
    if args.overlay_worktree and not args.name:
        parser.error("--overlay-worktree requires --name")
    names = [args.name] if args.name else DEFAULTS
    for label in names:
        reference = args.ref or DEFAULTS.get(label)
        if reference is None:
            parser.error("--ref is required for a custom archive name")
        prepare(label, reference, args.overlay_worktree, args.replace)
