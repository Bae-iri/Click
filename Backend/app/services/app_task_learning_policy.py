"""Shared wire fingerprints and native grounding; no Agent2 import in live planning."""
import hashlib
import json

VERSION = 'app-task-learning.v2'
CONSUMER = 'ai2-app-tasks.v1'
SEEN = 'ai2-app-tasks-seen.v1'
# v2: interactive-control route keys and user-confirmed last-step effects; v1 memories are not reused.
POLICY_HASH = hashlib.sha256(b'app-task-learning.v2:device-evidence:owner-scoped:two-confirmed-tasks:prefix:arrival-and-observation-order:route-key-v2:user-confirmed-last-effect').hexdigest()
ZERO = '0' * 64


def digest(value):
    return hashlib.sha256(value.encode('utf-8')).hexdigest()


def fingerprint(value):
    return digest(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',',':')))


def target_key(node):
    return digest(node.role+'\n'+node.resource+'\n'+node.label)


def screen_key(screen):
    """Mirror of AppTaskObservation.routeKey: interactive controls and native state only.

    Static result/heading text and scroll viewport hashes are excluded so that the
    verification, result and recall projections of one page share a key.
    """
    keys = sorted(target_key(n)+'|'+str(int(n.clickable))+str(int(n.editable))+str(int(n.scrollable))
        +'|'+str(int(n.selected))+'|'+('null' if n.checked is None else str(n.checked).lower())
        +'|'+str(int(n.has_value)) for n in screen.nodes
        if n.clickable or n.editable or n.scrollable or n.role in ('toggle','tab'))
    return digest('route.v2\n'+screen.package+'\n'+screen.status+'\n'+'\n'.join(keys))


def step_key(before,tool,target,after):
    return digest('\n'.join((before,tool,target,after)))
