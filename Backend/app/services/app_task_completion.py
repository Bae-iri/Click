"""Bind a complete public evidence candidate without repairing its meaning.

The caller supplies the same filtered model-ID/control map used for its action.
Malformed, private, unselected, invented and changed-bound contracts are strict
plan failures. Only a typed result-contract failure after all strict binding
checks defers the entire candidate; it never removes a clause or native flag.
"""
from collections.abc import Collection, Mapping
from dataclasses import dataclass
from typing import Literal

from pydantic import ValidationError

from app.schemas.app_task import TaskCompletion, TaskNode, TaskResultCheck
from app.services.app_guide import public_label


CHECK_FIELDS=frozenset(('target','label','app','checked','selected'))
VALIDATION_FIELDS=frozenset(('checks','package','label','resource','role','checked','selected',
    'index','contract','other'))
VALIDATION_TYPES=frozenset(('missing','extra_forbidden','literal_error','value_error',
    'string_type','string_too_short','string_too_long','string_pattern_mismatch','bool_type',
    'int_type','float_type','list_type','tuple_type','too_short','too_long','model_type','other'))
DEFERRED_REASONS=frozenset(('completion_contract_invalid',))


@dataclass(frozen=True)
class CompletionCandidate:
    completion: TaskCompletion | None
    deferred_reason: Literal['completion_contract_invalid'] | None = None
    validation_errors: tuple[dict[str,str], ...] = ()


def _native_state(value):
    if not isinstance(value,str) or value not in {'true','false','unknown'}:
        raise ValueError('expected_state')
    return {'true':True,'false':False,'unknown':None}[value]


def _validation_errors(error, *, item=None, bound=None):
    """Closed metadata only; Pydantic messages, inputs and contexts stay private."""
    errors=[]
    for detail in error.errors(include_input=False,include_context=False,include_url=False)[:4]:
        location=['index' if isinstance(part,int) else part if part in VALIDATION_FIELDS else 'other'
            for part in detail.get('loc',())]
        if item is not None:
            if not location and bound.get('checked') is not None and bound.get('role')!='toggle':
                location=['checked']
            elif not location and bound.get('selected') is not None and bound.get('role') not in {'tab','toggle','button'}:
                location=['selected']
            location=['checks','index',*(location or ['contract'])]
        else:
            location=location or ['checks']
        kind=detail.get('type')
        errors.append(dict(field='.'.join(location[:3]),
            type=kind if kind in VALIDATION_TYPES else 'other'))
    return errors


def _model_constraint_error(error):
    details=error.errors(include_input=False,include_context=False,include_url=False)
    return bool(details) and all(not item.get('loc') and item.get('type')=='value_error' for item in details)


def _native_flag_role_mismatch(check):
    return (check['checked'] is not None and check['role']!='toggle'
        or check['selected'] is not None and check['role'] not in {'tab','toggle','button'})


def bind_completion_candidate(raw_checks, *, current_package: str,
                              selected_packages: Collection[str],
                              indexed_controls: Mapping[str,TaskNode],
                              bound_completion: TaskCompletion | None = None) -> CompletionCandidate:
    """Strictly bind all clauses, then defer only typed contract incompatibility.

    Goal-aware/actionable and current-result-only gates remain with the caller.
    An ordinary unseen public label can remain a provisional prediction. Native
    state predictions must name an observed control; desired values are neither
    coerced nor replaced with its current value.
    """
    if not isinstance(raw_checks,list) or len(raw_checks)>8:
        raise ValueError('completion')
    if bound_completion is not None:
        if raw_checks:
            raise ValueError('changed_completion')
        return CompletionCandidate(bound_completion)
    if not raw_checks:
        return CompletionCandidate(None)

    allowed_packages={current_package,*selected_packages}
    grounded=[]
    # Complete this strict pass first: a recoverable flag error cannot conceal a
    # later unsafe/private/unselected clause and accidentally preserve an action.
    for check in raw_checks:
        if not isinstance(check,dict) or set(check)!=CHECK_FIELDS:
            raise ValueError('completion_shape')
        target_id=check['target']
        if not isinstance(target_id,str):
            raise ValueError('completion_target')
        target=indexed_controls.get(target_id)
        if target_id!='none' and target is None:
            raise ValueError('completion_target')
        label=check['label']
        if not isinstance(label,str) or len(label)>80 or label and not public_label(label):
            raise ValueError('completion_label')
        label=public_label(label) if label else ''
        app=check['app']
        if not isinstance(app,str):
            raise ValueError('completion_app')
        package=current_package if app=='none' else app
        if package not in allowed_packages:
            raise ValueError('completion_app')
        if target is not None and package!=current_package:
            raise ValueError('completion_target_app')
        checked,selected=_native_state(check['checked']),_native_state(check['selected'])
        if target is None and (not label or checked is not None or selected is not None):
            raise ValueError('ungrounded_result_state')
        if target is not None and (not target.label or label not in {'',target.label}):
            raise ValueError('completion_control')
        grounded.append(dict(package=package,label=target.label if target is not None else label,
            resource=target.resource if target is not None else '',role=target.role if target is not None else '',
            checked=checked,selected=selected))

    bound=[];errors=[]
    for index,check in enumerate(grounded):
        try:
            bound.append(TaskResultCheck(**check))
        except ValidationError as error:
            if not _native_flag_role_mismatch(check) or not _model_constraint_error(error):
                raise
            errors.extend(_validation_errors(error,item=index,bound=check))
    if errors:
        return CompletionCandidate(None,'completion_contract_invalid',tuple(errors[:4]))
    try:
        return CompletionCandidate(TaskCompletion(checks=bound))
    except ValidationError as error:
        if len({check.package for check in bound})<=1 or not _model_constraint_error(error):
            raise
        return CompletionCandidate(None,'completion_contract_invalid',tuple(_validation_errors(error)))
