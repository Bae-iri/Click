"""Opt-in environment identity and content-free correlation for device validation."""
import hashlib
import json
import logging
import math
import os
from pathlib import Path
import re
import subprocess

from alembic.config import Config
from alembic.script import ScriptDirectory
from fastapi import HTTPException
from sqlalchemy import select, text

from app.models.task_graph import ApprovedTaskGraph
from ai_agent.runtime.types import VerificationStatus

BACKEND = Path(__file__).resolve().parents[2]
ROOT = BACKEND.parent
logger = logging.getLogger('click.validation')


def digest(value):
    return hashlib.sha256(json.dumps(value, ensure_ascii=False, sort_keys=True,
                                    separators=(',', ':')).encode()).hexdigest()


def source_digest():
    result = hashlib.sha256()
    for folder in (BACKEND / 'app', BACKEND / 'migrations', ROOT / 'AI-Agent/src', ROOT / 'AI-Agent/goal_catalog'):
        for path in sorted(folder.rglob('*')):
            if path.is_file() and path.suffix in {'.py', '.json'} and '__pycache__' not in path.parts:
                result.update(path.relative_to(ROOT).as_posix().encode() + b'\0')
                result.update(path.read_bytes() + b'\0')
    return result.hexdigest()


def environment_manifest(factory, environment_id, *, runtime_settings=None):
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,80}', environment_id):
        raise ValueError('Invalid validation environment identity')
    config = Config(str(BACKEND / 'alembic.ini'))
    config.set_main_option('script_location', str(BACKEND / 'migrations'))
    expected = sorted(ScriptDirectory.from_config(config).get_heads())
    with factory() as db:
        actual = sorted(db.execute(text('SELECT version_num FROM alembic_version')).scalars())
        if actual != expected:
            raise ValueError('Validation database migrations do not match this code')
        from app.repositories.task_graphs import TaskGraphRepository
        graphs = []
        for row in db.scalars(select(ApprovedTaskGraph).where(
                      ApprovedTaskGraph.approved.is_(True), ApprovedTaskGraph.active.is_(True)
                  ).order_by(ApprovedTaskGraph.task_id, ApprovedTaskGraph.graph_version)):
            TaskGraphRepository._decode(row)
            graphs.append({'task_id': row.task_id, 'version': row.graph_version, 'sha256': row.graph_hash})
    revision = subprocess.run(['git', '-c', f'safe.directory={ROOT.as_posix()}', 'rev-parse', 'HEAD'],
                              cwd=ROOT, capture_output=True, text=True, check=False).stdout.strip()
    settings = os.environ if runtime_settings is None else runtime_settings
    configuration = {key: settings.get(key, default) for key, default in {
        'CLICK_COUPANG_BROWSE_ENABLED': '0', 'CLICK_GOV24_GUIDE_ENABLED': '1', 'JEV_MODEL': 'jev-1.14.0',
        'JEV_MIN_CONFIDENCE': '0.8', 'AI1_UI_SELECTOR_PROVIDER': 'rules',
        'GOAL_CLASSIFIER_PROVIDER': 'self_hosted', 'GEMINI_SETTINGS_MODEL': 'gemini-3.8-flash',
        'CLICK_GOV24_PLANNER_ENABLED': '0',
    }.items()}
    manifest = {'schema_version': 'click-validation-environment.v1', 'environment_id': environment_id,
                'runtime_configuration': configuration,
                'source_sha256': source_digest(),
                'source_revision': revision if re.fullmatch('[0-9a-f]{40}', revision) else None,
                'database_migrations': actual, 'approved_graphs': graphs}
    manifest['fingerprint'] = digest(manifest)
    return manifest


def install_validation_endpoint(app):
    environment_id = os.getenv('CLICK_VALIDATION_ENVIRONMENT_ID', '')
    if not environment_id:
        return
    baseline = environment_manifest(app.state.session_factory, environment_id)

    @app.get('/validation/environment', include_in_schema=False)
    def environment():
        try:
            current = environment_manifest(app.state.session_factory, environment_id)
            if current['fingerprint'] != baseline['fingerprint']:
                raise ValueError('Validation environment changed; restart and rebuild the paired APK')
            return current
        except Exception:
            raise HTTPException(503) from None


