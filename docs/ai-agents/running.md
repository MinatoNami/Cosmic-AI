# Running agents

## 1. A server to play on

```bash
cp .env.example .env && docker compose up -d --build
```

Wait for `Cosmic is now online` in `docker compose logs -f maplestory`. The login server is
on 8484 and channels on 7575-7577.

## 2. A classpath

The agent runtime is built from this same Maven module, so it needs the project's
dependencies on the classpath:

```bash
mvn compile dependency:build-classpath -Dmdep.outputFile=target/cp.txt
```

## 3. Agents

```bash
java -cp "target/classes:$(cat target/cp.txt)" -Dwz-path=wz agents.Launcher 127.0.0.1 8484 3 10
```

The arguments are host, login port, agent count and minutes to run. Each agent `N`:

- logs in as account `agentN`, which the server auto-registers on first sight
  (`AUTOMATIC_REGISTER` is on by default) and accepts the terms of service;
- creates character `AgentN` if the account has none, with an appearance drawn at random
  from the valid combinations in `Etc.wz/MakeCharInfo.img`;
- enters the world and runs its own loop on its own thread: perceive, remember, decide, act;
- explores with the reflex policy — picks up what is underfoot, attacks what is near, takes
  a portal when there is nothing else to do;
- writes a trace to `target/traces/<run>/<AgentName>.jsonl` (see `trace-format.md`);
- prints its beliefs, sorted by confidence, when the run ends.

Characters persist, so a second run reuses them.

## 4. Watching

Log in with any v83 client pointed at the same server and go to Amherst (map 10000), where
new characters start. The agents are ordinary characters: they show up in the player list,
their chat appears in yours, and you can walk up to them.

## Troubleshooting

**`Timed out waiting for LOGIN_STATUS`** — the packet reached the server but it could not
parse it. Check `docker compose logs maplestory` for a stack trace naming the handler, and
compare the field list there against the builder in `agents.protocol.ClientPackets`.

**`Login rejected, reason 23`** — terms of service. Handled automatically; if it recurs the
acceptance is not landing.

**`Login rejected, reason 7`** — already logged in. A previous run did not disconnect
cleanly; wait for the server to time the session out, or restart the stack.

**Nothing in the map** — check the character's `map` column:
`docker compose exec db mysql -uroot cosmic -e "SELECT name,level,map FROM characters;"`

## 5. The LLM policy

```bash
export ANTHROPIC_API_KEY=...
java -cp "target/classes:$(cat target/cp.txt)" -Dwz-path=wz agents.Launcher 127.0.0.1 8484 2 10 llm
```

Without a key every call fails and the agent falls back to reflexes — survivable, but
pointless, so the launcher warns at startup.

The model is asked every eighth decision, with reflexes in between; it sees the agent's
beliefs and what is currently visible, as ids. It never sees the names — those exist only
for the person reading the output.

## Reading the output

Ids are dereferenced for you in the console report and in the replay page:

```
monster:9300018 (Tutorial Jr. Sentinel) present_in map:40000 (Maple Road: In a Small Forest)
npc:2000 (Roger) present_in map:20000 (Maple Road: Snail Garden)
```

The agent's own memory holds only the left-hand side of each pair. Names come from
`String.wz` at write time and go into the trace as `label` events, which the replay page
uses for display.

## 6. Talking to them

While a run is going, ask an agent a question:

```bash
java -cp "target/classes:$(cat target/cp.txt)" -Dwz-path=wz agents.Ask 127.0.0.1 8484 Agent0 why
```

It understands `why`, `where`, `who`, `know <thing>`, and `help`. `why` is the interesting
one — it answers from the decision it recorded at the time, naming the beliefs it consulted.

You can also tell an agent something it has not seen:

```bash
java -cp "target/classes:$(cat target/cp.txt)" -Dwz-path=wz agents.Ask 127.0.0.1 8484 Agent0 '!know monster:100100 lives_in map:104000000'
```

It takes your word at hearsay confidence and says so. If it later sees the same thing, the
belief is promoted to first-hand.

From a real client, whisper an agent the same questions, or say `Agent0: why` in map chat.

## 7. Dispositions

Agents are handed one of four inclinations in turn — wanderer, fighter, forager, talker —
which scale the reflex ladder's thresholds. They diverge as a result: different maps,
different levels, different beliefs. That divergence is what makes knowledge transfer worth
watching, since agents that all know the same things have nothing to tell each other.

Watch for `hearsay` in a trace, or in the replay page: an amber node is something the agent
was told rather than saw.
