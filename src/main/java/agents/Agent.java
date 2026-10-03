package agents;

import agents.body.Keyboard;
import agents.body.Touch;
import agents.mind.DialogueReader;
import agents.mind.Disposition;
import agents.mind.Intent;
import agents.mind.IntentExecutor;
import agents.mind.Policy;
import agents.mind.Refusals;
import agents.mind.Shopkeeping;
import agents.mind.Survival;
import agents.mind.Wardrobe;
import agents.protocol.ClientPackets;
import agents.net.LoginFlow.InWorld;
import agents.memory.Belief;
import agents.percept.Observation;
import agents.percept.Perceiver;
import agents.social.Claim;
import agents.social.Conversation;
import agents.social.Voice;
import agents.world.KnownWorld;
import agents.world.MapGeometry;
import agents.world.WorldModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * One agent, living its own loop on its own thread.
 *
 * Perceive, remember, decide, act - and write down why, every time round. Agents share
 * nothing: no memory, no world model, no coordination. Anything one knows that another does
 * not has to travel through the game, which is what makes knowledge spreading between them
 * an observation rather than an assumption.
 */
public class Agent implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(Agent.class);

    /**
     * How long between decisions. Fast enough to look alive, slow enough that the server sees
     * a plausible client and a later LLM policy is not asked to think ten times a second.
     */
    private static final Duration TICK = Duration.ofMillis(600);

    private final InWorld connection;
    private final Mind mind;
    private final Policy policy;
    private final Perceiver perceiver = new Perceiver();
    private final WorldModel world = new WorldModel();
    private final IntentExecutor executor;
    private final Voice voice;
    private final DialogueReader dialogueReader;
    private final agents.mind.InstructionReader instructionReader;
    private final agents.mind.Requests requests = new agents.mind.Requests();

    /** The conversation going on now, gathered so it can be read as a whole once it ends. */
    private int talkingWith = -1;
    private final List<String> beingTold = new java.util.ArrayList<>();
    private java.util.Map<Integer, Integer> bagsWhenItBegan = java.util.Map.of();
    private final List<Integer> questsTakenOn = new java.util.ArrayList<>();
    private int lastLineAt;

    /** A finished conversation out with the model, and who it was with. */
    private CompletableFuture<List<agents.mind.InstructionReader.Step>> instructions;
    private int instructionsFrom;
    private long instructionsAskedAt;

    /**
     * Where the thinking about an NPC's words happens, so it does not happen on the tick.
     *
     * A local model takes around fifteen seconds, and an agent frozen mid-conversation for
     * fifteen seconds is worse to watch than one that answers thoughtlessly.
     */
    private final ExecutorService reading =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "dialogue");
                t.setDaemon(true);
                return t;
            });

    private PendingDialogue pendingDialogue;
    private volatile boolean running = true;

    /** An NPC's words, out with the model, with the style byte needed to answer them. */
    private record PendingDialogue(byte style, int npcId, long askedAtMillis,
                                   CompletableFuture<java.util.Optional<DialogueReader.Reply>> answer) {
    }

    /** After this the NPC has waited long enough and gets the thoughtless answer. */
    /**
     * How long an NPC waits for an answer.
     *
     * Twenty seconds was chosen for a model that answered in about fifteen. This one takes
     * seven to eighteen on its own and longer when anything else is asking, so conversations
     * were being handed back to the reflex with "nobody read it in time" - the one thing the
     * model is actually good at, timed out. A conversation is worth waiting for; there is
     * nothing else the agent needs to be doing while an NPC holds a window open.
     */
    private static final long DIALOGUE_PATIENCE_MILLIS = 45_000;

    private final Disposition disposition;
    private final Touch touch;
    private final Keyboard keyboard = new Keyboard();
    private final Survival survival = new Survival();
    private final Wardrobe wardrobe = new Wardrobe();
    private final agents.mind.Training training;
    private final agents.mind.MakingRoom makingRoom = new agents.mind.MakingRoom();
    private final Shopkeeping shopkeeping = new Shopkeeping();
    private final Refusals refusals = new Refusals();

    /** The style of the last NPC line answered, and greetings since anyone said anything. */
    private int lastDialogueStyle = -1;
    private int greetedWithoutAnswer;
    private int steps;

    /** Steps spent dead so far, so the agent waits a moment before asking to come back. */
    private int deadFor;
    private int nowhereToGo;

    /** The last NPC whose offer this agent accepted, so a dead end has somebody to blame. */
    private int wentAlongWith = -1;

    /**
     * Asks for the health and mana that return on their own, as a client does every ten
     * seconds.
     *
     * A client asks and the server grants; nothing asked, so an agent never recovered a single
     * point without a potion. Backing off to rest when hurt did nothing but stand there: two
     * agents spent ten minutes at exactly 50 health, "catching their breath". The amounts are
     * a resting character's, far inside what the server will allow at once, and no oftener
     * than it allows.
     */
    private void recoverOverTime() {
        long now = System.currentTimeMillis();
        if (now - lastRecovered < RECOVER_EVERY_MILLIS || world.isDead()) {
            return;
        }
        boolean hurt = world.hp() >= 0 && world.maxHp() > 0 && world.hp() < world.maxHp();
        int mp = world.stat("MP");
        int maxMp = world.stat("MAXMP");
        boolean drained = mp >= 0 && maxMp > 0 && mp < maxMp;
        if (!hurt && !drained) {
            return;
        }
        lastRecovered = now;
        connection.session().send(ClientPackets.healOverTime(hurt ? RESTING_HP : 0, drained ? RESTING_MP : 0));
    }

    private long lastRecovered;
    private static final long RECOVER_EVERY_MILLIS = 10_000;
    private static final int RESTING_HP = 10;
    private static final int RESTING_MP = 3;

    /** The shop last opened, and how the agent stood when it did. */
    private int shopNpc = -1;
    private int mesosAtCounter = -1;
    private int stacksAtCounter = -1;

    private int stacksCarried() {
        int stacks = 0;
        for (int type = agents.percept.Item.EQUIP; type <= agents.percept.Item.ETC; type++) {
            stacks += world.inventory().carried(type).size();
        }
        return stacks;
    }

    /** Who the agent was last in conversation with, and what job it had then. */
    private int lastSpokeWith = -1;
    private int lastJob = -1;

    /**
     * Writes down who, where and what an NPC's words named.
     *
     * Hearsay about that NPC, because it is being told: "npc:1022000 sends_you_to map:102020300",
     * "npc:1072004 wants_first 30 item:4031013". The policy acts on these the way it acts on
     * any other belief, and a person reading the trace can see where each errand came from.
     */
    private void rememberWhatWasSaid(Observation.DialogueShown dialogue) {
        agents.mind.Instructions.Heard heard = agents.mind.Instructions.read(dialogue.text());
        if (heard.isEmpty() || dialogue.npcId() <= 0) {
            return;
        }
        String speaker = "npc:" + dialogue.npcId();
        long tick = perceiver.currentTick();
        for (agents.mind.Instructions.ItemAsked item : heard.items()) {
            mind.hear(speaker, "wants_first", item.quantity() + " item:" + item.itemId(), tick);
        }
        for (int map : heard.maps()) {
            mind.hear(speaker, "sends_you_to", "map:" + map, tick);
        }
        for (int npc : heard.npcs()) {
            if (npc != dialogue.npcId()) {
                mind.hear(speaker, "sends_you_to", "npc:" + npc, tick);
            }
        }
        // "Get this to #p1072000# who's around #m102020300#": one person and one place said
        // together is where that person stands.
        if (heard.npcs().size() == 1 && heard.maps().size() == 1 && heard.npcs().get(0) != dialogue.npcId()) {
            mind.hear("npc:" + heard.npcs().get(0), "present_in", "map:" + heard.maps().get(0), tick);
        }
    }

    /**
     * Keeps what an NPC is saying until they have finished saying it.
     *
     * One line at a time is how a dialogue window is answered, and no way to understand what
     * is being asked: Roger hands over the apple in one line and says what to do with it in
     * the next. So the lines are kept, with what the bags held when the conversation began,
     * and read together once the NPC falls quiet.
     */
    private void listen(Observation.DialogueShown dialogue) {
        if (dialogue.npcId() <= 0) {
            return;
        }
        if (dialogue.npcId() != talkingWith) {
            talkingWith = dialogue.npcId();
            beingTold.clear();
            questsTakenOn.clear();
            bagsWhenItBegan = bagCounts();
        }
        beingTold.add(dialogue.text());
        lastLineAt = steps;
    }

    /**
     * Writes down that it has asked for a quest, or offered one back.
     *
     * Kept in memory rather than in a field, so it outlives a restart: something asked for
     * and refused, or offered back and not accepted, is not still "something to do here". A
     * quest no agent could start - a level or a job it did not have - and a hunting quest
     * nobody could finish had all three turning the ferry down every time it was offered.
     */
    private void rememberAsking(Intent intent) {
        long tick = perceiver.currentTick();
        if (intent instanceof Intent.StartQuest quest) {
            mind.infer("quest:" + quest.questId(), "asked_for", "true", tick);
        } else if (intent instanceof Intent.CompleteQuest quest) {
            mind.infer("quest:" + quest.questId(), "offered_back", "true", tick);
        }
    }

    /** Enough words that there may be something in them worth doing. */
    private static final int SAYS_SOMETHING = 160;

    /** Speeches already read, so the same words are not sent to the model twice. */
    private final Set<Integer> alreadyRead = new HashSet<>();

    /** Steps of silence after which a conversation is over. */
    private static final int QUIET_FOR = 4;

    /**
     * Once the NPC has stopped talking, asks the model what the conversation wanted done.
     *
     * Only conversations that gave the agent something - a quest, an item, or a person or
     * place to go to - are worth reading; "hello, nice weather" asks for nothing. While it is
     * being read the NPC is marked as waiting on the agent, so the reflex that hands quests in
     * does not walk up and offer it before the agent has done what was asked.
     */
    private void readWhatWasAskedOnceTheyStop() {
        if (talkingWith <= 0 || steps - lastLineAt < QUIET_FOR || pendingDialogue != null) {
            return;
        }
        int npc = talkingWith;
        List<String> lines = List.copyOf(beingTold);
        java.util.Map<Integer, Integer> received = new java.util.HashMap<>();
        java.util.Map<Integer, Integer> now = bagCounts();
        now.forEach((item, count) -> {
            int more = count - bagsWhenItBegan.getOrDefault(item, 0);
            if (more > 0) {
                received.put(item, more);
            }
        });
        List<Integer> quests = List.copyOf(questsTakenOn);
        talkingWith = -1;
        beingTold.clear();
        questsTakenOn.clear();

        boolean named = lines.stream().anyMatch(line -> !agents.mind.Instructions.read(line).isEmpty());
        // Directions come in plain words too: Robin tells a would-be warrior to go to Victoria
        // Island and find the trainer in Perion, with no tag anywhere. So anything that runs
        // to a few sentences is read - but each speech once, or an NPC who repeats himself,
        // as Robin does every time his list is answered, is read every time.
        int speech = String.join("\n", lines).hashCode();
        boolean saysSomething = String.join(" ", lines).length() >= SAYS_SOMETHING && !alreadyRead.contains(speech);
        if (instructionReader == null || instructions != null
                || (received.isEmpty() && quests.isEmpty() && !named && !saysSomething)) {
            return;
        }
        alreadyRead.add(speech);
        java.util.Map<Integer, Integer> usable = new java.util.HashMap<>();
        for (agents.percept.Item item : world.inventory().carried(agents.percept.Item.USE)) {
            usable.merge(item.itemId(), item.quantity(), Integer::sum);
        }
        Set<Integer> healing = new HashSet<>();
        for (Belief belief : mind.semantic().liveBeliefs()) {
            if (belief.predicate().equals("restores_hp") && belief.object().equals("true")
                    && belief.subject().startsWith("item:")) {
                try {
                    healing.add(Integer.parseInt(belief.subject().substring(5)));
                } catch (NumberFormatException ignored) {
                    // not an item ref after all
                }
            }
        }
        agents.mind.InstructionReader.Conversation conversation = new agents.mind.InstructionReader.Conversation(
                npc, lines, received, usable, healing, quests, world.hp(), world.maxHp());
        mind.infer("npc:" + npc, "waiting_on", "what you were told", perceiver.currentTick());
        instructionsFrom = npc;
        instructionsAskedAt = System.currentTimeMillis();
        instructions = CompletableFuture.supplyAsync(() -> instructionReader.read(conversation), reading);
    }

    /**
     * Turns what the model read into things to do, each into the form the rest of the agent
     * already acts on: somebody to find and somewhere to go are what the NPC "sends you to",
     * things to bring are what it "wants first", and things to use are done straight away.
     */
    private void takeUpWhatWasAsked() {
        if (instructions == null) {
            return;
        }
        boolean outOfPatience = System.currentTimeMillis() - instructionsAskedAt > DIALOGUE_PATIENCE_MILLIS;
        if (!instructions.isDone() && !outOfPatience) {
            return;
        }
        List<agents.mind.InstructionReader.Step> asked = List.of();
        if (instructions.isDone()) {
            try {
                asked = instructions.getNow(List.of());
            } catch (RuntimeException failed) {
                log.debug("{} could not read what npc:{} wanted", mind.name(), instructionsFrom, failed);
            }
        } else {
            instructions.cancel(true);
        }
        instructions = null;
        String who = "npc:" + instructionsFrom;
        long tick = perceiver.currentTick();
        for (agents.mind.InstructionReader.Step step : asked) {
            log.info("{} understood {} wants it to {}", mind.name(), who, step.describe());
            mind.hear(who, "asked_you_to", step.describe(), tick);
            switch (step.kind()) {
                case USE_ITEM -> requests.use(instructionsFrom, step.id(), step.count());
                case TALK_TO -> mind.hear(who, "sends_you_to", "npc:" + step.id(), tick);
                case GO_TO_MAP -> mind.hear(who, "sends_you_to", "map:" + step.id(), tick);
                case BRING_ITEM -> mind.hear(who, "wants_first", step.count() + " item:" + step.id(), tick);
                case HEAD_FOR -> mind.hear(who, "points_you_to", step.place(), tick);
            }
        }
        mind.infer(who, "waiting_on", requests.waitingOn(instructionsFrom) ? "you to use something" : "nothing",
                tick);
    }

    /** One use a step at most of whatever an NPC asked the agent to use. */
    private void doWhatWasAsked() {
        agents.mind.Requests.Step step = requests.next(world.inventory());
        step.use().ifPresent(itemId -> {
            log.info("{} uses item:{} as it was asked to", mind.name(), itemId);
            use(itemId);
        });
        for (int npc : step.finishedWith()) {
            mind.infer("npc:" + npc, "waiting_on", "nothing", perceiver.currentTick());
        }
    }

    /** Every item in the bags that are not worn, by id. */
    private java.util.Map<Integer, Integer> bagCounts() {
        java.util.Map<Integer, Integer> counts = new java.util.HashMap<>();
        for (int bag : new int[] {agents.percept.Item.EQUIP, agents.percept.Item.USE,
                agents.percept.Item.SETUP, agents.percept.Item.ETC}) {
            for (agents.percept.Item item : world.inventory().carried(bag)) {
                counts.merge(item.itemId(), item.quantity(), Integer::sum);
            }
        }
        return counts;
    }

    /**
     * Notices the job changing, and remembers who it was talking to when it did.
     *
     * That is the one who trained it, and the one to go back to when it has grown: the first
     * step of a second job is returning to them, and nothing else would think to.
     */
    private void noticeANewCalling() {
        int job = world.job();
        if (job < 0) {
            return;
        }
        if (lastJob >= 0 && job != lastJob && lastSpokeWith > 0) {
            mind.saw("self", "trained_by", "npc:" + lastSpokeWith, perceiver.currentTick());
            log.info("{} became job {}, trained by npc:{}", mind.name(), job, lastSpokeWith);
        }
        lastJob = job;
    }
    private boolean blamedThemAlready;
    private int nextToShare;

    public Agent(InWorld connection, Mind mind, Policy policy, Disposition disposition) {
        this.connection = connection;
        this.mind = mind;
        this.policy = policy;
        this.disposition = disposition;
        this.training = new agents.mind.Training(disposition, () -> mind.semantic().liveBeliefs());
        this.executor = new IntentExecutor(connection.session());
        // Seeded from the name so a given agent paces the same way run to run, and two
        // agents never pace identically.
        this.voice = new Voice(new Random(mind.name().hashCode()));
        this.touch = new Touch(new Random(mind.name().hashCode() * 31L));
        this.dialogueReader = policy.oracle().map(DialogueReader::new).orElse(null);
        this.instructionReader = policy.oracle().map(agents.mind.InstructionReader::new).orElse(null);
    }

    @Override
    public void run() {
        String name = mind.name();
        log.info("{} is awake, policy {}", name, policy.name());
        // What it was in the middle of doing for somebody did not survive the restart; nobody
        // should go on waiting for it.
        for (Belief belief : mind.semantic().liveBeliefs()) {
            if (belief.predicate().equals("waiting_on") && !belief.object().equals("nothing")) {
                mind.infer(belief.subject(), "waiting_on", "nothing", perceiver.currentTick());
            }
        }

        try {
            while (running && connection.session().isConnected()) {
                step();
                Thread.sleep(TICK.toMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            // One agent falling over should not take the run with it.
            log.error("{} stopped unexpectedly", name, e);
        } finally {
            log.info("{} is done: {} episodes, {} beliefs", name,
                    mind.episodic().size(), mind.semantic().size());
            // Leave the world rather than just stopping in it. A socket left open keeps the
            // character logged in as far as the server is concerned, and the next run is
            // refused with "already logged in" until the session times out - which makes
            // stopping and starting agents, the whole point of a daemon, not work.
            connection.session().close();
        }
    }

    private void step() {
        List<Observation> observations = perceiver.perceive(connection.inbox());
        for (Observation observation : observations) {
            world.update(observation);
            keyboard.update(observation);
            mind.take(observation);
            acknowledgeArrival(observation);
            answerNpc(observation);
            if (observation instanceof Observation.QuestStateChanged quest && quest.state() == 1
                    && talkingWith > 0) {
                questsTakenOn.add(quest.questId());
            }
            converse(observation);
            if (observation instanceof Observation.ShopOpened shop) {
                shopkeeping.opened(shop, world, mind);
                shopNpc = shop.npcId();
                mesosAtCounter = world.inventory().meso();
                stacksAtCounter = stacksCarried();
            }
        }

        if (steps++ % disposition.shareInterval() == disposition.shareInterval() - 1) {
            share();
        }

        answerWhenRead();
        readWhatWasAskedOnceTheyStop();
        takeUpWhatWasAsked();
        voice.next(System.currentTimeMillis()).ifPresent(this::say);

        feelForContact();
        survival.step(mind, world, perceiver.currentTick()).ifPresent(this::drink);
        wardrobe.step(world).ifPresent(change -> {
            log.info("{} puts on item:{}", mind.name(), change.itemId());
            connection.session().send(ClientPackets.moveItem(
                    agents.percept.Item.EQUIP, change.fromSlot(), change.toSlot(), 1));
        });
        training.step(world).ifPresent(point -> connection.session().send(point));
        recoverOverTime();
        makingRoom.step(world).ifPresent(drop -> {
            log.info("{} drops something ordinary to make room for a quest item", mind.name());
            connection.session().send(drop);
        });

        // Push the trace out to disk every step. Without this a buffered writer holds the
        // last few kilobytes indefinitely, so anything following the file live - a tail, or
        // the monitoring page - sees nothing until the run ends.
        mind.flush();

        if (world.isDead()) {
            awaitRevival();
            return;
        }
        deadFor = 0;
        doWhatWasAsked();

        // At a shop counter the agent does its business there and nothing else, one
        // transaction a step, as a player standing at one does.
        if (shopkeeping.atCounter()) {
            connection.session().send(shopkeeping.next(world, mind));
            if (!shopkeeping.atCounter()) {
                world.madeRoom();
                log.info("{} leaves the shop with {} mesos", mind.name(), world.inventory().meso());
                // Nothing sold, nothing bought: this counter has nothing for it as things stand.
                // Writing that down is what stops the walk back here every half minute - one
                // agent went to a pet-food shop for potions forty times in an hour.
                if (shopNpc > 0 && world.inventory().meso() == mesosAtCounter
                        && stacksCarried() == stacksAtCounter) {
                    mind.saw("npc:" + shopNpc, "shop_was_no_use", String.valueOf(mesosAtCounter),
                            perceiver.currentTick());
                }
            }
            return;
        }

        long tick = perceiver.currentTick();
        Policy.Decision decision = policy.decide(mind, world, tick);

        // A policy that decided for itself does not bother naming itself; one that handed
        // the decision to its fallback does, and that difference is the whole point of
        // recording it.
        mind.decided(tick, decision.goal(), decision.intent().name(),
                decision.intent().detail(), decision.consultedBeliefs(), decision.considered(),
                decision.decidedBy() != null ? decision.decidedBy() : policy.name(),
                decision.fellBackBecause(), world.selfPosition());

        executor.execute(decision.intent(), world);
        rememberAsking(decision.intent());
        noticeTheSilence(decision.intent());

        // Some maps cannot be left. Map 1020100 is an empty tutorial staging room: one
        // portal, and it is a spawn point, so there is nothing to walk through, nobody to
        // talk to and nothing to climb. An agent warped into one by an NPC wanders an empty
        // box until somebody notices. Counting the decisions spent with no way out at all
        // is how it says so; the population does the rescue, because logging back in is not
        // something an agent can do to itself.
        // No way out, and nothing here either. A second-job test is a room with no doors on
        // purpose - monsters to hunt and somebody to report to - and counting it as a trap had
        // the agent logged back out of its own test and the instructor blamed for it.
        boolean noWayOut = world.mapId() > 0 && MapGeometry.usablePortalsIn(world.mapId()).isEmpty();
        boolean nothingHere = world.visibleNpcs().isEmpty() && world.monsterCount() == 0;
        nowhereToGo = noWayOut && nothingHere ? nowhereToGo + 1 : 0;
        noticeANewCalling();
        if (nowhereToGo == 0) {
            blamedThemAlready = false;
        } else if (isTrapped() && wentAlongWith > 0 && !blamedThemAlready) {
            // Somebody offered, this agent said yes, and here it is somewhere with no way
            // out. Saying yes again next time is how a rescue becomes a loop: both agents
            // were pulled back into map 1020100 four times in ninety seconds.
            mind.infer("npc:" + wentAlongWith, "strands_you", "true", perceiver.currentTick());
            log.warn("{} blames npc:{} for stranding it in map {}", mind.name(),
                    wentAlongWith, world.mapId());
            blamedThemAlready = true;
        }
    }

    /**
     * Reports a monster walking into the agent, as its client would.
     *
     * The body notices, the server is told how hard it hit, and the mind is told what did it.
     * The health it cost arrives back from the server as a stat change like any other.
     */
    private void feelForContact() {
        touch.check(world, defence(), System.currentTimeMillis()).ifPresent(contact -> {
            connection.session().send(ClientPackets.touchedByMonster(contact.damage(),
                    contact.monsterId(), contact.objectId(), contact.facingLeft()));
            world.byObjectId(contact.objectId())
                    .ifPresent(monster -> executor.knockedBack(monster.position(), world));
            mind.take(perceiver.felt(tick -> new Observation.TouchedBy(tick,
                    contact.objectId(), contact.monsterId(), contact.damage())));
        });
    }

    /**
     * Drinks something, the way a player does: bind it to a key if it is not on one, then
     * press the key. With every potion key taken it is used straight from the bag instead,
     * which is the double-click a player falls back on.
     */
    private void drink(int itemId) {
        log.info("{} drinks item:{} at {}/{} hp", mind.name(), itemId, world.hp(), world.maxHp());
        use(itemId);
    }

    private void use(int itemId) {
        keyboard.bind(itemId).ifPresent(connection.session()::send);
        Optional<net.packet.Packet> use = keyboard.keyFor(itemId)
                .flatMap(key -> keyboard.press(key, world.inventory()))
                .or(() -> world.inventory().firstOf(itemId)
                        .map(item -> ClientPackets.useItem(item.slot(), item.itemId())));
        use.ifPresent(connection.session()::send);
    }

    /** What the agent's equipment soaks up when something hits it. */
    private int defence() {
        return world.inventory().wornDefence();
    }

    /**
     * Waits out the "you have died" window, then presses the button on it.
     *
     * A dead character can do nothing else, and the server ignores it until it asks to be
     * brought back; an agent that went on deciding things while dead would be walking a
     * corpse around, as far as anyone watching could tell, and nothing would happen.
     */
    private void awaitRevival() {
        if (deadFor++ == 0) {
            log.info("{} died in map {}", mind.name(), world.mapId());
            // First-hand, and at the level it happened: a map that killed a level-8 character
            // three times is somewhere to stay out of until it has grown, not for ever.
            if (world.mapId() > 0 && world.level() > 0) {
                mind.saw("map:" + world.mapId(), "killed_you_at_level", String.valueOf(world.level()),
                        perceiver.currentTick());
            }
        }
        if (deadFor == REVIVE_AFTER_STEPS) {
            connection.session().send(ClientPackets.revive());
        } else if (deadFor > REVIVE_AFTER_STEPS * 4) {
            deadFor = 0;        // the server did not bring it back; ask again
        }
    }

    /** A few seconds at a 600ms tick: long enough for anyone watching to see it fall. */
    private static final int REVIVE_AFTER_STEPS = 5;

    /**
     * Whether this one has taken the agent somewhere with no way out before.
     *
     * An ordinary belief, formed the same way a dud door is, and read back the same way. The
     * agent cannot read what it is being offered, but it can remember how the last such
     * offer turned out.
     */
    private boolean strandedMeBefore(int npcId) {
        String who = "npc:" + npcId;
        return mind.semantic().liveBeliefs().stream()
                .anyMatch(b -> b.subject().equals(who) && b.predicate().equals("strands_you"));
    }

    private boolean businessHereFirst(int npcId) {
        if (stayedFor.merge(npcId, 1, Integer::sum) > STAY_FOR_AT_MOST) {
            return false;   // asked enough times; whatever it was waiting for is not coming
        }
        java.util.Map<Integer, String> states = new java.util.HashMap<>();
        Set<Integer> asked = new HashSet<>();
        Set<Integer> offeredBack = new HashSet<>();
        for (Belief belief : mind.semantic().liveBeliefs()) {
            if (!belief.subject().startsWith("quest:")) {
                continue;
            }
            int quest;
            try {
                quest = Integer.parseInt(belief.subject().substring(6));
            } catch (NumberFormatException ignored) {
                continue;       // not a quest ref after all
            }
            switch (belief.predicate()) {
                case "state" -> states.put(quest, belief.object());
                case "asked_for" -> asked.add(quest);
                case "offered_back" -> offeredBack.add(quest);
                default -> { }
            }
        }
        Set<Integer> inSight = new HashSet<>();
        world.visibleNpcs().forEach(npc -> inSight.add(npc.typeId()));
        return businessHereFirst(npcId, states, asked, offeredBack, inSight);
    }

    /**
     * Something the one asking offers that was never taken, or a quest under way that somebody
     * in sight finishes. Either is a reason not to be sent anywhere yet.
     */
    static boolean businessHereFirst(int npcId, java.util.Map<Integer, String> questStates,
                                     Set<Integer> npcsInSight) {
        return businessHereFirst(npcId, questStates, Set.of(), Set.of(), npcsInSight);
    }

    /**
     * As above, leaving out what has already been tried: a quest asked for and never started
     * cannot be started yet, and one offered back and not taken is not finished yet. Neither
     * is a reason to stay.
     */
    static boolean businessHereFirst(int npcId, java.util.Map<Integer, String> questStates,
                                     Set<Integer> askedFor, Set<Integer> offeredBack,
                                     Set<Integer> npcsInSight) {
        for (int quest : agents.world.QuestBoard.offeredBy(npcId)) {
            if (!questStates.containsKey(quest) && !askedFor.contains(quest)) {
                return true;
            }
        }
        for (int npc : npcsInSight) {
            for (int quest : agents.world.QuestBoard.endedBy(npc)) {
                if ("1".equals(questStates.get(quest)) && !offeredBack.contains(quest)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * How many times one NPC's offer is turned down for this. Some offered quests can never
     * be started - a level too low, a job not had - and those would otherwise keep an agent
     * refusing the one way on for good.
     */
    private static final int STAY_FOR_AT_MOST = 4;
    private final java.util.Map<Integer, Integer> stayedFor = new java.util.HashMap<>();

    /**
     * Whether this agent is somewhere with no way out and has been for long enough to be
     * sure it is not simply mid-transition.
     *
     * The server's own answer to this map is forcedReturn, which it applies on login - so
     * the recovery is to log back in, and it is verified: an agent stuck in 1020100 came
     * back in Split Road of Destiny, exactly as that map's returnMap specifies.
     */
    public boolean isTrapped() {
        return nowhereToGo > TRAPPED_AFTER;
    }

    /** Half a minute at a 600ms tick: long past any ordinary map change. */
    private static final int TRAPPED_AFTER = 50;

    /**
     * Keeps an NPC conversation going without understanding a word of it.
     *
     * The dialogue style says what kind of answer is wanted, which is enough: say yes to
     * anything that asks, acknowledge anything that does not. An agent finds out what it
     * agreed to by watching what changes afterwards.
     */
    private void answerNpc(Observation observation) {
        if (observation instanceof Observation.ShopOpened) {
            greetedWithoutAnswer = 0;
        }
        if (!(observation instanceof Observation.DialogueShown dialogue)) {
            return;
        }
        greetedWithoutAnswer = 0;
        lastSpokeWith = dialogue.npcId();
        listen(dialogue);
        rememberWhatWasSaid(dialogue);
        // Somebody who has stranded this agent before gets a no, whoever would otherwise have
        // answered. This check used to sit on the reflex path only, so once the model was
        // reading dialogue it was asked afresh every time, said yes every time, and two agents
        // were rescued from the same rooms fifty-two times in half an hour.
        if (strandedMeBefore(dialogue.npcId())) {
            reply(dialogue.style(), NPC_NO, NO_SELECTION);
            return;
        }
        // Nor does an offer to be sent away get a yes while there is still something to do
        // here. Heena's first words in Mushroom Town are "are you done with your training? I
        // will send you out", and her quests are only offered after she has been heard; so
        // every agent that heard her said yes, and left the island's first two quests behind.
        if (isAQuestion(dialogue.style()) && businessHereFirst(dialogue.npcId())) {
            log.info("{} turns down npc:{} - there is still something to do here",
                    mind.name(), dialogue.npcId());
            reply(dialogue.style(), NPC_NO, NO_SELECTION);
            return;
        }
        // No model, or already thinking about the last thing it said: answer by reflex rather
        // than leave a conversation open with nobody attending it.
        if (dialogueReader == null || pendingDialogue != null) {
            byte answer = withoutReading(dialogue.style(), nothingKeepsMeHere(dialogue.npcId()));
            noteAnswer(dialogue.npcId(), dialogue.style(), answer);
            reply(dialogue.style(), answer, NO_SELECTION);
            return;
        }
        // How the agent is placed is worked out here, on its own thread, and handed over as
        // text. Worked out inside the reading task it walked the agent's beliefs while this
        // thread was adding to them, and the exception it threw ended the agent.
        String situation = situation(dialogue.npcId());
        pendingDialogue = new PendingDialogue((byte) dialogue.style(), dialogue.npcId(),
                System.currentTimeMillis(),
                CompletableFuture.supplyAsync(() -> dialogueReader.read(dialogue, situation),
                        reading));
    }

    /**
     * Sends the answer once there is one, or once the NPC has waited long enough.
     *
     * Checked on the tick rather than from the reading thread so that everything leaving this
     * agent leaves from one place, in the order the agent decided it.
     */
    private void answerWhenRead() {
        PendingDialogue pending = pendingDialogue;
        if (pending == null) {
            return;
        }
        boolean outOfPatience =
                System.currentTimeMillis() - pending.askedAtMillis() > DIALOGUE_PATIENCE_MILLIS;
        if (!pending.answer().isDone() && !outOfPatience) {
            return;
        }

        DialogueReader.Reply reply = DialogueReader.CONTINUE;
        if (pending.answer().isDone()) {
            reply = pending.answer().getNow(java.util.Optional.empty())
                    .orElse(DialogueReader.CONTINUE);
        } else {
            pending.answer().cancel(true);
            // Out of patience is not the same as having decided. Whatever the question was,
            // nobody read it, so it gets the same answer as if there were no model at all.
            reply = new DialogueReader.Reply(
                    withoutReading(pending.style(), nowhereLeftToGo()),
                    DialogueReader.Reply.NO_SELECTION, "nobody read it in time", null);
        }

        // An NPC that named a condition has given the agent a reason to come back. Recorded
        // as hearsay about that NPC, because being told something is not the same as knowing
        // it - and it puts the errand in the belief graph, where the agent can act on it and
        // a person can read it, rather than evaporating when the dialogue window closes.
        // Only something countable, though. The model also wrote down "class selection
        // options" and "No specific requirement stated" as conditions, and a condition
        // nobody can check is one the agent kept walking back to meet.
        if (reply.needs() != null && pending.npcId() > 0
                && agents.mind.ReflexPolicy.isConcrete(reply.needs())) {
            mind.hear("npc:" + pending.npcId(), "wants_first", reply.needs(),
                    perceiver.currentTick());
            log.info("{} was told npc:{} wants {}", mind.name(), pending.npcId(), reply.needs());
        }

        // Whoever read it, a yes to a question is a yes, and if it ends somewhere with no way
        // out this is who gets the blame. Only the reflex path used to record it, so an offer
        // the model accepted stranded the agent with nobody to hold responsible.
        noteAnswer(pending.npcId(), pending.style(), reply.action());

        log.debug("{} answers the NPC: {}", mind.name(), reply.why());
        reply(pending.style(), reply.action(), reply.selection());
        pendingDialogue = null;
    }

    /**
     * Sends an answer to an NPC, making sure it is one the conversation can end on.
     *
     * A list wants one of its options or nothing. "Next" with no option chosen is neither:
     * the script has nothing to act on, sends nothing more, and never closes - and while a
     * conversation is open the server ignores every greeting to every NPC. That is how one
     * unanswered menu at the Sleepywood Hotel was followed by 550 greetings nobody answered.
     * So "next" to a list with nothing picked becomes walking away from it.
     */
    private void reply(int style, byte action, int selection) {
        byte sent = style == MENU && action == NPC_YES_OR_NEXT && selection < 0 ? NPC_NO : action;
        connection.session().send(ClientPackets.npcTalkMore((byte) style, sent, selection));
        lastDialogueStyle = style;
    }

    /**
     * Closes whatever conversation is still open, as pressing Escape on a dialogue does.
     *
     * The last line of defence: some conversation the agent answered was left open by the
     * script anyway, and it can tell only because nobody answers it any more. Greeting three
     * people and hearing nothing from any of them is not three people ignoring it.
     */
    private void noticeTheSilence(Intent intent) {
        if (!(intent instanceof Intent.TalkTo)) {
            return;
        }
        if (++greetedWithoutAnswer < GREETINGS_BEFORE_CLOSING || lastDialogueStyle < 0) {
            return;
        }
        log.info("{} has had no answer from anyone in {} greetings; closing the last conversation",
                mind.name(), greetedWithoutAnswer);
        connection.session().send(ClientPackets.npcTalkMore((byte) lastDialogueStyle, NPC_CLOSE, NO_SELECTION));
        greetedWithoutAnswer = 0;
    }

    private static final int GREETINGS_BEFORE_CLOSING = 3;

    /** Mode -1: the dialogue window closed. Scripts dispose on it whatever they were waiting for. */
    private static final byte NPC_CLOSE = -1;

    /** sendSimple: a list of #L options. */
    private static final int MENU = 4;

    /** Action 1 means yes, or next, depending on what was asked. */
    private static final byte NPC_YES_OR_NEXT = 1;
    private static final byte NPC_NO = 0;
    private static final int NO_SELECTION = -1;

    /** sendYesNo and sendAcceptDecline: the two styles that ask rather than tell. */
    private static final int YES_OR_NO = 1;
    private static final int ACCEPT_OR_DECLINE = 0x0C;

    /**
     * What to answer when nobody read the question.
     *
     * Saying yes to everything is how an agent left Maple Island at level three. NPC 2007
     * stands a few steps from where every character starts and asks "would you like to skip
     * the tutorials and head straight to Lith Harbor?"; another warped one into an empty
     * tutorial room it could not walk out of. Both were offers, and both were accepted by
     * something with no way of reading them.
     *
     * So a question gets a no and a statement gets an acknowledgement - unless the agent has
     * nowhere left to go, which is something it can check rather than be told. An agent that
     * can reach no unopened door anywhere it knows of is finished with where it is, and an
     * offer to be taken somewhere is the only way on that remains. That is the difference
     * the reflex previously could not see between two identically-shaped questions: the
     * tutorial skip is offered on the first morning with a whole island unexplored, and the
     * ferry is worth taking once the island is done.
     *
     * It is still an answer given without reading the words. What makes it defensible is
     * that the agent refuses until its own map says refusing costs it everything.
     */
    static boolean isAQuestion(int style) {
        return style == YES_OR_NO || style == ACCEPT_OR_DECLINE;
    }

    static byte withoutReading(int style, boolean nowhereLeftToGo) {
        if (style == MENU) {
            // Choosing from a list blind could mean paying for a sauna. Walking away cannot.
            return NPC_NO;
        }
        if (!isAQuestion(style)) {
            return NPC_YES_OR_NEXT;
        }
        return nowhereLeftToGo ? NPC_YES_OR_NEXT : NPC_NO;
    }

    /**
     * Records how a question was answered, whoever answered it.
     *
     * A yes is who gets the blame if it ends somewhere with no way out; a no is counted, so
     * the same offer refused out of habit can be recognised as habit.
     */
    private void noteAnswer(int npcId, int style, byte answer) {
        if (!isAQuestion(style) || npcId <= 0) {
            return;
        }
        if (answer == NPC_YES_OR_NEXT) {
            wentAlongWith = npcId;
            refusals.accepted(npcId);
        } else {
            refusals.declined(npcId, perceiver.currentTick(), mind.lastNoveltyTick());
        }
    }

    /**
     * Whether there is any reason left to turn this one's offer down: nowhere left to walk
     * to, or turning it down has become a habit that is teaching the agent nothing.
     */
    private boolean nothingKeepsMeHere(int npcId) {
        return nowhereLeftToGo() || refusals.outgrown(npcId, mind.lastNoveltyTick());
    }

    /**
     * Whether the agent can still reach anywhere it has not opened.
     *
     * Read from its own beliefs through the same graph that plans its journeys, so "nowhere
     * left to go" means here exactly what it means everywhere else: no route, along doors it
     * has walked, to a door it has seen and never opened.
     */
    private boolean nowhereLeftToGo() {
        if (world.mapId() <= 0) {
            return false;       // it does not know where it is yet, so it knows nothing
        }
        if (somebodyHereIsStillAStranger()) {
            // Not yet. Accepting a lift while somebody in this room has never been spoken to
            // is how both agents kept being carried out of Southperry - the one map holding
            // the person who sells passage off the island - by whoever offered first. What
            // you are looking for might be them.
            return false;
        }
        return KnownWorld.rememberedBy(mind.semantic().liveBeliefs())
                .routeToNearestFrontier(KnownWorld.mapRef(world.mapId()))
                .isEmpty();
    }

    /**
     * How the agent is placed, in the only terms it has: what it is, what it carries, and
     * whether anywhere is left.
     *
     * Handed to the model with every NPC's words, because without it every offer of passage
     * came back DECLINE - correctly, given a prompt that says to accept only if going there
     * is what the agent wanted, and nothing anywhere saying what it wanted. The same
     * condition the reflex uses to decide the same question, so the two cannot drift.
     */
    private String situation(int npcId) {
        StringBuilder placed = new StringBuilder("you are level ").append(world.level());
        mesosHeld().ifPresent(mesos -> placed.append(", carrying ").append(mesos).append(" mesos"));
        if (refusals.outgrown(npcId, mind.lastNoveltyTick())) {
            // Said in so many words, because "somewhere you have not been" was true on Maple
            // Island forever and the model, told it, declined the ferry every time it was asked.
            placed.append(". You have turned this one's offer down ").append(refusals.timesDeclined(npcId))
                    .append(" times, and you have found out nothing new since the first time:"
                            + " staying where you are has stopped teaching you anything, and this"
                            + " offer is the way on.");
        } else {
            placed.append(nowhereLeftToGo()
                    ? ". You have opened every door you know of and there is nowhere left to "
                      + "walk to that you have not already seen."
                    : ". There is still somewhere you have not been, or somebody here you have "
                      + "not spoken to.");
        }
        // Whether it has a calling yet, and what sort of character it is. Without these an
        // offer to become something read like any other offer with no way back, and the
        // prompt says to turn those down - so a character could meet the person who would
        // train it and walk away.
        if (world.job() == 0) {
            placed.append(" You have not taken up any calling or way of life yet.");
        } else if (world.job() > 0) {
            placed.append(" You have already taken up a calling; you cannot take up another.");
        }
        placed.append(" By temperament you are someone who ").append(temperament()).append('.');
        // Where it has been told to go, in the words it was told. Without this, an offer of
        // passage to the very place Robin pointed it at read like any other offer.
        for (Belief belief : mind.semantic().liveBeliefs()) {
            if (belief.predicate().equals("points_you_to")) {
                placed.append(" You were told to head for ").append(belief.object()).append('.');
            }
        }
        // What it is still carrying for somebody, in the same markup the NPC used, so the
        // reader can match "collect 30 #t4031013#" against how many it has - and not take the
        // option to leave a test it is halfway through.
        for (Belief belief : mind.semantic().liveBeliefs()) {
            if (!belief.predicate().equals("wants_first")) {
                continue;
            }
            java.util.regex.Matcher item = ITEM_ASKED.matcher(belief.object());
            if (item.find()) {
                int itemId = Integer.parseInt(item.group(2));
                placed.append(" You were asked for ").append(item.group(1)).append(" #t").append(itemId)
                        .append("# and you have ").append(world.inventory().count(itemId)).append('.');
            }
        }
        return placed.toString();
    }

    private static final java.util.regex.Pattern ITEM_ASKED =
            java.util.regex.Pattern.compile("(\\d+) item:(\\d+)");

    /** The disposition in words a reader of an NPC's offer can weigh it against. */
    private String temperament() {
        List<String> traits = new java.util.ArrayList<>();
        if (disposition.aggression() >= 0.6) {
            traits.add("likes a straight fight up close");
        }
        if (disposition.wanderlust() >= 0.6) {
            traits.add("keeps moving and likes to see far");
        }
        if (disposition.greed() >= 0.6) {
            traits.add("likes to gather things and slip about quietly");
        }
        if (disposition.curiosity() >= 0.6 || disposition.sociability() >= 0.8) {
            traits.add("likes to learn and to talk");
        }
        return traits.isEmpty() ? "takes things as they come" : String.join(" and ", traits);
    }

    /** What the server last told this agent about its own purse. */
    private java.util.Optional<String> mesosHeld() {
        return mind.semantic().liveBeliefs().stream()
                .filter(b -> b.subject().equals("self") && b.predicate().equals("meso"))
                .map(Belief::object)
                .findFirst();
    }

    /** Whether anyone on screen here has never been heard from first-hand. */
    private boolean somebodyHereIsStillAStranger() {
        Set<String> met = new HashSet<>();
        for (Belief belief : mind.semantic().liveBeliefs()) {
            if (belief.predicate().equals("talks_in")
                    && belief.provenance() == Belief.Provenance.FIRST_HAND) {
                met.add(belief.subject());
            }
        }
        return world.visibleNpcs().stream()
                .anyMatch(npc -> !met.contains("npc:" + npc.typeId()));
    }

    /**
     * Answers questions put to it, and picks up claims other agents make.
     *
     * A question can arrive privately, or in map chat addressed by name - "Agent1: why" -
     * so a person can talk to one agent without whispering.
     */
    private void converse(Observation observation) {
        String text;
        String replyTo;

        if (observation instanceof Observation.WhisperHeard whisper) {
            // A claim is a claim whichever way it arrives, and whispering one is how a
            // person tells an agent something without shouting it at the whole map.
            Optional<Claim> told = Claim.parse(whisper.text());
            if (told.isPresent()) {
                adopt(told.get());
                voice.reply("noted, though I have not seen that myself",
                        whisper.speakerName(), System.currentTimeMillis());
                return;
            }
            text = whisper.text();
            replyTo = whisper.speakerName();
        } else if (observation instanceof Observation.ChatHeard chat) {
            if (chat.speakerId() == world.characterId()) {
                return;     // our own voice coming back off the map
            }
            Claim.parse(chat.text()).ifPresent(this::adopt);
            text = addressedToMe(chat.text());
            replyTo = null;
            if (text == null) {
                return;
            }
        } else {
            return;
        }

        Optional<String> answer = Conversation.answer(text, mind, world);
        answer.ifPresent(reply -> voice.reply(reply, replyTo, System.currentTimeMillis()));
    }

    /** "Agent1: why" is for Agent1. Returns the question, or null if it was not for us. */
    private String addressedToMe(String text) {
        String prefix = mind.name() + ":";
        return text.regionMatches(true, 0, prefix, 0, prefix.length())
                ? text.substring(prefix.length()).trim()
                : null;
    }

    /**
     * Takes another agent's word for something - at hearsay confidence, grounded in the
     * episode of hearing it, so the replay shows it as second-hand until the agent sees it
     * for itself.
     */
    private void adopt(Claim claim) {
        // Never take your own word for something. The speaker check in converse() catches the
        // map-chat echo, but only once the agent knows its own character id - and the first
        // position announcements go out in the seconds before the server has said who we are,
        // so they came back and were filed as hearsay about ourselves. An agent then held a
        // stale belief that it was somewhere it had since left, and treated it as a reason to
        // walk back and look for the company of itself. Checking the subject holds whenever
        // the claim arrives, which is the part the speaker check cannot promise.
        if (claim.subject().equals("player:" + world.characterId())) {
            return;
        }
        mind.hear(claim.subject(), claim.predicate(), claim.object(), perceiver.currentTick());
    }

    /**
     * Says something it is sure of, in a form another agent can pick up.
     *
     * Rotates through what it knows rather than repeating its single best fact. Always
     * announcing the same thing spreads one belief and nothing else, which is not how
     * knowledge gets around.
     */
    private void share() {
        List<Belief> worthSaying = mind.semantic().liveBeliefs().stream()
                .filter(b -> b.provenance() == Belief.Provenance.FIRST_HAND)
                .filter(b -> !b.subject().equals("self"))
                // "player:4 said ..." is a fact about a speaker, not about the world, and
                // announcing one wraps a claim inside a claim - which then gets announced
                // again. A run produced "player:6 said !know player:4 said !know ..." before
                // this filter existed.
                .filter(b -> !b.predicate().equals("said"))
                .sorted(Comparator.comparingDouble(Belief::confidence).reversed())
                .toList();
        // Every other turn, say where you are instead of what you know - and say it even when
        // there is nothing else worth saying, which is the case for a freshly reset agent that
        // most needs to be found. Two agents that never mention their own whereabouts can only
        // ever meet by accident. It goes out as the speaker's player id rather than as "self",
        // because a listener can do nothing with somebody else's "self".
        boolean sayWhereIAm = nextToShare++ % 2 == 1
                && world.characterId() > 0 && world.mapId() > 0;
        if (!sayWhereIAm && worthSaying.isEmpty()) {
            return;
        }
        String claim = sayWhereIAm
                ? Claim.announce("player:" + world.characterId(), "in_map", "map:" + world.mapId())
                : Claim.announce(worthSaying.get((nextToShare / 2) % worthSaying.size()));

        long now = System.currentTimeMillis();
        voice.announce(claim, now);

        // Map chat only carries as far as the map, and agents that have diverged are by
        // definition somewhere else. Anyone it has met is reachable by name wherever they
        // are, which is how news actually travels between people who have split up. These
        // queue behind the announcement rather than going out with it, so telling four
        // people takes four beats, as it would if you were doing the telling.
        acquaintances().forEach(name -> voice.tell(claim, name, now));
    }

    /** Names of players it has seen, from its own beliefs. */
    private List<String> acquaintances() {
        return mind.semantic().liveBeliefs().stream()
                .filter(b -> b.predicate().equals("named") && b.subject().startsWith("player:"))
                .map(Belief::object)
                .distinct()
                .toList();
    }

    /**
     * Confirms we have finished loading whatever map we were sent to. Without this the
     * server leaves the character flagged mid-transition and refuses every later map change.
     */
    private void acknowledgeArrival(Observation observation) {
        if (observation instanceof Observation.MapEntered
                || observation instanceof Observation.SelfDescribed) {
            connection.session().send(ClientPackets.mapTransitionComplete());
        }
    }

    /** Whispers to one player, or says it to the whole map. */
    private void say(Voice.Utterance utterance) {
        connection.session().send(utterance.whisperTo() == null
                ? ClientPackets.chat(utterance.text(), false)
                : ClientPackets.whisper(utterance.whisperTo(), utterance.text()));
    }

    /**
     * Carries the clock on from a restored mind, before the loop starts.
     *
     * Called by whoever restored the mind rather than by the agent itself: an agent has no
     * business knowing it has been asleep.
     */
    public void resumeAt(long previousTick) {
        perceiver.resumeFrom(previousTick);
    }

    public long tick() {
        return perceiver.currentTick();
    }

    public Disposition disposition() {
        return disposition;
    }

    public void stop() {
        running = false;
    }

    /**
     * What is actually deciding for this agent.
     *
     * Reported per agent rather than taken from what was requested, because those came
     * apart once: a start with policy=local built every agent on the reflex, and the status
     * endpoint went on echoing "local" for an hour while nothing asked the model anything.
     */
    public String policyName() {
        return policy.name();
    }

    public Mind mind() {
        return mind;
    }

    public WorldModel world() {
        return world;
    }

    public Perceiver perceiver() {
        return perceiver;
    }
}
