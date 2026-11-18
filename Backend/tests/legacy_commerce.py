"""Explicit old-catalog fixtures for retained Coupang graph/session compatibility.

The production catalog stays Government24-only outside these legacy tests.
"""
from copy import deepcopy
import json
from pathlib import Path
from app.main import create_app as current_app


def legacy_catalog():
    root=Path(__file__).parents[2]
    current=json.loads((root/'Backend/app/task_graphs/goal_catalog.json').read_text(encoding='utf-8'))
    legacy=json.loads((root/'AI-Agent/goal_catalog/legacy_coupang_goals.json').read_text(encoding='utf-8'))
    return {**legacy,'goals':[g for g in current['goals'] if g['target_app']=='android_settings']+legacy['goals']}


def create_app(*args,**kwargs):
    app=current_app(*args,**kwargs)
    catalog=legacy_catalog()
    preparation=app.state.turn_service.goal_preparation
    preparation.catalog=deepcopy(catalog)
    app.state.turn_service.goal_resolution.catalog=deepcopy(catalog)
    app.state.interactions.catalog=deepcopy(catalog['goals'])
    existing={family['id'] for family in preparation.requirements.document['families']}
    preparation.requirements.document['families'].extend(
        {'id':g['goal_type'],'goal_prefix':g['goal_type'],'keywords':[],'fields':[],
         'routes':[{'when':{},'target_app':'coupang'}]}
        for g in catalog['goals'] if g['target_app']=='coupang' and g['goal_type'] not in existing)
    def select_for_inputs(goal,values):
        preparation.requirements.validate_selection(goal,values)
        return app.state.turn_service.graphs.select(goal)
    app.state.turn_service.graphs.select_for_inputs=select_for_inputs
    return app
