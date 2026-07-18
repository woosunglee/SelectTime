from __future__ import annotations

import logging

from playwright.sync_api import Page

from .notify import notify

log = logging.getLogger(__name__)

APP_CARD_SELECTORS = [
    "text=앱카드",
    "button:has-text('앱카드')",
    "a:has-text('앱카드')",
    "label:has-text('앱카드')",
    "input[value*='앱카드']",
    "[data-pay*='app' i]",
]


def select_app_card(page: Page) -> bool:
    for sel in APP_CARD_SELECTORS:
        loc = page.locator(sel)
        try:
            if loc.count() == 0:
                continue
            loc.first.click(timeout=3000)
            log.info("Clicked app-card option via %s", sel)
            return True
        except Exception:  # noqa: BLE001
            continue
    for frame in page.frames:
        for sel in APP_CARD_SELECTORS:
            loc = frame.locator(sel)
            try:
                if loc.count() == 0:
                    continue
                loc.first.click(timeout=3000)
                return True
            except Exception:  # noqa: BLE001
                continue
    return False


def wait_app_card_approval(page: Page, timeout_ms: int = 300_000) -> bool:
    notify(
        "SelectTime",
        "앱카드 승인이 필요합니다. 모바일 카드 앱에서 승인한 뒤 PC 브라우저로 돌아오세요.",
    )
    step = 4000
    elapsed = 0
    while elapsed < timeout_ms:
        try:
            content = page.content()
            if any(x in content for x in ("예약완료", "결제완료", "정상적으로", "성공")):
                return True
        except Exception:  # noqa: BLE001
            pass
        page.wait_for_timeout(step)
        elapsed += step
    return False
