"""检查待提交文件、Markdown 本地链接和常见凭据格式；不打印命中内容。"""
from pathlib import Path
import re
import subprocess
import sys
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[1]


def files():
    result = subprocess.check_output(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"], cwd=ROOT
    )
    return sorted(set(p for p in result.decode("utf-8").split("\0") if p))


def check():
    issues = []
    names = files()
    public = {name for name in names if (ROOT / name).is_file()}
    key_pattern = re.compile(r"\bsk-[A-Za-z0-9_-]{20,}")
    private_pattern = re.compile(r"-----BEGIN (?:RSA |OPENSSH |EC )?PRIVATE KEY-----")
    for name in names:
        path = ROOT / name
        if not path.is_file():  # 已移动或在工作树删除的旧路径
            continue
        parts = Path(name).parts
        if any(p in {".idea", ".vscode", ".venv", "node_modules", "__pycache__", ".ruff_cache", "target", "data"} for p in parts):
            issues.append(f"{name}: 本机文件或产物仍被跟踪")
        if path.name == ".env" or path.name.endswith((".pem", ".p12", ".pfx", ".iml")) or path.name == "application-dev.properties":
            issues.append(f"{name}: 私有配置或 IDE 文件仍被跟踪")
        if path.suffix.lower() not in {".md", ".py", ".java", ".ts", ".vue", ".yml", ".yaml", ".properties", ".json", ".ps1", ".sh", ".example"}:
            continue
        try:
            text = path.read_text(encoding="utf-8-sig")
        except UnicodeError:
            issues.append(f"{name}: 文本不是 UTF-8")
            continue
        for line, content in enumerate(text.splitlines(), 1):
            if key_pattern.search(content) or private_pattern.search(content):
                issues.append(f"{name}:{line}: 疑似 API Key 或私钥，请人工检查")
        if path.suffix == ".md":
            prose = re.sub(r"```.*?```", "", text, flags=re.S)
            for target in re.findall(r"!?\[[^\]]*\]\(([^)]+)\)", prose):
                target = target.strip().split(' "', 1)[0].strip("<>")
                if not target or target.startswith("#") or re.match(r"^[A-Za-z][A-Za-z0-9+.-]*:", target):
                    continue
                target = unquote(target.split("#", 1)[0])
                if target and not (path.parent / target).exists():
                    issues.append(f"{name}: 本地链接不存在: {target}")
                elif target:
                    resolved = (path.parent / target).resolve()
                    if resolved.is_file() and resolved.is_relative_to(ROOT):
                        relative = resolved.relative_to(ROOT).as_posix()
                        if relative not in public:
                            issues.append(f"{name}: 链接目标未纳入仓库: {target}")
    print(f"检查 {len(names)} 个候选文件；问题 {len(issues)} 项")
    for issue in issues:
        print(issue)
    print("范围：当前候选文件与本地链接；不验证 Git 历史、远端链接或真实服务。")
    return bool(issues)


if __name__ == "__main__":
    raise SystemExit(check())
