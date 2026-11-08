"""Finite settings execution contracts shared by online and offline agents."""
import json
from pathlib import Path

DOCUMENT = json.loads((Path(__file__).resolve().parents[3] / 'goal_catalog'
    / 'settings_execution.json').read_text(encoding='utf-8'))
CONTROLS = DOCUMENT['toggles']
