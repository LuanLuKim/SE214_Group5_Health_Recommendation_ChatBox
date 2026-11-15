"""Translation pipeline: lines -> machine drafts -> LLM fusion -> quality gate.

  1. ``build_units``       chunks (and loose strings: titles, headings) become units of aligned lines
  2. ``machine_translate`` every engine translates the unique segments once (cached, resumable)
  3. ``refine``            the LLM writes the final line from the drafts (optional, cached)
  4. ``finalise``          gate per line; a failing LLM line falls back to the engines' consensus

Everything that needs a model is passed in (engines, LLM client), so the
pipeline runs in tests with fakes.

No imports from apps/, packages/, database/ or any backend/frontend code.
"""
from __future__ import annotations

from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass, field
import json
from pathlib import Path
import re
import time
from typing import Any, Callable, Iterable

from .cache import JsonlCache, digest
from .consensus import pick, support
from .glossary import Rules, find_names, name_kept
from .quality import QualityReport, check_line, check_quality
from .refine import PROMPT_VERSION, LLMClient, Stop, build_item, parse_answer, system_prompt
from .segments import harmonise, split_marker, split_sentences

Log = Callable[[str], None]
_HAS_LETTER = re.compile(r"[^\W\d_]")


@dataclass
class Unit:
    """Lines translated together: one chunk, or a group of loose strings."""
    id: str
    kind: str                       # "chunk" | "extra"
    title: str
    section: str
    lines: list[str]                # source lines as they are in the chunk
    tags: list[str] = field(default_factory=list)
    markers: list[str] = field(default_factory=list)
    bodies: list[str] = field(default_factory=list)
    drafts: dict[str, list[str]] = field(default_factory=dict)   # engine key -> draft per line
    hint_names: list[str] = field(default_factory=list)          # index names found in the source
    kept_names: list[str] = field(default_factory=list)          # ... that most engines left unchanged

    def __post_init__(self) -> None:
        pairs = [split_marker(line) for line in self.lines]
        self.markers = [marker for marker, _ in pairs]
        self.bodies = [body for _, body in pairs]

    @property
    def source_text(self) -> str:
        return "\n".join(self.lines)


@dataclass
class UnitResult:
    unit: Unit
    bodies: list[str]
    methods: list[str]              # per line: llm | consensus | consensus_fallback | copy
    picks: list[str]                # per line: engine chosen by consensus ("" when none)
    consensus: list[str]
    report: QualityReport

    @property
    def lines(self) -> list[str]:
        return [marker + body for marker, body in zip(self.unit.markers, self.bodies)]

    @property
    def text(self) -> str:
        return "\n".join(self.lines)


# -- 1. units ---------------------------------------------------------------

def build_units(chunks: Iterable[dict[str, Any]], extra_strings: Iterable[str], name_pattern,
                extra_size: int = 40) -> list[Unit]:
    units: list[Unit] = []
    for chunk in chunks:
        path = [p for p in chunk.get("heading_path") or [] if p]
        unit = Unit(id=chunk["id"], kind="chunk", title=chunk.get("title", ""),
                    section=" > ".join(path) or chunk.get("heading", ""),
                    lines=chunk["text"].split("\n"), tags=list(chunk.get("scope_tags", [])))
        unit.hint_names = find_names(chunk["text"], name_pattern)
        units.append(unit)
    strings = list(dict.fromkeys(s for s in extra_strings if s and s.strip()))
    for n, start in enumerate(range(0, len(strings), extra_size)):
        units.append(Unit(id=f"extra-{n:04d}", kind="extra", title="Titles and section headings of health articles",
                          section="", lines=strings[start:start + extra_size]))
    return units


# -- 2. machine drafts ------------------------------------------------------

def _segments(body: str, rules: Rules, max_words: int) -> list[str]:
    localised = rules.rewrite_source(body)
    return [s for s in split_sentences(localised, max_words) if s.strip()]


