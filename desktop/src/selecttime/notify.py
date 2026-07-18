from __future__ import annotations

import logging
import sys

log = logging.getLogger(__name__)


def notify(title: str, message: str) -> None:
    log.info("%s — %s", title, message)
    print(f"[{title}] {message}", flush=True)
    if sys.platform != "win32":
        return
    try:
        from win10toast import ToastNotifier

        ToastNotifier().show_toast(title, message, duration=8, threaded=True)
    except Exception as exc:  # noqa: BLE001
        log.debug("toast failed: %s", exc)
