from __future__ import annotations

import logging
import re
from typing import Any

from playwright.sync_api import Page, Frame

from .notify import notify
from .secrets import get_card_secrets, mask_card

log = logging.getLogger(__name__)

CARD_NUMBER_SELECTORS = [
    "input[name*='card' i][name*='no' i]",
    "input[name*='cardNo' i]",
    "input[id*='cardNo' i]",
    "input[placeholder*='카드' i]",
    "input[autocomplete='cc-number']",
]
EXPIRY_SELECTORS = [
    "input[name*='exp' i]",
    "input[autocomplete='cc-exp']",
    "input[placeholder*='유효' i]",
    "input[placeholder*='MM' i]",
]
CVC_SELECTORS = [
    "input[name*='cvc' i]",
    "input[name*='cvv' i]",
    "input[autocomplete='cc-csc']",
    "input[placeholder*='CVC' i]",
    "input[placeholder*='CVV' i]",
]
SUBMIT_SELECTORS = [
    "button:has-text('결제')",
    "button:has-text('다음')",
    "button:has-text('확인')",
    "input[type='submit']",
    "a:has-text('결제')",
]


def _all_frames(page: Page) -> list[Page | Frame]:
    frames: list[Page | Frame] = [page]
    frames.extend(page.frames)
    return frames


def _fill_first(target: Page | Frame, selectors: list[str], value: str) -> bool:
    if not value:
        return False
    for sel in selectors:
        loc = target.locator(sel)
        try:
            if loc.count() == 0:
                continue
            el = loc.first
            if el.is_visible(timeout=500):
                el.fill(value, timeout=2000)
                return True
        except Exception:  # noqa: BLE001
            continue
    return False


def _click_first(target: Page | Frame, selectors: list[str]) -> bool:
    for sel in selectors:
        loc = target.locator(sel)
        try:
            if loc.count() == 0:
                continue
            el = loc.first
            if el.is_visible(timeout=500):
                el.click(timeout=2000)
                return True
        except Exception:  # noqa: BLE001
            continue
    return False


def normalize_expiry(expiry: str) -> str:
    digits = re.sub(r"\D", "", expiry)
    if len(digits) == 4:
        return f"{digits[:2]}/{digits[2:]}"
    return expiry


def looks_like_payment(page: Page) -> bool:
    text = ""
    try:
        text = page.content()
    except Exception:  # noqa: BLE001
        return False
    markers = ("결제", "카드번호", "유효기간", "CVC", "할부", "payment", "cardNo")
    return any(m.lower() in text.lower() for m in markers)


def looks_like_otp(page: Page) -> bool:
    try:
        text = page.content().lower()
    except Exception:  # noqa: BLE001
        return False
    markers = ("인증번호", "otp", "ars", "3d secure", "본인인증", "sms")
    return any(m in text for m in markers)


def fill_card_form(page: Page, installment_months: int = 0) -> bool:
    secrets = get_card_secrets()
    log.info("Filling card form for %s", mask_card(secrets["number"]))
    number = re.sub(r"\D", "", secrets["number"])
    expiry = normalize_expiry(secrets["expiry"])
    filled_any = False

    for frame in _all_frames(page):
        if _fill_first(frame, CARD_NUMBER_SELECTORS, number):
            filled_any = True
        if _fill_first(frame, EXPIRY_SELECTORS, expiry):
            filled_any = True
        if _fill_first(frame, CVC_SELECTORS, secrets["cvc"]):
            filled_any = True
        if secrets["password"]:
            _fill_first(
                frame,
                ["input[name*='pwd' i]", "input[type='password']"],
                secrets["password"],
            )
        if secrets["birth"]:
            _fill_first(
                frame,
                ["input[name*='birth' i]", "input[name*='ssn' i]"],
                secrets["birth"],
            )
        if installment_months:
            try:
                frame.locator("select").first.select_option(str(installment_months))
            except Exception:  # noqa: BLE001
                pass

    if not filled_any:
        notify("SelectTime", "카드 입력란을 찾지 못했습니다. 브라우저에서 직접 입력하세요.")
        return False

    for frame in _all_frames(page):
        if _click_first(frame, SUBMIT_SELECTORS):
            break
    return True


def wait_for_user_auth(page: Page, timeout_ms: int = 300_000) -> None:
    notify("SelectTime", "추가 인증(OTP/3DS)이 필요합니다. 브라우저에서 완료하세요.")
    page.wait_for_timeout(min(timeout_ms, 5_000))
    # Poll until payment markers disappear or success text appears
    deadline = timeout_ms
    step = 3000
    elapsed = 0
    while elapsed < deadline:
        try:
            content = page.content()
            if any(x in content for x in ("예약완료", "결제완료", "정상적으로", "성공")):
                return
            if not looks_like_otp(page) and not looks_like_payment(page):
                return
        except Exception:  # noqa: BLE001
            pass
        page.wait_for_timeout(step)
        elapsed += step
