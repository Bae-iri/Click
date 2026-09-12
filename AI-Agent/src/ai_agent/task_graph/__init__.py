"""Shared declarative graph structures and registry.

Session state belongs to runtime/replanning. Graph promotion and rollback belong
to graph_proposal.releases, not this shared read/model boundary.
"""
