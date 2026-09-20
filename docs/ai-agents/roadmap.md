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

## Stage 4 — reflex policy and the loop ✅

`Policy` interface, `ReflexPolicy`, typed intents, executor, one thread and one trace per
agent. No LLM — this is the control condition.

A three-minute run of two agents: Agent1 walked `10000 → 20000 → 30000 → 40000`, found
monsters, threw 47 attacks, reached level 3, and revised 15 beliefs. The revision chain is
the self-model updating as it plays:

```
self in_map map:10000 -> superseded after 94 ticks
self level 1          -> superseded after 185 ticks
self level 2          -> superseded after 42 ticks
self maxhp 64         -> superseded after 42 ticks
```

Two bugs worth remembering, both invisible from unit tests:

- **The policy re-decided every tick**, so it took one step towards a door and then wandered
  off. 199 decisions, all `MoveTo`, and it never left town. A policy that picks a
  destination has to commit to it until it arrives.
- **`PLAYER_MAP_TRANSFER` was never sent.** `mapTransitioning` starts true at login, so
  until the client acknowledges arrival the server refuses every map change with "got stuck
  when changing maps" — and the flag latches, so the first portal attempt poisons all the
  rest. Agents now acknowledge on every `MapEntered` and on entering the world.

Design notes:

- **Intents are typed and coarse.** A policy says "attack that", and what a swing looks like
  on the wire stays in the executor. The worst a bad decision can do is a sensible action at
  a silly moment, not a malformed packet that drops the agent.
- **Attacks claim one point of damage.** The server bans for claiming more than a character
  could plausibly deal, so agents claim the floor. Killing slowly is a fair price.
- **Portal targets come from `Map.wz` but their destinations do not.** The file has `tm`;
  reading it would hand over the world's connectivity for free, which is most of what
  exploring means here. A player sees a doorway and has to walk through it, and so does an
  agent — where it led becomes an ordinary belief afterwards.

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
