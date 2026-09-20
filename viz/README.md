# Replay

`replay.html` plays a run back: the belief graph as it stood at any tick, what the agent did,
and why.

## Using it

Open `replay.html` in a browser and drop a trace onto it — they are written to
`target/traces/<run>/<AgentName>.jsonl` when you run `agents.Launcher`. Drop several files to
switch between agents with the picker.

`sample-trace.jsonl` is a real three-minute run: Agent1 walking out of Amherst, finding
monsters in map 40000 and reaching level 3. The page loads it if you serve the directory and
pass it as a parameter:

```bash
python3 -m http.server 8777
```

then open `http://localhost:8777/viz/replay.html?trace=/viz/sample-trace.jsonl`.

Opening the file straight from disk works too, but a page on `file://` cannot fetch a
sibling file, which is why drag-and-drop is the main path.

## Reading it

**The graph** is what the agent believed at the tick on the scrubber. Entities are nodes,
relationships are edges. Plain values — a level, a hit point total — are not given nodes of
their own; a number beside a node counts the attributes it holds, and clicking shows them. A
triple graph that gave `self level 3` its own node buried the interesting structure in a
cloud of integers.

- **green** first-hand, **amber** hearsay, **red dashed** a belief that has just been
  revised, left on screen briefly so you can watch it die
- **ring thickness** is confidence, which climbs as a belief is corroborated

**Click an action** in the decisions list and the beliefs that justified it light up gold in
the graph. That is the question the trace format exists to answer: not what the agent did,
but what it thought it knew when it did it.

**Click a belief** — a node or an edge — for its confidence, when it was first held, whether
it is still held, and the episodes it rests on, each with the raw observation behind it.

**Scrub backwards** to watch the graph grow, and to catch the moment a belief dies. Beliefs
are never deleted, so a revision is visible as the old edge turning red and the new one
appearing.
