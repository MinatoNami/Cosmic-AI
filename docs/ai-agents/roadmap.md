# AI agent players — roadmap

Tracks what exists against the design in `architecture.md`. Each stage is meant to end at
something you can actually watch happen.

## Stage 0 — design ✅

Architecture, protocol survey, prior-art review.

## Stage 1 — a client that connects ✅

`agents.net` + `agents.protocol` outbound. Handshake, cyphers, packet framing. An agent
logs in, picks a character, lands in a map, walks, and says something in chat.

Verified against a live server: three agents auto-registered their accounts, accepted the
terms of service, created characters from appearances read out of `MakeCharInfo.img`,
entered Amherst on channel 1 and stayed there for the run, with no server-side errors.
See `running.md`.

Two protocol details cost a round of debugging and are worth remembering: the login packet
carries six bytes of machine id before the four hwid nibbles, and a freshly registered
account is refused once with reason 23 until it accepts the terms.

## Stage 2 — perception

Inbound decoding for the first-slice opcodes. Unknown opcodes counted, not dropped.

A 30-second run of three agents standing in Amherst already sees 23 distinct opcodes, so
the histogram the launcher prints is the priority list to work through.

**Done when** a run prints a readable stream of what the agent saw, and the
unknown-opcode histogram tells us what to decode next.

## Stage 3 — memory and trace

Episodic log, semantic belief graph with provenance, JSONL trace with `from`/`because`
links.

**Done when** you can ask, offline, "why did agent X attack that monster?" and follow the
chain back to raw packets.

## Stage 4 — reflex policy and the loop

`Policy` interface, `ReflexPolicy`, intent executor, N agents in one runtime. No LLM yet —
this is the control condition.

**Done when** several agents survive unattended for an hour and their traces show belief
formation (e.g. "attacking mob 100100 reduced my HP").

## Stage 5 — visualiser

Replay page over a trace file: tick scrubber, belief graph at time *T*, click-through to
supporting episodes, hearsay vs first-hand colouring.

**Done when** watching a run back is more informative than reading the log.

## Stage 6 — LLM policy

`LlmPolicy` against Claude, curriculum, skill library, reflection. Cost controls: LLM on
decision points, reflex in between.

**Done when** an LLM agent measurably out-explores the reflex agent on the same map.

## Stage 7 — interaction

Whisper protocol (`why`, `what do you know about X`), agent-to-agent knowledge transfer
with provenance, trust.

**Done when** you can ask an agent why it did something and get an answer grounded in its
own trace, and when one agent teaching another visibly changes the second one's graph.

## Open questions

- **Tick rate.** How often does an agent decide? Too fast burns tokens, too slow looks
  robotic. Likely variable: fast reflexes, slow deliberation.
- **Death and persistence.** Do agents keep their characters across runs? Persistent
  characters make long-horizon learning observable but make experiments harder to repeat.
- **How many agents** before the server or the token budget complains. Unknown until
  Stage 4 runs.
- **Embeddings for retrieval** — worth the dependency, or is lexical matching enough at
  this scale?
