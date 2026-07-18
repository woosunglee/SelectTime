from __future__ import annotations

import logging
import time
from datetime import date, timedelta
from pathlib import Path

from playwright.sync_api import Browser, Page, sync_playwright

from .config import AppConfig
from .notify import notify
from .payment_app_card import select_app_card, wait_app_card_approval
from .payment_card import (
    fill_card_form,
    looks_like_otp,
    looks_like_payment,
    wait_for_user_auth,
)
from .secrets import get_auc_credentials

log = logging.getLogger(__name__)

SCREENSHOT_DIR = Path(__file__).resolve().parents[2] / "screenshots"


def _shot(page: Page, name: str) -> None:
    try:
        SCREENSHOT_DIR.mkdir(parents=True, exist_ok=True)
        path = SCREENSHOT_DIR / f"{int(time.time())}_{name}.png"
        page.screenshot(path=str(path), full_page=True)
        log.info("Screenshot %s", path)
    except Exception as exc:  # noqa: BLE001
        log.debug("screenshot failed: %s", exc)


def _target_date(cfg: AppConfig) -> date:
    return date.today() + timedelta(days=cfg.target.days_ahead)


class AucFlow:
    def __init__(self, cfg: AppConfig) -> None:
        self.cfg = cfg

    def run(self, *, dry_run: bool = False) -> bool:
        with sync_playwright() as p:
            browser = p.chromium.launch(headless=self.cfg.headless)
            context = browser.new_context(locale="ko-KR", viewport={"width": 1280, "height": 900})
            page = context.new_page()
            try:
                return self._run_on_page(page, browser, dry_run=dry_run)
            finally:
                context.close()
                browser.close()

    def _run_on_page(self, page: Page, browser: Browser, *, dry_run: bool) -> bool:
        notify("SelectTime", "예약 페이지 접속 중…")
        page.goto(self.cfg.facility.reservation_url, wait_until="domcontentloaded", timeout=60_000)
        self._wait_tracer(page)
        _shot(page, "after_queue")

        if not self._ensure_login(page):
            notify("SelectTime", "로그인 실패")
            _shot(page, "login_fail")
            return False

        self._wait_tracer(page)
        target = _target_date(self.cfg)
        notify("SelectTime", f"목표일 {target.isoformat()} 슬롯 선택 중…")
        if not self._select_date(page, target):
            notify("SelectTime", "날짜 선택 실패 — 달력 UI를 확인하세요")
            _shot(page, "date_fail")
            return False

        if not self._select_slot(page):
            notify("SelectTime", "선호 시간/코트 슬롯을 찾지 못했습니다")
            _shot(page, "slot_fail")
            return False

        if dry_run:
            notify("SelectTime", "doctor/dry-run: 슬롯까지 확인 완료")
            _shot(page, "dry_run_ok")
            return True

        if not self._confirm_reservation(page):
            notify("SelectTime", "예약 확정 버튼을 찾지 못했습니다")
            _shot(page, "confirm_fail")
            return False

        page.wait_for_timeout(2000)
        if looks_like_payment(page) or "결제" in (page.content() or ""):
            ok = self._pay(page)
            if not ok:
                _shot(page, "pay_fail")
                return False

        notify("SelectTime", "예약/결제 플로우 완료(또는 사용자 인증 대기 종료)")
        _shot(page, "done")
        return True

    def _wait_tracer(self, page: Page, timeout_s: int = 600) -> None:
        deadline = time.time() + timeout_s
        notified = False
        while time.time() < deadline:
            try:
                content = page.content()
            except Exception:  # noqa: BLE001
                page.wait_for_timeout(2000)
                continue
            waiting = any(
                x in content
                for x in (
                    "서비스 접속 대기",
                    "예상대기시간",
                    "TRACER",
                    "접속 사용자가 많아",
                )
            )
            blocked = any(
                x in content
                for x in ("서비스 접속이 차단", "접속이 불가능합니다", "접속량이 많아")
            )
            if blocked:
                notify("SelectTime", "접속이 차단/불가 상태입니다. 잠시 후 재시도하세요.")
                page.wait_for_timeout(5000)
                page.reload(wait_until="domcontentloaded")
                continue
            if waiting:
                if not notified:
                    notify("SelectTime", "TRACER 대기열 대기 중…")
                    notified = True
                page.wait_for_timeout(3000)
                continue
            return
        raise TimeoutError("TRACER wait timed out")

    def _ensure_login(self, page: Page) -> bool:
        content = page.content()
        if "로그아웃" in content or "님" in content:
            return True
        user, password = get_auc_credentials()
        # Try common login entry points
        for sel in [
            "a:has-text('로그인')",
            "button:has-text('로그인')",
            "text=로그인",
        ]:
            try:
                loc = page.locator(sel)
                if loc.count():
                    loc.first.click(timeout=3000)
                    page.wait_for_timeout(1000)
                    break
            except Exception:  # noqa: BLE001
                continue

        filled = False
        for frame in [page, *page.frames]:
            try:
                user_loc = frame.locator(
                    "input[name*='id' i], input[id*='id' i], input[type='text']"
                )
                pass_loc = frame.locator("input[type='password']")
                if user_loc.count() and pass_loc.count():
                    user_loc.first.fill(user)
                    pass_loc.first.fill(password)
                    filled = True
                    for submit in [
                        "button:has-text('로그인')",
                        "input[type='submit']",
                        "a:has-text('로그인')",
                    ]:
                        s = frame.locator(submit)
                        if s.count():
                            s.first.click()
                            break
                    break
            except Exception:  # noqa: BLE001
                continue

        if not filled:
            # Maybe already on a portal requiring manual login
            notify("SelectTime", "로그인 폼을 찾지 못했습니다. 브라우저에서 로그인한 뒤 대기합니다.")
            page.wait_for_timeout(60_000)
        else:
            page.wait_for_timeout(3000)
        return True

    def _select_date(self, page: Page, target: date) -> bool:
        day = str(target.day)
        # Prefer calendar cells that look bookable
        candidates = [
            f"td:has-text('{day}')",
            f"a:has-text('{day}')",
            f"button:has-text('{day}')",
            f"text={day}",
        ]
        for sel in candidates:
            locs = page.locator(sel)
            try:
                count = locs.count()
            except Exception:  # noqa: BLE001
                continue
            for i in range(min(count, 15)):
                el = locs.nth(i)
                try:
                    if not el.is_visible():
                        continue
                    txt = (el.inner_text(timeout=500) or "").strip()
                    if txt != day and not txt.startswith(day):
                        continue
                    el.click(timeout=2000)
                    page.wait_for_timeout(1500)
                    return True
                except Exception:  # noqa: BLE001
                    continue
        return False

    def _select_slot(self, page: Page) -> bool:
        preferred_times = self.cfg.target.preferred_times
        preferred_courts = self.cfg.target.preferred_courts
        content = page.content()

        # Clickable time labels
        for t in preferred_times:
            hour = t.split(":")[0]
            patterns = [t, f"{t}:00", f"{hour}시", f"{int(hour)}시"]
            for pat in patterns:
                loc = page.locator(f"text={pat}")
                try:
                    if loc.count() == 0:
                        continue
                    for i in range(min(loc.count(), 10)):
                        el = loc.nth(i)
                        if not el.is_visible():
                            continue
                        parent_text = ""
                        try:
                            parent_text = el.locator("xpath=ancestor::*[self::tr or self::li or self::div][1]").inner_text()
                        except Exception:  # noqa: BLE001
                            parent_text = el.inner_text()
                        if preferred_courts and not any(c in parent_text for c in preferred_courts):
                            # still allow if court filter empty match later
                            if not any(c in content for c in preferred_courts):
                                pass
                            else:
                                continue
                        # Skip unavailable markers nearby
                        if any(x in parent_text for x in ("불가", "마감", "환", "대기")):
                            continue
                        el.click(timeout=2000)
                        page.wait_for_timeout(1000)
                        return True
                except Exception:  # noqa: BLE001
                    continue
        return False

    def _confirm_reservation(self, page: Page) -> bool:
        for sel in [
            "button:has-text('예약')",
            "a:has-text('예약')",
            "button:has-text('신청')",
            "button:has-text('다음')",
            "input[type='submit']",
        ]:
            loc = page.locator(sel)
            try:
                if loc.count() and loc.first.is_visible():
                    loc.first.click(timeout=3000)
                    page.wait_for_timeout(1500)
                    return True
            except Exception:  # noqa: BLE001
                continue
        return False

    def _pay(self, page: Page) -> bool:
        method = self.cfg.payment.method
        notify("SelectTime", f"결제 진행 ({method})")
        if method == "app_card":
            if not select_app_card(page):
                notify("SelectTime", "앱카드 버튼을 찾지 못했습니다. 직접 선택하세요.")
            return wait_app_card_approval(page)

        if not fill_card_form(page, self.cfg.payment.installment_months):
            wait_for_user_auth(page)
            return True
        page.wait_for_timeout(2000)
        if looks_like_otp(page):
            wait_for_user_auth(page)
        return True


def run_doctor(cfg: AppConfig) -> bool:
    """Smoke: open site, wait queue, login check, try date navigation."""
    flow = AucFlow(cfg)
    return flow.run(dry_run=True)


def run_once(cfg: AppConfig) -> bool:
    return AucFlow(cfg).run(dry_run=False)
