"""Bounded in-place conversion/rotation of known private columns. No values are logged."""
from collections import defaultdict

import sqlalchemy as sa

from app.core.data_encryption import MARKER, PREFIX

# Public graph definitions and structural metadata remain queryable. User content
# and analysis/review payloads are protected, including historical cached responses.
TEXT_COLUMNS = {
    "users": ("id", ("name",)),
    "accounts": ("user_id", ("email", "phone_number")),
    "voice_requests": ("id", ("raw_goal",)),
    "product_feedback": ("id", ("source_text", "detail")),
    "graph_release_audits": ("id", ("reviewer",)),
}
JSON_COLUMNS = {
    "agent_sessions": ("id", ("goal", "device_context", "workflow", "recovery", "task_outcome", "outcome_history")),
    "agent_goal_resolutions": ("id", ("candidates", "response")),
    "agent_checkpoints": ("session_id", ("state",)),
    "agent_turns": ("id", ("response",)),
    "runtime_events": ("id", ("payload",)),
    "runtime_event_consumptions": ("id", ("result",)),
    "safety_confirmations": ("session_id", ("command",)),
    "graph_proposals": ("id", ("change", "evidence")),
    "evaluation_reports": ("id", ("failed_cases",)),
    "graph_release_audits": ("id", ("details",)),
}


def protect_data(connection, cipher, *, check_only=False, rotate=False):
    counts = defaultdict(int)
    # Revision 18 also calls this helper before revision 19 creates feedback.
    feedback_exists = "product_feedback" in sa.inspect(connection).get_table_names()
    for json_mode, definitions in ((False, TEXT_COLUMNS), (True, JSON_COLUMNS)):
        for name, (primary_key, columns) in definitions.items():
            if name == "product_feedback" and not feedback_exists:
                continue
            table = sa.table(name, sa.column(primary_key, sa.String()),
                *(sa.column(key, sa.JSON() if json_mode else sa.Text()) for key in columns))
            cursor = connection.execute(sa.select(table))
            while rows := cursor.fetchmany(200):
                for row in rows:
                    changes = {}
                    for key in columns:
                        value = row._mapping[key]
                        if value is None:
                            continue
                        scope = f"{name}.{key}"
                        encrypted = (isinstance(value, dict) and value.get("encryption") == MARKER) if json_mode else (
                            isinstance(value, str) and value.startswith(PREFIX))
                        if encrypted:
                            plain = cipher.decrypt(value["token"] if json_mode else value[len(PREFIX):], scope)
                            counts["encrypted_values"] += 1
                        else:
                            plain = value
                            counts["plaintext_values"] += 1
                            if check_only:
                                continue
                        if not check_only and (not encrypted or rotate):
                            token = cipher.encrypt(plain, scope)
                            changes[key] = {"encryption": MARKER, "token": token} if json_mode else PREFIX + token
                            counts["written_values"] += 1
                        if not json_mode and name == "accounts" and not check_only:
                            index_column = "phone_lookup" if key == "phone_number" else "email_lookup"
                            changes[index_column] = cipher.lookup(plain, scope)
                    if name == "runtime_event_consumptions" and not check_only:
                        stored = row._mapping["result"]
                        plain_result = cipher.decrypt(stored["token"], f"{name}.result") if isinstance(stored, dict) and stored.get("encryption") == MARKER else stored
                        expiry = (plain_result or {}).get("expires_at")
                        if type(expiry) is int:
                            changes["expires_at"] = expiry
                    if changes:
                        # Extra lookup/expiry columns are intentionally structural.
                        for extra in set(changes) - set(table.c.keys()):
                            table.append_column(sa.column(extra, sa.BigInteger() if extra == "expires_at" else sa.String(64)))
                        connection.execute(table.update().where(table.c[primary_key] == row._mapping[primary_key]).values(**changes))
    return dict(counts)
