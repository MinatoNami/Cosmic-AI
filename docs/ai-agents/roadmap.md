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

## Stage 2 — perception ✅

Inbound decoding for the first-slice opcodes. Unknown opcodes counted, not dropped.

Agents now perceive: their own character on entering the world, map changes, stat changes,
other players appearing and leaving, NPCs, monsters spawning and dying, movement, drops,
chat and notices. Decoders are tested against the server's own `PacketCreator`, because a
decoder written from notes drifts silently and the symptom is an agent misreading the world
rather than anything failing.

Two findings from running it:

- **Agents already perceive each other.** With two running, each sees the other's
  `PlayerAppeared`, hears its `ChatHeard`, and tracks its `ThingMoved`. The channel for
  social learning exists without anything extra.
- **Everything still undecoded is client UI state** — keymaps, quickslots, macros, buddy
  list, family, UI locks. None of it is world knowledge, so the decoder is complete enough
  for now and the remaining list is not a backlog.

One protocol wrinkle worth remembering: `SERVERMESSAGE` type 4 is ambiguous, since the
scrolling server message writes a flag byte before the string and `serverNotice(4, …)` does
not. The decoder reads the byte and reconstructs the string length if it turns out not to be
the flag.

## Stage 3 — memory and trace ✅

Episodic log, semantic belief graph with provenance, JSONL trace with `from`/`because`
links. Format documented in `trace-format.md`.

A 30-second run of two agents produces 93 episodes and 12 beliefs each, and the chain walks
back cleanly from any action:

```
ACTION a59: MoveTo {x:-526}
  BECAUSE d59: goal='explore the map'
    USED b6: npc:2007 present_in map:10000  conf=0.75 (first_hand)
      FROM e14: NpcAppeared
      FROM e20: NpcAppeared
```

Decisions taken here that are worth not re-litigating later:

- **`BeliefFormer` restates and never infers.** It will not conclude that a monster is
  dangerous or that an NPC sells something. Those are the conclusions the project exists to
  watch an agent reach; a rule supplying them would make the demo look better and hollow out
  the result. There is a test pinning the restraint.
- **Positions get no beliefs.** They change several times a second; the episode is the right
  home for them, not the long-term graph.
- **Hearing a claim is a fact about the speaker**, recorded as `player:3 said "…"` rather
  than as the claim itself. Turning hearsay into a belief about the world is a judgement,
  and it belongs with the policy.
- **Unknown predicates accumulate rather than replace.** Only a declared list is treated as
  exclusive. A wrongly-kept belief is easy to spot; a wrongly-deleted one is not.
- **Nothing in Java parses the trace back.** The consumer is the replay page, where JSON
  parsing is free.

Not yet exercised live: belief **revision**. Nothing an agent can reach in Amherst changes a
functional value - no map changes without portals, no damage without monsters. Unit tests
cover the mechanism; the first live revision arrives with stage 4.

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
