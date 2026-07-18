from __future__ import annotations

import logging
import time
from datetime import datetime, timedelta

from .auc_flow import run_once
from .config import AppConfig
from .notify import notify

log = logging.getLogger(__name__)


def seconds_until_open(cfg: AppConfig) -> float:
    now = datetime.now()
    open_at = now.replace(
        hour=cfg.schedule.open_hour,
        minute=cfg.schedule.open_minute,
        second=0,
        microsecond=0,
    )
    lead = timedelta(seconds=cfg.schedule.lead_seconds)
    start_at = open_at - lead
    if now >= open_at + timedelta(minutes=30):
        # Too late today — schedule tomorrow
        start_at = start_at + timedelta(days=1)
        open_at = open_at + timedelta(days=1)
    if now < start_at:
        return (start_at - now).total_seconds()
    return 0.0


def watch(cfg: AppConfig) -> bool:
    wait_s = seconds_until_open(cfg)
    if wait_s > 0:
        notify(
            "SelectTime",
            f"오픈 {cfg.schedule.open_hour:02d}:{cfg.schedule.open_minute:02d} "
            f"까지 {int(wait_s)}초 대기…",
        )
        # Sleep in chunks so Ctrl+C works
        end = time.time() + wait_s
        while time.time() < end:
            time.sleep(min(5, end - time.time()))

    notify("SelectTime", "예약 시도 시작")
    attempts = cfg.retry.max_attempts
    for i in range(1, attempts + 1):
        log.info("Attempt %s/%s", i, attempts)
        try:
            if run_once(cfg):
                notify("SelectTime", "성공")
                return True
        except Exception as exc:  # noqa: BLE001
            log.exception("attempt failed: %s", exc)
            notify("SelectTime", f"시도 {i} 실패: {exc}")
        time.sleep(cfg.retry.interval_seconds)
    notify("SelectTime", "모든 재시도 실패")
    return False
