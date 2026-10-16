"""공개 차단 목록(AdGuard/ABP 형식)을 앱이 읽는 단순한 형식으로 바꾼다.

출력 형식 (blocklist/bundled/*.txt):
  # 주석
  example.com        ← 차단 (하위 도메인 포함)
  @@example.com      ← 예외 (이 목록 안에서 차단을 풀어줌)

와일드카드(*), 정규식, 경로, 대부분의 $옵션이 붙은 규칙은 DNS 로 표현할 수 없어 버린다.
사용: python -I convert_lists.py <원본파일> <출력파일> <목록이름> <출처URL>
"""
import re
import sys
from datetime import date

DOMAIN = re.compile(r"^[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?(\.[a-z0-9_]([a-z0-9_-]*[a-z0-9_])?)+$")
KEEP_OPTIONS = {"", "important"}


def convert(lines):
    block, allow = set(), set()
    for raw in lines:
        line = raw.strip()
        if not line or line[0] in "!#[":
            continue
        exception = line.startswith("@@")
        if exception:
            line = line[2:]
        if not line.startswith("||"):
            continue
        line = line[2:]
        rule, _, options = line.partition("$")
        if options not in KEEP_OPTIONS:
            continue
        domain = rule.rstrip("^").rstrip("|").lower()
        if not DOMAIN.match(domain):
            continue
        (allow if exception else block).add(domain)
    return block, allow


def main():
    src, dst, name, url = sys.argv[1:5]
    with open(src, encoding="utf-8", errors="replace") as f:
        block, allow = convert(f)
    with open(dst, "w", encoding="utf-8", newline="\n") as f:
        f.write(f"# {name}\n# 출처: {url}\n# 변환: {date.today()} · 차단 {len(block)}개 · 예외 {len(allow)}개\n")
        for d in sorted(block):
            f.write(d + "\n")
        for d in sorted(allow):
            f.write("@@" + d + "\n")
    print(f"{name}: block={len(block)} allow={len(allow)} -> {dst}")


if __name__ == "__main__":
    main()
