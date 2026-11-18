"""Medical translation package (EN -> VI) for normalized health chunks.

  segments   lines and sentences; bullet markers are kept aside
  glossary   localisation rules (localisation.json), number checks, medicine names from the crawled index
  engines    NLLB-200, VinAI Translate v2, MedCrab-1.5B behind one interface (config.json)
  cache      resumable per-engine cache
  consensus  chrF agreement between drafts, minimum-Bayes-risk choice
  refine     LLM post-editor that fuses the drafts (OpenAI-compatible endpoint from LLM_* variables)
  quality    line-level quality gate
  pipeline   the stages put together

No imports from apps/, packages/, database/ or any backend/frontend code.
"""
