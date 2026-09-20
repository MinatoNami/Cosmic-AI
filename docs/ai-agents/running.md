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
java -cp "target/classes:$(cat target/cp.txt)" -Dwz-path=wz agents.Launcher 127.0.0.1 8484 3
```

The arguments are host, login port and agent count. Each agent `N`:

- logs in as account `agentN`, which the server auto-registers on first sight
  (`AUTOMATIC_REGISTER` is on by default) and accepts the terms of service;
- creates character `AgentN` if the account has none, with an appearance drawn at random
  from the valid combinations in `Etc.wz/MakeCharInfo.img`;
- enters the world, greets the channel, and shuffles about for thirty seconds;
- prints a histogram of the packet opcodes it received but does not yet understand.

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
