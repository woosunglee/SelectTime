from __future__ import annotations

import argparse
import logging
import sys
from pathlib import Path

# Allow `python -m selecttime` from desktop/ with src layout
_SRC = Path(__file__).resolve().parents[1]
if str(_SRC) not in sys.path:
    sys.path.insert(0, str(_SRC))

from selecttime.auc_flow import run_doctor, run_once
from selecttime.config import load_config
from selecttime.notify import notify
from selecttime.scheduler import watch


def _setup_logging() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )
    log_dir = Path(__file__).resolve().parents[2] / "logs"
    log_dir.mkdir(parents=True, exist_ok=True)
    fh = logging.FileHandler(log_dir / "selecttime.log", encoding="utf-8")
    fh.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(name)s: %(message)s"))
    logging.getLogger().addHandler(fh)


def main(argv: list[str] | None = None) -> int:
    _setup_logging()
    parser = argparse.ArgumentParser(description="SelectTime Hogye badminton booking")
    parser.add_argument(
        "command",
        choices=["doctor", "once", "watch"],
        help="doctor=smoke, once=run now, watch=wait for open time",
    )
    parser.add_argument("--config", type=Path, default=None)
    args = parser.parse_args(argv)

    cfg = load_config(args.config)
    notify("SelectTime", f"command={args.command} payment={cfg.payment.method}")

    if args.command == "doctor":
        ok = run_doctor(cfg)
    elif args.command == "once":
        ok = run_once(cfg)
    else:
        ok = watch(cfg)
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
