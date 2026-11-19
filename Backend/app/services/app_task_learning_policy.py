"""Shared wire fingerprints and native grounding; no Agent2 import in live planning."""
import hashlib
import json

VERSION = 'app-task-learning.v1'
CONSUMER = 'ai2-app-tasks.v1'
SEEN = 'ai2-app-tasks-seen.v1'
POLICY_HASH = hashlib.sha256(b'app-task-learning.v1:device-evidence:owner-scoped:two-confirmed-tasks:prefix:arrival-and-observation-order').hexdigest()
ZERO = '0' * 64


def digest(value):
    return hashlib.sha256(value.encode('utf-8')).hexdigest()


def fingerprint(value):
    return digest(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',',':')))


def target_key(node):
    return digest(node.role+'\n'+node.resource+'\n'+node.label)


def screen_key(screen):
    keys = sorted(target_key(n)+'|'+str(int(n.clickable))+str(int(n.editable))+str(int(n.scrollable))
        +'|'+str(int(n.selected))+'|'+('null' if n.checked is None else str(n.checked).lower())
        +'|'+str(int(n.has_value))+'|'+n.viewport_hash for n in screen.nodes)
    return digest(screen.package+'\n'+screen.status+'\n'+'\n'.join(keys))


def step_key(before,tool,target,after):
    return digest('\n'.join((before,tool,target,after)))
