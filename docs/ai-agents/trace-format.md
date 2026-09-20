# Trace format

One JSON object per line, one file per agent per run, written to
`target/traces/<run>/<AgentName>.jsonl`.

The format exists to answer one question well: **why did it do that?** Everything else is
secondary to being able to start at an action and walk back to the packets behind it.

## Common fields

| Field | Meaning |
| --- | --- |
| `t` | tick, monotonic per agent |
| `agent` | character name |
| `kind` | `observe`, `believe`, `revise`, `deliberate` or `act` |
| `id` | reference other events point at — `e…` episode, `b…` belief, `d…` deliberation, `a…` action |

## The five kinds

**`observe`** — something was perceived. `obs.type` names the observation, `obs.detail`
carries it in full.

```json
{"t":2,"agent":"Agent0","kind":"observe","id":"e1","obs":{"type":"SelfDescribed","detail":"…"}}
```

**`believe`** — a belief was formed or strengthened. `from` lists the episodes it rests on,
`corroborated` distinguishes strengthening from creating, and `provenance` separates what was
seen from what was merely heard.

```json
{"t":2,"kind":"believe","id":"b3","triple":["self","in_map","map:10000"],
 "from":["e1"],"confidence":0.5,"provenance":"first_hand","corroborated":false}
```

**`revise`** — a belief stopped being held. The belief is not removed from the graph; this
records when it died and what replaced it, which is what lets a replay show "believed this
for 400 ticks, then stopped".

```json
{"t":50,"kind":"revise","id":"b3","triple":[…],"supersededBy":"b19","heldFor":400}
```

**`deliberate`** — a decision point. `used` names the beliefs consulted, `considered` the
options weighed, and `by` the policy that actually chose. When the policy that was asked did
not choose, `fellBack` says why in a few words.

```json
{"t":1125,"kind":"deliberate","id":"d980","goal":"hit what is in front of me","used":["b31"],
 "considered":[],"by":"reflex:fighter","fellBack":"target gone"}
```

That last field is the difference between a trace you can ask "did the model help?" and one
you cannot. An LLM policy answers with its fallback's decision far more often than with its
own — a reply arrives fifteen seconds after the question, by which time the monster it names
is dead — and the resulting action is indistinguishable from a deliberated one. Counting
`fellBack` reasons separates the three ways that happens: `between asks` and `still thinking`
are the design working (reflexes fill the gaps), while `target gone`, `model returned
nothing`, `no INTENT line` and `not an action` are answers that were paid for and thrown
away.

`by` is absent from traces written before decisions were credited; readers treat that as
unknown rather than as reflex.

**`act`** — something the agent did. `because` points at the deliberation that produced it.

## Walking it backwards

```
act.because   → deliberate
deliberate.used → [belief]
belief.from     → [episode]
episode.obs     → the packet, as perceived
```

Four hops from "it walked west" to the raw thing it saw. Nothing in the chain is
reconstructed after the fact — each link is written at the moment it was true.

## Reading it

Nothing in the Java runtime parses these back. That is deliberate: the consumer is the
replay page, where JSON parsing is free, and a JSON reader written here would exist only to
be maintained. The writer avoids a JSON dependency too — `commons-text`, already present,
handles the one genuinely error-prone part, string escaping.
