# AI agent players — architecture

A multi-agent system in which LLM-backed agents play Cosmic as ordinary players: they
connect over the v83 network protocol, start knowing nothing about the world, and learn
it by acting in it and talking to each other. A human can log in with a real client,
watch them, and talk to them. Everything they come to believe, and every decision that
follows from it, is recorded so it can be replayed and inspected.

Status: design. See `docs/ai-agents/roadmap.md` for what is built.

## 1. The one decision everything else follows from

**Agents are network clients, not server code.** They speak the same TCP protocol as
MapleStory.exe, in their own process, against an unmodified server.

Two alternatives were considered and rejected:

- *In-process bots* (fake `Client`/`Character` objects inside the server). Far less work,
  and the codebase would tolerate it — but an in-process agent can read `MapleMap`
  directly, so "starts with zero knowledge" becomes a promise the code cannot keep. Every
  interesting question ("did it *learn* that slimes are weak?") stops being answerable.
- *Screen scraping a real client.* Faithful, but needs a running Windows client per agent
  and gives us pixels instead of structure.

The protocol boundary buys four things at once:

| | |
| --- | --- |
| Honest ignorance | An agent knows exactly what a player's client is told, and nothing else. Zero-knowledge is enforced by process isolation, not discipline. |
| Observability | Agents are real characters in the database. Log in with any v83 client and you'll see them walking around. |
| Upstream compatibility | The server is untouched, so merging from `P0nk/Cosmic` stays easy. |
| Honest difficulty | The agent must work out that mob `100100` is a snail from what happens when it hits one. That difficulty is the research content, not an obstacle to it. |

The cost is real: we must implement the client half of the protocol. Section 3 scopes it.

## 2. Shape of the system

```
   ┌──────────────────┐         ┌──────────────────────┐
   │ MapleStory.exe   │         │  agent runtime       │   one JVM, N agents
   │ (you, watching)  │         │  (agents.Launcher)   │
   └────────┬─────────┘         └──────────┬───────────┘
            │  v83 TCP                     │  v83 TCP  (one session per agent)
            └──────────────┬───────────────┘
                           ▼
                ┌─────────────────────┐
                │  Cosmic server      │  unmodified
                │  login + channels   │
                └─────────────────────┘

   agent runtime internals, per agent:

   packets ──▶ Perception ──▶ Memory ──▶ Cognition ──▶ Intent ──▶ packets
                   │            │            │            │
                   └────────────┴─────┬──────┴────────────┘
                                      ▼
                              Trace (JSONL, causally linked)
                                      │
                                      ▼
                              Visualiser (timeline + graph replay)
```

The agent runtime is a second process built from this same Maven module. It reuses
`net.encryption`, `net.packet` and `net.opcodes` — the wire format is shared, so
re-implementing AES-OFB or the opcode tables would only invite drift. Reuse is safe
because the agent runs in its own process and holds no server objects.

## 3. Protocol layer

`agents.net` mirrors `net.netty.ServerChannelInitializer`, with the roles swapped.

The handshake is unencrypted and tells us everything we need:

```
server ──▶ client   HELLO: version, sendIv, recvIv
```

The server then builds `ClientCyphers.of(sendIv, recvIv)`, giving `send = AESOFB(sendIv,
0xFFFF - version)` and `receive = AESOFB(recvIv, version)`. The client is the mirror:
its send cypher is built from `recvIv` with `version`, its receive cypher from `sendIv`
with `0xFFFF - version`. Same classes, swapped arguments.

### Outbound: what an agent can do

Client→server packets are small and hand-written. A first slice:

`LOGIN_PASSWORD`, `SERVERLIST_REQUEST`, `SERVERSTATUS_REQUEST`, `CHARLIST_REQUEST`,
`CHAR_SELECT`, `PLAYER_LOGGEDIN`, `MOVE_PLAYER`, `GENERAL_CHAT`, `CLOSE_RANGE_ATTACK`,
`NPC_TALK`, `NPC_TALK_MORE`, `CHANGE_MAP`, `PICKUP_ITEM`.

Movement is less frightening than its reputation. `MovePlayerHandler` skips 9 bytes and
parses a command list; command `0` is an absolute move carrying `(x, y, wobbleX, wobbleY,
foothold, state, duration)`, and the server simply takes the final position. One
command-0 fragment per move is enough to walk convincingly.

