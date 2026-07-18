from __future__ import annotations

import logging
from typing import Optional

from .config import env

log = logging.getLogger(__name__)

SERVICE = "selecttime-auc"


def _keyring_get(username: str) -> Optional[str]:
    try:
        import keyring

        return keyring.get_password(SERVICE, username)
    except Exception as exc:  # noqa: BLE001
        log.debug("keyring get failed: %s", exc)
        return None


def _keyring_set(username: str, password: str) -> None:
    try:
        import keyring

        keyring.set_password(SERVICE, username, password)
    except Exception as exc:  # noqa: BLE001
        log.warning("keyring set failed: %s", exc)


def get_auc_credentials() -> tuple[str, str]:
    user = env("AUC_ID")
    password = env("AUC_PASSWORD") or (_keyring_get(user) if user else None) or ""
    if not user or not password:
        raise RuntimeError(
            "AUC_ID and AUC_PASSWORD required. Set desktop/.env or store password in keyring."
        )
    return user, password


def store_auc_password(user: str, password: str) -> None:
    _keyring_set(user, password)


def mask_card(number: str) -> str:
    digits = "".join(c for c in number if c.isdigit())
    if len(digits) < 8:
        return "****"
    return f"{digits[:4]} **** **** {digits[-4:]}"


def get_card_secrets() -> dict[str, str]:
    number = env("CARD_NUMBER")
    if not number:
        raise RuntimeError("CARD_NUMBER required for payment.method=card (desktop/.env)")
    return {
        "number": number,
        "expiry": env("CARD_EXPIRY"),
        "cvc": env("CARD_CVC"),
        "password": env("CARD_PASSWORD"),
        "birth": env("CARD_BIRTH"),
    }
