"""Export the implemented HTTP contract using a disposable local database."""
import json
import sys
from pathlib import Path

BACKEND = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(BACKEND))

from app.main import create_app  # noqa: E402


def normalize_default_response_descriptions(schema):
    """Keep HTTPStatus wording stable across Python 3.12 and newer versions."""
    descriptions = {
        "413": ("Request Entity Too Large", "Content Too Large"),
        "422": ("Unprocessable Entity", "Unprocessable Content"),
    }
    for path in schema.get("paths", {}).values():
        for operation in path.values():
            if not isinstance(operation, dict):
                continue
            for status, (previous, canonical) in descriptions.items():
                response = operation.get("responses", {}).get(status)
                if isinstance(response, dict) and response.get("description") == previous:
                    response["description"] = canonical


if __name__ == "__main__":
    application = create_app("sqlite+pysqlite:///:memory:")
    try:
        schema = application.openapi()
        normalize_default_response_descriptions(schema)
        target = BACKEND / "docs" / "openapi.json"
        target.write_text(
            json.dumps(schema, ensure_ascii=False, indent=2) + "\n",
            encoding="utf-8",
        )
        print(f"Exported {len(schema['paths'])} paths to {target}")
    finally:
        application.state.engine.dispose()