Char select needs a host string matching `[0-9A-F]{12}_[0-9A-F]{8}`; each agent generates
its own so the anti-multiclient coordinator sees distinct machines. With stock config
(`DETERRED_MULTICLIENT: false`, `USE_IP_VALIDATION: false`) many agents share one host
fine.

### Inbound: what an agent can perceive

Server→client decoding is the bulk of the work, and it is deliberately partial. The
decoder handles a subset and emits `UnknownPacket(opcode, length)` for everything else.

That fallback is a feature, not a stopgap. An agent that receives something it cannot
interpret is in the same position as a person seeing an unfamiliar UI element: it knows
*something happened*. The unknown-opcode histogram also tells us precisely which packet
to implement next, ranked by how often agents actually trip over it.

First slice, chosen to make exploration and social learning possible:

| Opcode | Becomes |
| --- | --- |
| `LOGIN_STATUS`, `SERVERLIST`, `CHARLIST`, `SERVER_IP` | login flow control, not observations |
| `WARP_TO_MAP` | `MapEntered(mapId, spawnPoint)` |
| `SPAWN_PLAYER` / `REMOVE_PLAYER_FROM_MAP` | `PlayerAppeared` / `PlayerLeft` |
| `SPAWN_MONSTER`, `SPAWN_MONSTER_CONTROL` | `MonsterAppeared(oid, mobId, pos)` |
| `KILL_MONSTER` | `MonsterDied(oid)` |
| `MOVE_MONSTER`, `MOVE_PLAYER` | `ThingMoved(oid, pos)` |
| `SPAWN_NPC` | `NpcAppeared(oid, npcId, pos)` |
| `DROP_ITEM_FROM_MAPOBJECT` | `DropAppeared(oid, itemId, pos)` |
| `CHATTEXT` | `ChatHeard(speakerId, text)` |
| `STAT_CHANGED` | `StatsChanged(hp, mp, exp, level, meso, …)` |
| `NPC_TALK` | `DialogueShown(npcId, text, style)` |
| `SERVERMESSAGE`, `SHOW_STATUS_INFO` | `NoticeShown(text)` |

Note what the agent is *not* given: names for mob ids, item meanings, map topology, or
any notion that exp is good. Those are hypotheses it has to form.

## 4. Memory

Following AriGraph (Anokhin et al., 2024), memory is two linked stores rather than a
transcript.

**Episodic** — an append-only log of `Episode(tick, agentId, observation)`. Raw, never
rewritten. This is the ground truth the agent can always fall back to, and the evidence
every belief points at.

**Semantic** — a graph of `Belief` records:

```
Belief {
  subject, predicate, object      // ("mob:100100", "drops", "item:2000000")
  confidence                      // grows with corroboration, falls on contradiction
  supportedBy: [episodeId]        // provenance, always non-empty
  firstSeen, lastSeen
  invalidatedAt, supersededBy     // bitemporal: wrong beliefs are kept, not deleted
}
```

Keeping superseded beliefs is what makes the visualiser interesting. "This agent believed
X for two hours, then saw Y and stopped" is exactly the kind of thing worth watching, and
it is lost if revision means deletion.

Retrieval scores candidates on relevance + recency + confidence, in the manner of
Generative Agents (Park et al., 2023), and returns a working set small enough to fit a
prompt.

## 5. Cognition

```java
interface Policy {
    Decision decide(WorkingMemory wm);
}
```

Two implementations, deliberately interchangeable:

- **`ReflexPolicy`** — hand-written rules (wander, attack what's near, pick up drops, say
  hello). No API cost. It bootstraps the system, provides the control condition for
  "did the LLM actually help?", and keeps agents alive when the model is unreachable.
- **`LlmPolicy`** — Claude. Receives the retrieved working set rendered as text plus the
  legal intents; returns a chosen intent, a rationale, and proposed belief updates. It is
  handed *the agent's memory*, never game state, so the zero-knowledge boundary holds
  inside the prompt too.

Two Voyager (Wang et al., 2023) ideas carry over well:

- **Automatic curriculum** — instead of a fixed goal, the agent asks what it should try
  next given what it knows and what it has never seen. Open-ended worlds reward this far
  more than a task list.
- **Skill library** — an intent sequence that demonstrably achieved something gets stored,
  named, and offered back as a single callable action. Skills compound; flat action
  choice does not.