def log_turn(http_request, request, response=None, *, failed=False):
    if (os.getenv('CLICK_VALIDATION_DIAGNOSTICS') != '1'
            or not os.getenv('CLICK_VALIDATION_ENVIRONMENT_ID')):
        return
    try:
        _log_turn(http_request, request, response, failed=failed)
    except Exception:
        # Diagnostic recording must never change an already committed API result.
        logger.warning('Validation diagnostic unavailable')


def _log_turn(http_request, request, response=None, *, failed=False):
    hashed = lambda value: digest(value) if value is not None else None
    number = lambda value: value if type(value) in (int, float) and math.isfinite(value) and 0 <= value <= 10000 else None
    tree = getattr(request, 'accessibility_tree', None)
    vision = getattr(request, 'vision_snapshot', None)
    action = getattr(request, 'user_action_event', None)
    guide = getattr(response, 'guide_command', None)
    metadata = getattr(response, 'metadata', {}) or {}
    verification = (metadata.get('verification') or {}).get('result') or {}
    technical = metadata.get('technical_outcome') or {}
    settings = getattr(tree, 'applied_settings', None)
    record = {'schema_version': 'click-validation-turn.v1',
        'trace_id': http_request.scope.get('trace_id'),
        'environment_hash': hashed(os.getenv('CLICK_VALIDATION_ENVIRONMENT_ID')),
        'request_id_hash': hashed(request.request_id), 'session_id_hash': hashed(request.session_id),
        'episode_id_hash': hashed(getattr(response, 'episode_id', None) or getattr(request, 'episode_id', None)),
        'command_type': getattr(request, 'command_type', 'legacy_turn'),
        'observation_id_hash': hashed(getattr(tree, 'observation_id', None) or getattr(vision, 'observation_id', None)),
        'captured_at_ms': getattr(tree, 'captured_at_ms', None) or getattr(vision, 'captured_at_ms', None),
        'action_event_id_hash': hashed(getattr(action, 'event_id', None)),
        'action_guide_id_hash': hashed(getattr(action, 'guide_command_id', None)),
        'action_session_id_hash': hashed(getattr(action, 'session_id', None)),
        'action_episode_id_hash': hashed(getattr(action, 'episode_id', None)),
        'guide_id_hash': hashed(getattr(guide, 'guide_command_id', None)),
        'graph_reference': getattr(response, 'graph_reference', None),
        'font_scale': getattr(settings, 'font_scale', None), 'failed': failed,
        'baseline_font_scale': number(technical.get('baseline_font_scale')),
        'after_font_scale': number(technical.get('font_scale')),
        'baseline_slider_value': number(technical.get('baseline_value')),
        'after_slider_value': number(technical.get('after_value')),
        'baseline_observation_id_hash': hashed(technical.get('baseline_observation_id')),
        'completion_guide_id_hash': hashed(technical.get('guide_command_id')),
        'task_status': getattr(response, 'task_status', None) if getattr(response, 'task_status', None) in
            {'IN_PROGRESS', 'WAITING_CONFIRMATION', 'WAITING_RESULT', 'COMPLETED', 'BLOCKED',
             'PAUSED', 'CANCELLED', 'EXPIRED', 'ERROR'} else None,
        'verification_status': verification.get('status') if verification.get('status') in
            {value.value for value in VerificationStatus} else None,
        'technical_status': technical.get('status') if technical.get('status') in
            {'CHANGED', 'NOT_ACHIEVED', 'UNVERIFIABLE'} else None}
    if hasattr(record['graph_reference'], 'model_dump'):
        record['graph_reference'] = record['graph_reference'].model_dump()
    # No input text, node labels, credentials, user ID, raw session IDs or exception text.
    logger.info(json.dumps(record, ensure_ascii=True, sort_keys=True))
