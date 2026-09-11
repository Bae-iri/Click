"""Export the implemented HTTP contract using a disposable local database."""
import json
import sys
from pathlib import Path

BACKEND = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND))

from app.main import create_app  # noqa: E402


if __name__ == "__main__":
    application = create_app("sqlite+pysqlite:///:memory:")
    try:
        target = BACKEND / "docs" / "openapi.json"
        target.write_text(
            json.dumps(application.openapi(), ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        print(f"Exported {len(application.openapi()['paths'])} paths to {target}")
    finally:
        application.state.engine.dispose()
