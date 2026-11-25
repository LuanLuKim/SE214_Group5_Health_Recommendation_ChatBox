"""Safely move legacy data directories; dry-run unless --apply is supplied."""
from __future__ import annotations

import argparse
from pathlib import Path
import shutil

from project_config import ROOT


def migration_moves(root: Path) -> list[tuple[Path, Path]]:
    """Preflight every target before moving anything. Never merge or overwrite."""
    data = root / ".data"
    moves = []
    crawl = data / "crawl"
    if crawl.exists():
        for item in crawl.iterdir():
            if item.name not in {"collection", "checkpoint"}:
                moves.append((item, crawl / "checkpoint" / item.name))
    for source, target in (
        ("collection", "crawl/collection"),
        ("normalized", "normalize/rag"),
        ("normalized_reference", "normalize/expert_reference"),
        ("translated", "translate"),
    ):
        if (data / source).exists():
            moves.append((data / source, data / target))
    for source, target in moves:
        if source.is_symlink() or target.exists():
            raise ValueError(f"Migration conflict: {source} -> {target}")
    return moves


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()
    moves = migration_moves(ROOT)
    for source, target in moves:
        print(f"{source} -> {target}")
    if args.apply:
        for source, target in moves:
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.move(str(source), str(target))
        for name in ("crawl", "normalize", "translate", "review", "eval"):
            (ROOT / ".data" / name).mkdir(parents=True, exist_ok=True)
    print(f"{'Moved' if args.apply else 'Would move'} {len(moves)} entries.")


if __name__ == "__main__":
    main()