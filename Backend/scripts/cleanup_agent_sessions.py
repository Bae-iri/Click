"""Expire idle guidance sessions and remove terminal development records."""
import argparse
import json
import os
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.database import build_engine, build_session_factory
from app.repositories.agent_sessions import AgentSessionRepository


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--retention-seconds", type=int, default=86400)
    args = parser.parse_args()
    if not os.environ.get("DATABASE_URL"):
        parser.error("Set DATABASE_URL to the development database before cleanup")
    engine = build_engine()
    try:
        result = AgentSessionRepository(build_session_factory(engine)).cleanup(args.retention_seconds)
        print(json.dumps(result))
    finally:
        engine.dispose()


if __name__ == "__main__":
    main()
