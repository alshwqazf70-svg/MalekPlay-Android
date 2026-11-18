#!/usr/bin/env python3
"""One-shot desktop V2 receiver used by the Kotlin/Python TCP integration test."""
import argparse
import json
import threading
from pathlib import Path

from ultra_share_v2 import receive_batch_v2, serve_control_once


def mark_ready(path: str):
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text("ready", encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--control-port", type=int, required=True)
    parser.add_argument("--data-port", type=int, required=True)
    parser.add_argument("--secret", required=True)
    parser.add_argument("--output", required=True)
    parser.add_argument("--control-ready", required=True)
    parser.add_argument("--data-ready", required=True)
    parser.add_argument("--result", required=True)
    args = parser.parse_args()

    control_errors = []
    def control_worker():
        try:
            serve_control_once(
                args.control_port, args.secret, "UltraShare Python Test Receiver",
                lambda peer_name, peer_host: True,
                on_listening=lambda _port: mark_ready(args.control_ready),
            )
        except Exception as exc:
            control_errors.append(repr(exc))

    control_thread = threading.Thread(target=control_worker, daemon=True)
    control_thread.start()
    try:
        outcomes = receive_batch_v2(
            args.data_port, args.secret, Path(args.output),
            peer_identity="kotlin-python-integration-test",
            on_listening=lambda _port: mark_ready(args.data_ready),
        )
        Path(args.result).write_text(json.dumps(outcomes, ensure_ascii=False), encoding="utf-8")
        return 0
    except Exception as exc:
        Path(args.result).write_text(json.dumps({"error": repr(exc)}), encoding="utf-8")
        return 1
    finally:
        control_thread.join(timeout=3)


if __name__ == "__main__":
    raise SystemExit(main())