def machine_translate(units: list[Unit], engines: list[Any], cache_dir: Path, rules: Rules,
                      max_words: int = 60, log: Log = print) -> dict[str, dict[str, Any]]:
    """Fill ``unit.drafts`` for every engine; returns per-engine statistics."""
    line_segments = {(u.id, i): _segments(body, rules, max_words) for u in units for i, body in enumerate(u.bodies)}
    unique = list(dict.fromkeys(seg for segs in line_segments.values() for seg in segs if _HAS_LETTER.search(seg)))
    stats: dict[str, dict[str, Any]] = {}
    for engine in engines:
        cache = JsonlCache(Path(cache_dir) / f"{engine.key}.jsonl", engine.signature)
        todo = [seg for seg in unique if seg not in cache]
        log(f"  [{engine.key}] {engine.model_id}: {len(unique)} segments, {len(unique) - len(todo)} cached, "
            f"{len(todo)} to translate")
        started = time.perf_counter()
        if todo:
            done = 0

            def on_batch(batch: dict[str, str], cache=cache) -> None:
                nonlocal done
                cache.put_many(batch)
                before, done = done, done + len(batch)
                if done == len(todo) or before // 500 != done // 500:
                    log(f"  [{engine.key}] {done}/{len(todo)} ({time.perf_counter() - started:.0f}s)")

            try:
                engine.translate(todo, on_batch=on_batch)
            finally:
                engine.unload()
        for unit in units:
            drafts = []
            for i, body in enumerate(unit.bodies):
                parts = [str(cache.get(seg) or "") if _HAS_LETTER.search(seg) else seg
                         for seg in line_segments[(unit.id, i)]]
                drafts.append(harmonise(body, " ".join(parts)))
            unit.drafts[engine.key] = drafts
        stats[engine.key] = {"model_id": engine.model_id, "segments": len(unique), "translated_now": len(todo),
                             "seconds": round(time.perf_counter() - started, 1)}
    for unit in units:
        if unit.drafts:
            needed = len(unit.drafts) / 2
            unit.kept_names = [name for name in unit.hint_names
                               if sum(name_kept(name, "\n".join(d)) for d in unit.drafts.values()) > needed]
    return stats


def consensus_lines(unit: Unit, rules: Rules, settings: dict[str, Any] | None = None) -> tuple[list[str], list[str]]:
    """Per line, the draft closest to the other drafts among those that pass the hard checks."""
    lines, picks = [], []
    for i, body in enumerate(unit.bodies):
        candidates = {key: drafts[i] for key, drafts in unit.drafts.items()}
        if not body.strip() or not candidates:
            lines.append(rules.rewrite_source(body) if not _HAS_LETTER.search(body) else "")
            picks.append("")
            continue
        valid = {key: not any(issue.hard for issue in check_line(body, text, rules, settings))
                 for key, text in candidates.items()}
        best, _ = pick(candidates, valid)
        lines.append(candidates[best])
        picks.append(best)
    return lines, picks


# -- 3. LLM fusion ----------------------------------------------------------

def _problems(unit: Unit, bodies: list[str], rules: Rules, settings: dict[str, Any] | None) -> list[str]:
    found = []
    for i, (src, tgt) in enumerate(zip(unit.bodies, bodies)):
        for issue in check_line(src, tgt, rules, settings):
            if issue.hard:
                found.append(f"line {i + 1}: {issue.detail}")
    return found


def refine(units: list[Unit], client: LLMClient, rules: Rules, cache_dir: Path, llm_settings: dict[str, Any],
           quality_settings: dict[str, Any] | None = None, log: Log = print,
           max_requests: int = 0) -> tuple[dict[str, list[str]], dict[str, Any]]:
    """LLM lines per unit id (only units that were answered well) and statistics.

    Stops quietly on rate limit / quota: what is done is cached, the rest is
    left to the consensus and picked up by the next run.
    """
    system = system_prompt(rules)
    cache = JsonlCache(Path(cache_dir) / "llm.jsonl", digest(client.model, client.effort, PROMPT_VERSION, system))
    per_request = max(1, int(llm_settings.get("units_per_request", 4)))
    workers = max(1, int(llm_settings.get("workers", 1)))
    stats: dict[str, Any] = {"model": client.model, "requests": 0, "cached_units": 0, "answered_units": 0,
                             "repaired_units": 0, "stopped": ""}
    results: dict[str, list[str]] = {}

    def ask(items: list[dict[str, Any]], batch: list[Unit]) -> dict[str, list[str]]:
        answer = client.chat(system, json.dumps(items, ensure_ascii=False))
        return parse_answer(answer, {u.id: len(u.bodies) for u in batch})

    def run_round(jobs: list[tuple[Unit, dict[str, Any]]], label: str) -> dict[str, list[str]]:
        """jobs: (unit, item). Cached items are answered from disk, the rest sent in batches."""
        got: dict[str, list[str]] = {}
        pending: list[tuple[Unit, dict[str, Any], str]] = []
        for unit, item in jobs:
            key = json.dumps(item, ensure_ascii=False, sort_keys=True)
            cached = cache.get(key)
            if isinstance(cached, list) and len(cached) == len(unit.bodies):
                got[unit.id] = cached
                stats["cached_units"] += 1
            else:
                pending.append((unit, item, key))
        batches = [pending[i:i + per_request] for i in range(0, len(pending), per_request)]
        if max_requests:
            batches = batches[:max(0, max_requests - stats["requests"])]
        if not batches:
            return got
        log(f"  [llm] {label}: {len(pending)} units in {len(batches)} requests ({len(got)} from cache)")
        done = 0
        with ThreadPoolExecutor(max_workers=workers) as pool:
            futures = {pool.submit(ask, [item for _, item, _ in batch], [unit for unit, _, _ in batch]): batch
                       for batch in batches}
            for future in as_completed(futures):
                if future.cancelled():
                    continue  # queued when the LLM stopped: picked up by the next run
                batch = futures[future]
                stats["requests"] += 1
                try:
                    answers = future.result()
                except Stop as exc:
                    stats["stopped"] = str(exc)
                    for other in futures:
                        other.cancel()
                    continue
                cache.put_many({key: answers[unit.id] for unit, _, key in batch if unit.id in answers})
                got.update(answers)
                done += 1
                if done % 5 == 0 or done == len(batches):
                    log(f"  [llm] {label}: {done}/{len(batches)} requests")
        return got

    results.update(run_round([(u, build_item(u)) for u in units], "fusion"))
    stats["answered_units"] = len(results)

    by_id = {u.id: u for u in units}
    for _ in range(int(llm_settings.get("repair_rounds", 1))):
        if stats["stopped"]:
            break
        jobs = []
        for unit_id, bodies in results.items():
            problems = _problems(by_id[unit_id], bodies, rules, quality_settings)
            if problems:
                jobs.append((by_id[unit_id], build_item(by_id[unit_id], previous=bodies, problems=problems)))
        if not jobs:
            break
        repaired = run_round(jobs, "repair")
        for unit_id, bodies in repaired.items():
            if len(_problems(by_id[unit_id], bodies, rules, quality_settings)) < len(
                    _problems(by_id[unit_id], results[unit_id], rules, quality_settings)):
                results[unit_id] = bodies
                stats["repaired_units"] += 1
    if stats["stopped"]:
        log(f"  [llm] stopped ({stats['stopped']}); answered units are cached, run again later for the rest")
    return results, stats


