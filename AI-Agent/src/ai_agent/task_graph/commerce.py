"""Finite commerce contracts shared by graph authoring and runtime validation."""
import json
from pathlib import Path

DOCUMENT = json.loads((Path(__file__).resolve().parents[3] / 'goal_catalog'
    / 'commerce_execution.json').read_text(encoding='utf-8'))
PACKAGES = frozenset(DOCUMENT['packages'])
PREDICATES = {'commerce_page_visible', 'commerce_manual_result_required', 'commerce_effect_observed'}


def vocabulary():
    labels = set(DOCUMENT['navigation_labels'])
    for page in DOCUMENT['pages'].values():
        labels.update(page['titles'])
        labels.update(page['route'])
        for group in page.get('controls', ()):
            labels.update(group)
    for task in DOCUMENT['tasks'].values():
        labels.update(task.get('action_labels', ()))
        labels.update(task.get('success_labels', ()))
    return frozenset(' '.join(label.strip().lower().split()) for label in labels)
