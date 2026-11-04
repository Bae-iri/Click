# Jev screen selection

Set server environment variables and restart the backend:

```dotenv
AI1_UI_SELECTOR_PROVIDER=jev
TYPESAFE_API_KEY=<server secret>
JEV_MODEL=jev-1.14.0
JEV_MIN_CONFIDENCE=0.8
```

Default `local` retains existing behavior. Goal classification has a separate provider.
The server reuses goal_jev authentication, fixed HTTPS endpoint, pinned version validation,
8-second timeout, disabled redirects/proxy inheritance and strict Choice response validation.
Missing credentials or an unpinned model fail configuration at adapter creation.

The resolver selects graph transition + observed node, including role-eligible sliders
that local label matching misses. Input includes minimized labels, nearby descriptions,
parent links, values, graph conditions and verification predicates. Raw goals, audio,
images, coordinates, session identifiers and actual node identifiers are not sent.
The selected binding is checked locally; existing guide generation preserves graph
confirmation and safety requirements. The app must revalidate node, active window,
bounds and observation freshness immediately before operating. `revalidate_before_action`
expresses this requirement; it does not add client-side enforcement.

Unknown/low confidence choices, waiting, malformed responses and HTTP errors yield
observe_only and request_fresh_observation. They never trigger automatic home/back/scroll
recovery. Pending action results are observed before further selection. Failed operations
on an unchanged screen remain paused until the screen changes.

This opt-in sends selected accessibility text to TypeSafe; existing privacy masking
is best effort. No live API call is part of automated tests.

Protocol references: [System One](https://docs.typesafe.ai/api/system-one),
[Choice](https://docs.typesafe.ai/primitives/choice).