# -- 4. final text + gate ---------------------------------------------------

def finalise(unit: Unit, llm_bodies: list[str] | None, rules: Rules,
             quality_settings: dict[str, Any] | None = None) -> UnitResult:
    consensus, picks = consensus_lines(unit, rules, quality_settings)
    bodies, methods = [], []
    for i, body in enumerate(unit.bodies):
        if not body.strip() or not _HAS_LETTER.search(body):
            bodies.append(rules.localise_numbers(body, rules.rewrite_source(body)))
            methods.append("copy")
            continue
        chosen, method = consensus[i], "consensus"
        if llm_bodies is not None:
            llm_line = llm_bodies[i]
            llm_bad = any(issue.hard for issue in check_line(body, llm_line, rules, quality_settings))
            consensus_bad = (not consensus[i].strip()
                             or any(issue.hard for issue in check_line(body, consensus[i], rules, quality_settings)))
            if llm_bad and not consensus_bad:
                method = "consensus_fallback"
            else:
                chosen, method = llm_line, "llm"
        bodies.append(rules.localise_numbers(body, rules.replace_target(body, chosen)))
        methods.append(method)
    drafts = [[d[i] for d in unit.drafts.values()] for i in range(len(unit.bodies))] if unit.drafts else None
    text = "\n".join(marker + body for marker, body in zip(unit.markers, bodies))
    report = check_quality(unit.id, unit.source_text, text, rules=rules, settings=quality_settings, drafts=drafts,
                           names=unit.kept_names, tags=unit.tags)
    return UnitResult(unit=unit, bodies=bodies, methods=methods, picks=picks, consensus=consensus, report=report)


def engine_statistics(results: list[UnitResult], rules: Rules,
                      quality_settings: dict[str, Any] | None = None) -> dict[str, dict[str, Any]]:
    """How each engine did against the final text (no reference translation is needed)."""
    totals: dict[str, Counter] = {}
    support_sum: dict[str, float] = {}
    for result in results:
        unit = result.unit
        for key, drafts in unit.drafts.items():
            counter = totals.setdefault(key, Counter())
            for i, body in enumerate(unit.bodies):
                if result.methods[i] == "copy":
                    continue
                counter["lines"] += 1
                counter["picked_by_consensus"] += result.picks[i] == key
                counter["hard_issue_lines"] += any(
                    issue.hard for issue in check_line(body, drafts[i], rules, quality_settings))
                support_sum[key] = support_sum.get(key, 0.0) + support(result.bodies[i], [drafts[i]])
    out = {}
    for key, counter in totals.items():
        lines = max(counter["lines"], 1)
        out[key] = {"lines": counter["lines"],
                    "consensus_pick_rate": round(counter["picked_by_consensus"] / lines, 4),
                    "hard_issue_line_rate": round(counter["hard_issue_lines"] / lines, 4),
                    "mean_chrf_vs_final": round(support_sum.get(key, 0.0) / lines, 4)}
    return out
