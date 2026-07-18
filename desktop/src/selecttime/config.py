from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml
from dotenv import load_dotenv


ROOT = Path(__file__).resolve().parents[2]
REPO_ROOT = ROOT.parent


@dataclass
class FacilityConfig:
    name: str
    reservation_url: str


@dataclass
class TargetConfig:
    days_ahead: int
    preferred_times: list[str]
    preferred_courts: list[str] = field(default_factory=list)


@dataclass
class ScheduleConfig:
    open_hour: int
    open_minute: int
    lead_seconds: int = 30


@dataclass
class PaymentConfig:
    method: str  # card | app_card
    installment_months: int = 0


@dataclass
class RetryConfig:
    max_attempts: int
    interval_seconds: float


@dataclass
class AppConfig:
    facility: FacilityConfig
    target: TargetConfig
    schedule: ScheduleConfig
    payment: PaymentConfig
    retry: RetryConfig
    headless: bool = False


def _load_yaml(path: Path) -> dict[str, Any]:
    with path.open(encoding="utf-8") as f:
        data = yaml.safe_load(f) or {}
    if not isinstance(data, dict):
        raise ValueError(f"Config must be a mapping: {path}")
    return data


def find_config_path() -> Path:
    candidates = [
        ROOT / "config.local.yaml",
        ROOT / "config.yaml",
        REPO_ROOT / "shared" / "config.example.yaml",
    ]
    for p in candidates:
        if p.is_file():
            return p
    raise FileNotFoundError(
        "No config found. Copy shared/config.example.yaml to desktop/config.local.yaml"
    )


def load_config(path: Path | None = None) -> AppConfig:
    load_dotenv(ROOT / ".env")
    cfg_path = path or find_config_path()
    raw = _load_yaml(cfg_path)

    facility = raw.get("facility") or {}
    target = raw.get("target") or {}
    schedule = raw.get("schedule") or {}
    payment = raw.get("payment") or {}
    retry = raw.get("retry") or {}

    method = str(payment.get("method", "card")).strip().lower()
    if method not in ("card", "app_card"):
        raise ValueError("payment.method must be 'card' or 'app_card'")

    return AppConfig(
        facility=FacilityConfig(
            name=str(facility.get("name", "호계배드민턴장")),
            reservation_url=str(
                facility.get(
                    "reservation_url",
                    "https://www.auc.or.kr/reservation/program/facility/calendar1"
                    "?facilityCategoryNo=1&menuLevel=2&menuNo=403",
                )
            ),
        ),
        target=TargetConfig(
            days_ahead=int(target.get("days_ahead", 7)),
            preferred_times=[str(t) for t in (target.get("preferred_times") or [])],
            preferred_courts=[str(c) for c in (target.get("preferred_courts") or [])],
        ),
        schedule=ScheduleConfig(
            open_hour=int(schedule.get("open_hour", 15)),
            open_minute=int(schedule.get("open_minute", 0)),
            lead_seconds=int(schedule.get("lead_seconds", 30)),
        ),
        payment=PaymentConfig(
            method=method,
            installment_months=int(payment.get("installment_months", 0)),
        ),
        retry=RetryConfig(
            max_attempts=int(retry.get("max_attempts", 20)),
            interval_seconds=float(retry.get("interval_seconds", 2)),
        ),
        headless=bool(raw.get("headless", False)),
    )


def env(name: str, default: str = "") -> str:
    return os.getenv(name, default).strip()
