"""Machine-translation backends (EN -> VI), all with one interface.

  nllb     facebook/nllb-200-distilled-600M   multilingual seq2seq
  vinai    vinai/vinai-translate-en2vi-v2     EN-VI seq2seq (mBART)
  medcrab  pnnbao-ump/MedCrab-1.5B            medical EN-VI, causal LM

Model ids and generation settings come from ``config.json``. Each engine
translates a list of short segments (lines or sentences): inputs are sorted by
length, batched, and a batch that does not fit in memory is halved and
retried. Models are loaded on first use and released with ``unload()`` so
three engines can take turns on a small GPU.

No imports from apps/, packages/, database/ or any backend/frontend code.
"""
from __future__ import annotations

import abc
import json
from typing import Any, Callable


def resolve_device(device: str) -> str:
    if device != "auto":
        return device
    try:
        import torch
        return "cuda" if torch.cuda.is_available() else "cpu"
    except ImportError:
        return "cpu"


class TranslationEngine(abc.ABC):
    """Translate short English segments into Vietnamese."""

    kind = ""

    def __init__(self, key: str, settings: dict[str, Any], device: str = "auto"):
        self.key = key
        self.settings = dict(settings)
        self.model_id: str = settings["model_id"]
        self.device = device
        self.batch_size = int(settings.get("batch_size", 16))
        self.max_new_tokens = int(settings.get("max_new_tokens", 384))
        self._model = None
        self._tokenizer = None

    @property
    def signature(self) -> str:
        """Identifies model + generation settings; cached translations are keyed by it."""
        relevant = {k: v for k, v in sorted(self.settings.items()) if k != "batch_size"}
        return json.dumps(relevant, sort_keys=True)

    # -- model lifecycle ---------------------------------------------------
    @abc.abstractmethod
    def _load(self) -> None: ...

    @abc.abstractmethod
    def _generate(self, batch: list[str]) -> list[str]: ...

    def load(self) -> None:
        if self._model is None:
            self.device = resolve_device(self.device)
            self._load()
            self._model.eval()

    def unload(self) -> None:
        self._model = None
        self._tokenizer = None
        try:
            import gc
            import torch
            gc.collect()
            if torch.cuda.is_available():
                torch.cuda.empty_cache()
        except ImportError:
            pass

    def _dtype(self):
        import torch
        return torch.float16 if self.device.startswith("cuda") else torch.float32

    # -- public API --------------------------------------------------------
    def translate(self, texts: list[str], on_batch: Callable[[dict[str, str]], None] | None = None) -> list[str]:
        """Translations in the order of ``texts``; ``on_batch`` receives each finished batch (for caching)."""
        if not texts:
            return []
        self.load()
        order = sorted(range(len(texts)), key=lambda i: len(texts[i]), reverse=True)
        out: list[str] = [""] * len(texts)
        size = self.batch_size
        start = 0
        while start < len(order):
            ids = order[start:start + size]
            batch = [texts[i] for i in ids]
            try:
                results = self._generate(batch)
            except RuntimeError as exc:  # CUDA out of memory: retry this batch smaller
                if "out of memory" not in str(exc).lower() or size == 1:
                    raise
                size = max(1, size // 2)
                self._free_cuda()
                continue
            for i, text in zip(ids, results):
                out[i] = " ".join(text.split())
            if on_batch:
                on_batch({texts[i]: out[i] for i in ids})
            start += len(ids)
        return out

    @staticmethod
    def _free_cuda() -> None:
        import torch
        if torch.cuda.is_available():
            torch.cuda.empty_cache()


class Seq2SeqEngine(TranslationEngine):
    """Encoder-decoder model with language codes (NLLB-200, mBART)."""

    def _load(self) -> None:
        from transformers import AutoModelForSeq2SeqLM, AutoTokenizer
        self._tokenizer = AutoTokenizer.from_pretrained(self.model_id, src_lang=self.settings["src_lang"])
        self._model = AutoModelForSeq2SeqLM.from_pretrained(self.model_id, torch_dtype=self._dtype()).to(self.device)
        self._target_id = self._tokenizer.convert_tokens_to_ids(self.settings["tgt_lang"])
        if self._target_id is None or self._target_id == self._tokenizer.unk_token_id:
            raise ValueError(f"{self.model_id}: unknown target language code {self.settings['tgt_lang']!r}")

    def _generation_args(self) -> dict[str, Any]:
        raise NotImplementedError

    def _generate(self, batch: list[str]) -> list[str]:
        import torch
        inputs = self._tokenizer(batch, return_tensors="pt", padding=True, truncation=True,
                                 max_length=self.max_new_tokens).to(self.device)
        with torch.inference_mode():
            generated = self._model.generate(**inputs, max_new_tokens=self.max_new_tokens,
                                             num_beams=int(self.settings.get("num_beams", 4)),
                                             early_stopping=True, **self._generation_args())
        return self._tokenizer.batch_decode(generated, skip_special_tokens=True)


class NLLBEngine(Seq2SeqEngine):
    kind = "nllb"

    def _generation_args(self) -> dict[str, Any]:
        return {"forced_bos_token_id": self._target_id}


class MBartEngine(Seq2SeqEngine):
    """VinAI Translate: the decoder starts with the target language code."""

    kind = "mbart"

    def _generation_args(self) -> dict[str, Any]:
        return {"decoder_start_token_id": self._target_id}


class MedCrabEngine(TranslationEngine):
    """MedCrab-1.5B (Qwen2.5 fine-tune). The prompt is the one the model was trained with."""

    kind = "medcrab"
    PROMPT = ("<|user|>: Translate the following medical text from English to Vietnamese:"
              "<|ENGLISH_TEXT_START|>{text}<|ENGLISH_TEXT_END|>\n<|assistant|>:<|VIETNAMESE_TEXT_START|>")
    END = "<|VIETNAMESE_TEXT_END|>"

    def _load(self) -> None:
        import torch
        from transformers import AutoModelForCausalLM, AutoTokenizer
        self._tokenizer = AutoTokenizer.from_pretrained(self.model_id)
        self._tokenizer.padding_side = "left"  # batched generation with a causal LM
        if self._tokenizer.pad_token is None:
            self._tokenizer.pad_token = self._tokenizer.eos_token
        dtype = torch.bfloat16 if self.device.startswith("cuda") else torch.float32
        self._model = AutoModelForCausalLM.from_pretrained(self.model_id, torch_dtype=dtype).to(self.device)
        self._end_id = self._tokenizer.get_vocab().get(self.END, self._tokenizer.eos_token_id)

    def _generate(self, batch: list[str]) -> list[str]:
        import torch
        prompts = [self.PROMPT.format(text=text) for text in batch]
        inputs = self._tokenizer(prompts, return_tensors="pt", padding=True).to(self.device)
        longest = max(len(self._tokenizer(text)["input_ids"]) for text in batch)
        budget = min(self.max_new_tokens, 3 * longest + 48)  # a translation is never many times its source
        with torch.inference_mode():
            generated = self._model.generate(**inputs, max_new_tokens=budget, do_sample=False,
                                             eos_token_id=self._end_id,
                                             pad_token_id=self._tokenizer.pad_token_id)
        texts = self._tokenizer.batch_decode(generated[:, inputs["input_ids"].shape[1]:], skip_special_tokens=True)
        return [text.split(self.END)[0].strip() for text in texts]


KINDS: dict[str, type[TranslationEngine]] = {cls.kind: cls for cls in (NLLBEngine, MBartEngine, MedCrabEngine)}


def get_engine(name: str, config: dict[str, Any], *, device: str = "auto") -> TranslationEngine:
    """Build an engine from its ``config.json`` entry (aliases such as 'medev' are accepted)."""
    key = config.get("engine_aliases", {}).get(name.lower(), name.lower())
    settings = config.get("engines", {}).get(key)
    if settings is None:
        raise ValueError(f"Unknown engine '{name}'. Available: {sorted(config.get('engines', {}))}")
    cls = KINDS.get(settings.get("kind", ""))
    if cls is None:
        raise ValueError(f"Engine '{key}': unknown kind {settings.get('kind')!r}. Known: {sorted(KINDS)}")
    return cls(key, settings, device=device)