Voyager's third idea, iterative code generation, is a poor fit here: our action space is
a dozen packet types, not arbitrary JavaScript. Choosing among typed intents is the
right granularity, and it keeps a bad LLM output from becoming a crash.

## 6. Multi-agent learning

Agents share nothing in memory. Everything one agent learns from another must travel
through a channel the game actually provides: chat, watching where someone walks,
watching someone fight, trading. An agent that discovers where to buy potions has to
*tell* someone for that knowledge to spread — and the receiver has to decide whether to
believe it.

This is the part with the least prior art. Surveys of LLM game agents note that MMO
settings, and MMO economies in particular, remain largely unexplored compared to
Minecraft. Beliefs acquired from another agent keep provenance
(`supportedBy: [episode of hearing it]`), so hearsay is visibly distinct from first-hand
knowledge, and the visualiser can colour it differently. Rumour propagation, and
agents learning who to trust, come for free from that.

## 7. Trace and visualisation

Every step appends causally-linked events to a per-run JSONL file:

```jsonl
{"t":1041,"agent":"aria","kind":"observe","id":"e412","obs":{"type":"MonsterDied","oid":9001}}
{"t":1041,"agent":"aria","kind":"believe","id":"b88","triple":["mob:100100","dies_to","skill:0"],"from":["e412","e390"],"confidence":0.4}
{"t":1042,"agent":"aria","kind":"deliberate","id":"d20","goal":"find food","used":["b88"],"considered":["Attack","MoveTo"]}
{"t":1042,"agent":"aria","kind":"act","id":"a51","intent":{"type":"Attack","oid":9002},"because":"d20"}
```

Four event kinds and two link fields (`from`, `because`) are enough to answer both
questions worth asking:

- *forwards* — this observation led to this belief, which justified this decision, which
  produced this action;
- *backwards* — why did it do that? Follow `because` → `used` → `from` → raw packets.

The visualiser is a replay over this log, not a live dashboard: a scrubber over ticks, the
belief graph as it stood at time *T*, and click-through from any node to the episodes
supporting it. Built as a static page over the JSONL, so a run can be shared as a file.

## 8. Talking to them

The human in-world is just another source of `ChatHeard`. An `LlmPolicy` agent can answer,
and because every action carries a `because` pointer, it can answer honestly about its own
reasoning: ask an agent why it walked west and it can read its own trace rather than
confabulate. Planned as a whisper convention (`why`, `what do you know about X`) handled
before normal conversation.

## 9. Layout

```
src/main/java/agents/
  Launcher.java          entry point — runs N agents
  net/                   netty client, handshake, cyphers
  protocol/              outbound builders, inbound decoder
  percept/               Observation records
  memory/                Episodic, Semantic, retrieval
  mind/                  Policy, ReflexPolicy, LlmPolicy, Curriculum, SkillLibrary
  act/                   Intent, executor
  trace/                 event log writer
viz/                     trace replay page
docs/ai-agents/          this document, roadmap, protocol notes
```

Same Maven module as the server, separate `main`. A second module would mean a parent POM
and a restructure that fights every upstream merge; the only thing shared is a jar, and
the process boundary is what matters.

## 10. Risks

| Risk | Handling |
| --- | --- |
| Inbound decoding is a long tail | Partial decoder + `UnknownPacket`; implement by observed frequency |
| The server disconnects misbehaving clients | Keep to plausible packet rates, honour `IdleStateHandler` (30s), send pongs |
| LLM cost across many agents | Reflex policy for most ticks; LLM only on decision points and reflection |
| Agents learn nothing interesting | Curriculum + skill library; the reflex policy is the control to measure against |
| Zero-knowledge quietly eroded | Any new agent-side use of a server class is reviewed; agents never import `server.*` |

## References

- Wang et al., [Voyager: An Open-Ended Embodied Agent with Large Language Models](https://arxiv.org/abs/2305.16291), 2023 — curriculum, skill library, self-verification.
- Park et al., [Generative Agents: Interactive Simulacra of Human Behavior](https://arxiv.org/abs/2304.03442), 2023 — memory stream, relevance/recency/importance retrieval, reflection.
- Anokhin et al., [AriGraph: Learning Knowledge Graph World Models with Episodic Memory for LLM Agents](https://arxiv.org/abs/2407.04363), 2024 — semantic + episodic graph memory, two-phase retrieval.
