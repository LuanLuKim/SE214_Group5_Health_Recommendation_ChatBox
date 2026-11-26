import pytest

from app.output_checker import (
    DEFAULT_DISCLAIMER,
    SAFE_FALLBACK_VIOLATION,
    check_output,
)
from app.safety import SourceReference


def test_empty_output_fails():
    res = check_output("")
    assert not res.passed
    assert "EMPTY_OUTPUT" in res.violations
    assert res.safe_replacement == SAFE_FALLBACK_VIOLATION


def test_clean_output_passes_and_appends_disclaimer():
    raw_text = "Bạn nên uống đủ nước và nghỉ ngơi hợp lý."
    res = check_output(raw_text)
    assert res.passed
    assert res.violations == []
    assert DEFAULT_DISCLAIMER in res.sanitized_text


def test_clean_output_with_existing_disclaimer_does_not_duplicate():
    raw_text = "Nghỉ ngơi nhiều hơn. Thông tin này chỉ mang tính tham khảo."
    res = check_output(raw_text)
    assert res.passed
    assert res.sanitized_text == raw_text


def test_prohibited_diagnosis_fails():
    res = check_output("Chẩn đoán của bạn là sốt xuất huyết.")
    assert not res.passed
    assert "DIAGNOSIS_ASSERTION" in res.violations
    assert res.safe_replacement == SAFE_FALLBACK_VIOLATION


def test_prohibited_prescription_fails():
    res = check_output("Hãy uống 500mg paracetamol mỗi 4 giờ.")
    assert not res.passed
    assert "PRESCRIPTION_OR_DOSAGE" in res.violations

    res2 = check_output("Liều lượng: 2 viên mỗi ngày.")
    assert not res2.passed
    assert "PRESCRIPTION_OR_DOSAGE" in res2.violations

    res3 = check_output("Bạn nên ngừng thuốc đang uống ngay.")
    assert not res3.passed
    assert "PRESCRIPTION_OR_DOSAGE" in res3.violations


def test_weakening_care_level_fails():
    res = check_output(
        "Triệu chứng này không cần đi khám đâu, cứ yên tâm.",
        care_level="SEEK_CARE_SOON",
    )
    assert not res.passed
    assert "CARE_LEVEL_WEAKENED" in res.violations


def test_fabricated_source_url_fails():
    valid_source = SourceReference(
        id="src-1",
        name="WHO Viet Nam",
        url="https://who.int/vietnam",
        verifiedAt="2026-11-01",
    )
    res = check_output(
        "Xem thêm tại https://unauthorized-medical-blog.com/tips",
        provided_sources=[valid_source],
    )
    assert not res.passed
    assert "FABRICATED_SOURCE" in res.violations


def test_valid_source_url_passes():
    valid_source = SourceReference(
        id="src-1",
        name="WHO Viet Nam",
        url="https://who.int/vietnam",
        verifiedAt="2026-11-01",
    )
    res = check_output(
        "Theo hướng dẫn tại https://who.int/vietnam bạn nên nghỉ ngơi.",
        provided_sources=[valid_source],
    )
    assert res.passed
    assert res.violations == []
