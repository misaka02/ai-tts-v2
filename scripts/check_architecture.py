#!/usr/bin/env python3
"""Static architecture and hygiene guard for ai-tts-v2."""

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parent.parent
MAX_MAIN_KT_LINES = 350

BANNED_PATTERNS = [
    ("MediaProjection", "MediaProjection is banned in v2"),
    ("BaseDexClassLoader", "Reflective DexClassLoader/native path injection is banned"),
    ("MANAGE_EXTERNAL_STORAGE", "MANAGE_EXTERNAL_STORAGE is banned"),
    ("RECORD_AUDIO", "RECORD_AUDIO permission is banned"),
    ("FOREGROUND_SERVICE_MEDIA_PROJECTION", "MediaProjection FGS permission is banned"),
    ('android:allowBackup="true"', "allowBackup=true is banned; set android:allowBackup=\"false\""),
    ("getExternalStoragePublicDirectory", "Legacy external storage write is banned; use MediaStore"),
]

WINDOWS_C_PATH_RE = re.compile(r"[A-Za-z]:\\\\Users\\\\|[Cc]:[/\\\\](?:Temp|Users)")


def iter_kt_files(base: Path) -> list[Path]:
    if not base.exists():
        return []
    return sorted(base.rglob("*.kt"))


def check_file_limits_and_imports(violations: list[str]) -> None:
    for module in ("core-model", "core-engine", "core-reader", "app"):
        for src_set in ("main", "cloud", "full"):
            src_dir = ROOT / module / "src" / src_set
            for kt_file in iter_kt_files(src_dir):
                rel = kt_file.relative_to(ROOT).as_posix()
                text = kt_file.read_text(encoding="utf-8")
                lines = text.splitlines()
                if len(lines) > MAX_MAIN_KT_LINES:
                    violations.append(
                        f"[FILE_SIZE] {rel} has {len(lines)} lines (limit {MAX_MAIN_KT_LINES})"
                    )

                for idx, line in enumerate(lines, start=1):
                    stripped = line.strip()
                    if module in ("core-model", "core-reader") and (
                        stripped.startswith("import android.") or stripped.startswith("import okhttp3.")
                    ):
                        violations.append(f"[LAYER] {rel}:{idx} forbidden import in {module}: {stripped}")
                    if module == "core-engine" and stripped.startswith("import android."):
                        violations.append(f"[LAYER] {rel}:{idx} forbidden android import in core-engine: {stripped}")
                    if module == "app" and "/ui/" in f"/{rel}":
                        for bad_ui_imp in (
                            "import okhttp3.",
                            "import com.k2fsa.",
                            "import android.media.MediaPlayer",
                            "import android.content.SharedPreferences",
                        ):
                            if stripped.startswith(bad_ui_imp):
                                violations.append(f"[UI_LAYER] {rel}:{idx} forbidden import in ui/: {stripped}")

                    if WINDOWS_C_PATH_RE.search(line):
                        violations.append(f"[C_DRIVE] {rel}:{idx} hardcoded C: path found")

                check_okhttp_execute_use(rel, lines, violations)


def check_okhttp_execute_use(rel: str, lines: list[str], violations: list[str]) -> None:
    for idx, line in enumerate(lines, start=1):
        if ".newCall(" in line:
            window = "\n".join(lines[idx - 1 : min(len(lines), idx + 4)])
            if ".execute()" in window and ".use" not in window:
                violations.append(
                    f"[OKHTTP_LEAK] {rel}:{idx} .newCall(...).execute() must be closed with .use {{ ... }}"
                )


def check_banned_tokens(violations: list[str]) -> None:
    scan_exts = {".kt", ".java", ".xml", ".kts"}
    for path in ROOT.rglob("*"):
        if not path.is_file() or path.suffix not in scan_exts:
            continue
        rel = path.relative_to(ROOT).as_posix()
        if "/build/" in f"/{rel}" or "/.gradle/" in f"/{rel}" or rel.startswith("scripts/"):
            continue
        text = path.read_text(encoding="utf-8", errors="ignore")
        for token, reason in BANNED_PATTERNS:
            if token in text:
                violations.append(f"[BANNED_API] {rel}: contains `{token}` ({reason})")


def main() -> int:
    violations: list[str] = []
    check_file_limits_and_imports(violations)
    check_banned_tokens(violations)
    if violations:
        print("Architecture check FAILED:")
        for v in violations:
            print(f"  - {v}")
        return 1
    print("Architecture check PASSED (0 violations).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
