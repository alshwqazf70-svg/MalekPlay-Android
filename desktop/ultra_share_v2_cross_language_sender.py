#!/usr/bin/env python3
"""One-shot Python V2 sender used by the Kotlin receiver integration test."""
import argparse
import json
from pathlib import Path

from ultra_share_v2 import connect_control_session, send_batch_v2


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", required=True)
    parser.add_argument("--control-port", type=int, required=True)
    parser.add_argument("--data-port", type=int, required=True)
    parser.add_argument("--secret", required=True)
    parser.add_argument("--source", required=True)
    parser.add_argument("--result", required=True)
    args = parser.parse_args()
    session = None
    try:
        session = connect_control_session(args.host, args.control_port, args.secret, "Python V2 sender")
        outcomes = send_batch_v2(args.host, args.data_port, args.secret, [Path(args.source)])
        Path(args.result).write_text(json.dumps(outcomes, ensure_ascii=False), encoding="utf-8")
        return 0
    except Exception as exc:
        Path(args.result).write_text(json.dumps({"error": repr(exc)}), encoding="utf-8")
        return 1
    finally:
        if session is not None:
            session.close()


if __name__ == "__main__":
    raise SystemExit(main())
