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

    /** When it last answered somebody, and how long after that the next line may still come. */
    private int lastRepliedAt = -1000;
    private static final int STILL_TALKING_FOR = 10;

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
    private int acceptedAtStep = Integer.MIN_VALUE / 2;
    /** Where it was when it said yes, so being moved by the yes can be told from staying. */
    private int mapWhenAccepted = -1;

    /** The map this agent was last in, so arriving somewhere new can be noticed. */
    private int lastMap = -1;

    /** Steps after saying yes within which a map change is that NPC's doing. */
    private static final int TAKEN_WITHIN = 5;

    /**
     * Until when it is being carried somewhere, in steps: a ride paid for, waited out in a
     * room with no way out on foot - the deck before the boat to Orbis leaves - and every map
     * it is carried through after.
     */
    private int inTransitUntil = Integer.MIN_VALUE / 2;

    /** Fifteen minutes: the boat to Orbis leaves every quarter of an hour and takes ten. */
    private static final int TRANSIT_PATIENCE = 1500;

    /** When it last walked into a door, and the map it was last carried from. */
    private int lastPortalAt = Integer.MIN_VALUE / 2;
    private int lastMapCarried = -1;

    /** Steps after walking into a door within which a map change is the door's doing. */
    private static final int DOOR_WITHIN = 10;

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
    private int lastSpokeAt = Integer.MIN_VALUE / 2;
    private static final int GIVEN_WITHIN = 10;
    private int lastJob = -1;

    /**
     * Writes down who, where and what an NPC's words named.
     *
     * Hearsay about that NPC, because it is being told: "npc:1022000 sends_you_to map:102020300",
     * "npc:1072004 wants_first 30 item:4031013". The policy acts on these the way it acts on
     * any other belief, and a person reading the trace can see where each errand came from.
     */
    /**
     * Whether a line offers passage somewhere: Joel's "the ship ride to Orbis Station", Cherry's
     * "do you want to go to Orbis?", "travel in this boat", a ticket for a train.
     */
    /** Whether this NPC is known to offer passage, seen or told. */
    private boolean offersPassage(int npcId) {
        String who = "npc:" + npcId;
        return mind.semantic().liveBeliefs().stream()
                .anyMatch(b -> b.subject().equals(who) && b.predicate().equals(agents.mind.ReflexPolicy.OFFERS_PASSAGE));
    }

    static boolean offersPassage(String said) {
        return PASSAGE.matcher(said).find();
    }

    private static final java.util.regex.Pattern PASSAGE = java.util.regex.Pattern.compile(
            "(?i)\\b(ship|boat|ferry|train|subway|ticket|voyage|sail)s?\\b|travel(?:l?ing)? (?:in|on|to)\\b|do you want to go to");

    private void rememberWhatWasSaid(Observation.DialogueShown dialogue) {
        // "Come back at level ten" is the one condition it can always read for itself, and the
        // model summarised it as "Warrior class requirement" - which nothing can check.
        if (dialogue.npcId() > 0) {
            comeBackAt(dialogue.text()).ifPresent(condition ->
                    mind.hear("npc:" + dialogue.npcId(), "wants_first", condition, perceiver.currentTick()));
        }
        // Somebody offering to take it somewhere, in so many words: a ship, a boat, a ticket.
        if (dialogue.npcId() > 0 && offersPassage(dialogue.text())) {
            mind.saw("npc:" + dialogue.npcId(), agents.mind.ReflexPolicy.OFFERS_PASSAGE, "true", perceiver.currentTick());
        }
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
            if (talkingWith > 0) {
                mind.infer("npc:" + talkingWith, "waiting_on", "nothing", perceiver.currentTick());
            }
            talkingWith = dialogue.npcId();
            beingTold.clear();
            questsTakenOn.clear();
            bagsWhenItBegan = bagCounts();
            // Not to be handed anything back until it has finished saying what it wants.
            mind.infer("npc:" + talkingWith, "waiting_on", "what you are being told", perceiver.currentTick());
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

    /**
     * Steps of silence after which a conversation is over.
     *
     * Four was too few: a scripted NPC waits for each answer, and an answer the model is
     * reading takes several seconds. Roger's conversation came apart into three, and the one
     * saying "double click to consume" began after the apple had arrived - so it was read as
     * handing over nothing, and the apple was never eaten.
     */
    private static final int QUIET_FOR = 20;

    /** Steps before the same NPC's plain talk is worth reading again. */
    private static final int READ_AGAIN_AFTER = 1000;

    /** When each NPC's plain talk was last read. */
    private final java.util.Map<Integer, Integer> lastReadAt = new java.util.HashMap<>();

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
        // And not the same NPC again straight away: Robin answers every option on his list with
        // a different speech, and was read a hundred and twenty times in half an hour.
        boolean saysSomething = String.join(" ", lines).length() >= SAYS_SOMETHING && !alreadyRead.contains(speech)
                && steps - lastReadAt.getOrDefault(npc, Integer.MIN_VALUE / 2) >= READ_AGAIN_AFTER;
        if (instructionReader == null || instructions != null
                || (received.isEmpty() && quests.isEmpty() && !named && !saysSomething)) {
            mind.infer("npc:" + npc, "waiting_on", "nothing", perceiver.currentTick());
            return;
        }
        alreadyRead.add(speech);
        lastReadAt.put(npc, steps);
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
        this.executor.actingFor(mind.name());
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
        rereadOfferedCallings();
        forgiveDoorsThatWorked();
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
        // Whatever somebody told it from outside the world since the last step, heard on its
        // own thread like a whisper would be.
        for (Claim told; (told = toldFromOutside.poll()) != null; ) {
            adopt(told);
        }
        List<Observation> observations = perceiver.perceive(connection.inbox());
        for (Observation observation : observations) {
            world.update(observation);
            keyboard.update(observation);
            mind.take(observation);
            acknowledgeArrival(observation);
            answerNpc(observation);
            // And picking up anything from the etc pocket: a job test's monsters give no
            // experience, only the marbles that pass it, and Agent3 was logged out of the
            // Magician's test for making no progress while it collected them.
            if (observation instanceof Observation.ExpGained
                    || observation instanceof Observation.QuestStateChanged
                    || observation instanceof Observation.ItemGained picked
                       && picked.quantity() > 0 && picked.itemId() / 1_000_000 == 4) {
                lastProgressAt = steps;
                rethought = false;
            }
            if (observation instanceof Observation.QuestStateChanged quest && quest.state() == 1
                    && talkingWith > 0) {
                questsTakenOn.add(quest.questId());
            }
            converse(observation);
            // Something handed over in a conversation is remembered as given by whoever it was:
            // a trainer's letter is for taking to whoever the trainer named, for as long as it
            // is carried, however many times the way there goes wrong.
            noticePaidPassage(observation);
            noticeToldToWait(observation);
            if (observation instanceof Observation.ItemGained gained && gained.quantity() > 0
                    && gained.itemId() / 1_000_000 == 4 && lastSpokeWith > 0
                    && steps - lastSpokeAt <= GIVEN_WITHIN) {
                mind.saw("item:" + gained.itemId(), "given_by", "npc:" + lastSpokeWith, perceiver.currentTick());
            }
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
        keepBuffsUp();
        watchForStalling();
        escapeIfWalledIn();

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

        if (crossingASideRoom()) {
            return;
        }
        long tick = perceiver.currentTick();
        noticeBeingCarried();
        policy.inTransit(inTransit());
        Policy.Decision decision = policy.decide(mind, world, tick);
        if (policy.readAReturnScroll() && world.inventory().count(agents.mind.Shopkeeping.RETURN_SCROLL) > 0) {
            log.info("{} reads a return scroll: its return town is the quicker way to where it is going", mind.name());
            use(agents.mind.Shopkeeping.RETURN_SCROLL);
            policy.readTheScroll();
            return;
        }

        // A policy that decided for itself does not bother naming itself; one that handed
        // the decision to its fallback does, and that difference is the whole point of
        // recording it.
        mind.decided(tick, decision.goal(), decision.intent().name(),
                decision.intent().detail(), decision.consultedBeliefs(), decision.considered(),
                decision.decidedBy() != null ? decision.decidedBy() : policy.name(),
                decision.fellBackBecause(), world.selfPosition());

        // Not out of the room while somebody is still talking. Dances asks "are you sure?" a
        // moment after the calling is picked from his list, and Agent1 had already walked out
        // of his door by then, four times over, so the question was never answered and
        // Fighter never became its job.
        if (decision.intent() instanceof Intent.EnterPortal && steps - lastRepliedAt < STILL_TALKING_FOR) {
            return;
        }
        if (decision.intent() instanceof Intent.TalkTo) {
            greetedAt = steps;
        }
        executor.execute(decision.intent(), world);
        if (decision.intent() instanceof Intent.EnterPortal) {
            lastPortalAt = steps;
        }
        rememberAsking(decision.intent());
        noticeTheSilence(decision.intent());
        // After acting rather than before, so a greeting never follows a close in the same step.
        closeWhatWentQuiet();

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
        // Not while a ride it paid for is under way: the deck before the boat leaves has no
        // door and nobody on it, and an agent waiting there was logged back in - which the
        // server answers with the station it had just left.
        nowhereToGo = noWayOut && nothingHere && !inTransit() ? nowhereToGo + 1 : 0;
        noticeANewCalling();
        noticeWhereOffersGo();
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
        buffsLastUntil.clear();     // dying takes every buff with it
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

    /**
     * Casts whatever buff it has learned that has worn off, one at a time, as a player keeps
     * Magic Guard or Iron Body up. Not mid-conversation, and not more often than every few
     * steps, so the server sees each land.
     */
    private void keepBuffsUp() {
        if (pendingDialogue != null || steps - lastCastAt < STEPS_BETWEEN_CASTS) {
            return;
        }
        long now = System.currentTimeMillis();
        agents.mind.Fighting.due(agents.mind.Fighting.buffs(world), buffsLastUntil, now).ifPresent(buff -> {
            log.info("{} casts skill:{} (level {})", mind.name(), buff.skillId(), buff.level());
            connection.session().send(ClientPackets.castOnSelf(buff.skillId(), buff.level()));
            buffsLastUntil.put(buff.skillId(), now + buff.seconds() * 1000L - RECAST_EARLY_MILLIS);
            lastCastAt = steps;
        });
    }

    private final java.util.Map<Integer, Long> buffsLastUntil = new java.util.HashMap<>();
    private int lastCastAt = Integer.MIN_VALUE / 2;
    private static final int STEPS_BETWEEN_CASTS = 5;
    private static final long RECAST_EARLY_MILLIS = 3000;

    private static final java.util.regex.Pattern CALLING = java.util.regex.Pattern.compile(
            "(?i)(?:be(?:come)? an?|be the)\\s*(?:#[a-z])*\\s*(warrior|bowman|archer|magician|mage|thief|rogue|pirate)\\b");

    /** The calling an NPC is offering, if it is offering one: "want to be a #bMagician#k?" */
    static Optional<String> callingOffered(String said) {
        java.util.regex.Matcher calling = CALLING.matcher(said);
        if (!calling.find()) {
            return Optional.empty();
        }
        // Naming several is telling, not offering: Maple Island's guide says "you can become a
        // Bowman in Henesys, a Magician in Ellinia, a Warrior in Perion...", and three agents
        // took it for an offer of the first, cut it off, and wrote it down as offering Bowman.
        long named = CALLING_NAME.matcher(said).results()
                .map(name -> callingNamed(name.group())).distinct().count();
        if (named > 1) {
            return Optional.empty();
        }
        // And an offer is put to you: "want to be", "wanna be", "decided to become".
        // Explaining is not offering - "for example, to be a warrior, your STR has to be over
        // 35" is a Maple Island helper's advice, and a talker cut it off as a Warrior's offer.
        int start = Math.max(0, lastSentenceEnd(said, calling.start()));
        int end = said.length();
        for (int i = calling.end(); i < said.length(); i++) {
            if (".!?".indexOf(said.charAt(i)) >= 0) {
                end = i + 1;
                break;
            }
        }
        String sentence = said.substring(start, end);
        boolean putToYou = OFFERING.matcher(sentence).find();
        // Pointing you to somebody else is advice: "You want to become a Warrior? Hmmm, then I
        // suggest you head over to Perion" is a Maple Island helper's answer to a question.
        boolean sendsYouOn = ADVICE.matcher(said).find();
        return putToYou && !sendsYouOn ? Optional.of(callingNamed(calling.group(1))) : Optional.empty();
    }

    private static final java.util.regex.Pattern ADVICE = java.util.regex.Pattern.compile(
            "(?i)\\b(suggest|head over|head to|you should go|go and see|look for)\\b");

    private static int lastSentenceEnd(String said, int before) {
        for (int i = before - 1; i >= 0; i--) {
            if (".!?".indexOf(said.charAt(i)) >= 0) {
                return i + 1;
            }
        }
        return 0;
    }

    private static final java.util.regex.Pattern OFFERING = java.util.regex.Pattern.compile(
            "(?i)\\b(want|wanna|decided|would you|will you|ready to|like to)\\b");

    private static final java.util.regex.Pattern CALLING_NAME = java.util.regex.Pattern.compile(
            "(?i)(warrior|bowman|archer|magician|mage\\b|thief|rogue|pirate)");

    /**
     * A calling by the name the statues give it. The Dark Lord asks "Wanna be the Rogue?",
     * and a forager with the DEX for it turned him down for not having been asked about
     * thieving at all.
     */
    static String callingNamed(String said) {
        return switch (said.toLowerCase()) {
            case "rogue" -> "thief";
            case "archer" -> "bowman";
            case "mage" -> "magician";
            default -> said.toLowerCase();
        };
    }

    /**
     * Reads again, with today's reading, the words each "offers a calling" belief rests on,
     * and lets go of any that no longer say so.
     *
     * The reading has been wrong before: Maple Island's guide telling of every calling was
     * taken as offering Bowman, and a helper's "to be a warrior, your STR has to be over 35"
     * as offering Warrior. Fixing the reader stops new mistakes; this undoes the old ones
     * before they are passed down. A belief with no words of its own to go back to - one
     * inherited, say - is kept.
     */
    private void rereadOfferedCallings() {
        long now = perceiver.currentTick();
        for (Belief belief : mind.semantic().liveBeliefs()) {
            if (!belief.predicate().equals("offers_calling")) {
                continue;
            }
            List<String> said = new java.util.ArrayList<>();
            for (long episodeId : belief.supportedBy()) {
                mind.episodic().byId(episodeId).flatMap(episode -> spokenIn(episode.observation()))
                        .ifPresent(said::add);
            }
            if (!said.isEmpty() && said.stream().noneMatch(line -> callingOffered(line).isPresent())) {
                mind.semantic().retract(belief.id(), now);
                log.info("{} no longer believes {} offers {}: \"{}\" was not an offer", mind.name(),
                        belief.subject(), belief.object(), said.get(0).length() > 60 ? said.get(0).substring(0, 60) + "..." : said.get(0));
            }
        }
    }

    /**
     * Lets go of "leads nowhere" for any door it has also seen take it somewhere, so the door
     * is given another try.
     *
     * A door tried while somebody is still talking only works once they stop, and the Magic
     * Library's way out was written off as going nowhere on the very decision it had worked.
     * Nowhere then outweighed the walk through it - it was the later verdict - so the library
     * was a dead end, and a Magician with its instructor's letter wandered in it for hours.
     * A door that really does nothing is found out again in a dozen decisions.
     */
    private void forgiveDoorsThatWorked() {
        long now = perceiver.currentTick();
        Set<String> worked = new HashSet<>();
        for (Belief belief : mind.semantic().liveBeliefs()) {
            if (belief.predicate().equals("leads_to") && belief.object().startsWith("map:")
                    && belief.provenance() != Belief.Provenance.HEARSAY) {
                worked.add(belief.subject());
            }
        }
        for (Belief belief : mind.semantic().liveBeliefs()) {
            if (belief.predicate().equals("leads_to") && belief.object().equals(KnownWorld.NOWHERE)
                    && worked.contains(belief.subject())) {
                mind.semantic().retract(belief.id(), now);
                log.info("{} gives {} another try: it has gone somewhere before", mind.name(), belief.subject());
            }
        }
    }

    private static final java.util.regex.Pattern RECALLED_LINE = java.util.regex.Pattern.compile(
            "text=(.*), style=-?\\d+\\]?$", java.util.regex.Pattern.DOTALL);

    /** What an NPC said in an episode, whether seen this run or recalled from a saved mind. */
    static Optional<String> spokenIn(agents.percept.Observation observation) {
        if (observation instanceof agents.percept.Observation.DialogueShown dialogue) {
            return Optional.of(dialogue.text());
        }
        if (observation instanceof agents.percept.Observation.Recalled recalled
                && recalled.type().equals("DialogueShown")) {
            java.util.regex.Matcher line = RECALLED_LINE.matcher(recalled.detail());
            return line.find() ? Optional.of(line.group(1)) : Optional.empty();
        }
        return Optional.empty();
    }

    /** "Train yourself further until you reach #blevel 10, DEX 25#k": a condition, in so many words. */
    private static final java.util.regex.Pattern COME_BACK_AT = java.util.regex.Pattern.compile(
            "(?i)(?:reach|at least|be at|until)\\s*(?:#[a-z])*\\s*(level \\d+(?:\\s*,\\s*(?:STR|DEX|INT|LUK) \\d+)*)");

    static Optional<String> comeBackAt(String said) {
        java.util.regex.Matcher at = COME_BACK_AT.matcher(said);
        return at.find() ? Optional.of(at.group(1)) : Optional.empty();
    }

    /**
     * Notices an agent that has stopped getting anywhere, and does something about it.
     *
     * Every trap so far - a dead door believed live, a way back struck off, a quest offered
     * back for ever, a room walled in by killing grounds - was found by somebody looking. They
     * all looked the same from outside: no experience and no quests, for an hour or more. So
     * that is what is watched. First the policy drops its short-term plans and give-ups, which
     * is usually where it is stuck; if that does not help, the population logs it back in.
     */
    private void watchForStalling() {
        int stalled = steps - lastProgressAt;
        if (stalled >= RETHINK_AFTER && !rethought) {
            log.warn("{} has made no progress in {} minutes in map {} - rethinking", mind.name(),
                    stalled * TICK.toMillis() / 60_000, world.mapId());
            policy.rethink();
            rethought = true;
        }
    }

    /**
     * Reads a return scroll when no door in the map can be walked to, and has not been for a
     * while. A Magician dropped from the top floor of the Nautilus' Generator Room to the bottom,
     * where no ladder reaches back up, and wandered there for nine hours. The scroll takes it to
     * the map's own return town, which is where the game means somebody stuck there to go.
     */
    private void escapeIfWalledIn() {
        if (steps % WALL_CHECK_EVERY != 0 || world.mapId() <= 0) {
            return;
        }
        int map = world.mapId();
        Set<String> reachable = agents.world.Doorways.from(map, world.selfPosition());
        boolean noDoorReached = !agents.world.MapGeometry.usablePortalsIn(map).isEmpty()
                && !agents.world.MapGeometry.groundIn(map).isEmpty()
                && reachable.isEmpty();
        // Or every door it can reach is one it has found goes nowhere, and nothing has come of
        // staying. The Nautilus' cow stable has one way out, a scripted door that will not open
        // until the milk jug is full; Agent1 walked into it 489 times, wrote it off, and spent
        // the rest of the day there with a return scroll in its bag.
        List<Belief> beliefs = mind.semantic().liveBeliefs();
        boolean deadEnded = steps - lastProgressAt >= RETHINK_AFTER
                && everyDoorGoesNowhere(map, reachable, beliefs);
        boolean walledIn = noDoorReached || deadEnded;
        walledInFor = walledIn && map == walledInMap ? walledInFor + 1 : walledIn ? 1 : 0;
        walledInMap = map;
        if (walledInFor < WALLED_IN_CHECKS) {
            return;
        }
        // A door that will not open is usually a quest keeping it shut: the stable's lets
        // anybody out who is not in the middle of fetching milk. Giving up whatever was under
        // way here opens it, so its doors are given another try - and the quest is not taken
        // up again until the agent has grown, or it would only be sent back in.
        List<Integer> underWayHere = deadEnded ? questsUnderWayIn(map, beliefs) : List.of();
        if (!underWayHere.isEmpty()) {
            long now = perceiver.currentTick();
            for (int questId : underWayHere) {
                log.info("{} gives up quest {}: it has walled it in, in map {}", mind.name(), questId, map);
                connection.session().send(ClientPackets.questAction(QUEST_FORFEIT, questId, 0));
                mind.infer("quest:" + questId, "walled_you_in_at_level", String.valueOf(world.level()), now);
            }
            for (Belief belief : beliefs) {
                if (belief.predicate().equals("leads_to") && belief.object().equals(KnownWorld.NOWHERE)
                        && belief.subject().startsWith(KnownWorld.portalRef(map, ""))) {
                    mind.semantic().retract(belief.id(), now);
                }
            }
            walledInFor = 0;
        } else if (world.inventory().count(agents.mind.Shopkeeping.RETURN_SCROLL) > 0) {
            log.info("{} reads a return scroll: {} in map {}", mind.name(),
                    noDoorReached ? "no door can be walked to" : "every door it can reach goes nowhere", map);
            use(agents.mind.Shopkeeping.RETURN_SCROLL);
            walledInFor = 0;
        }
    }

    /** Whether there are doors within reach and every one of them is known to go nowhere. */
    static boolean everyDoorGoesNowhere(int map, Set<String> reachable, List<Belief> beliefs) {
        if (reachable.isEmpty()) {
            return false;
        }
        KnownWorld known = KnownWorld.rememberedBy(beliefs);
        return reachable.stream().allMatch(door -> known.destinationOf(KnownWorld.portalRef(map, door))
                .filter(KnownWorld.NOWHERE::equals).isPresent());
    }

    /**
     * Quests started and not finished whose state last changed since the agent came into this
     * map. When it came in is when it first believed it was here: that belief outlives a
     * restart, where a count kept in a field would start again inside the trap.
     */
    static List<Integer> questsUnderWayIn(int map, List<Belief> beliefs) {
        String here = KnownWorld.mapRef(map);
        Optional<Long> cameIn = beliefs.stream()
                .filter(b -> b.subject().equals("self") && b.predicate().equals("in_map") && b.object().equals(here))
                .map(Belief::firstSeen)
                .findFirst();
        if (cameIn.isEmpty()) {
            return List.of();
        }
        List<Integer> quests = new java.util.ArrayList<>();
        for (Belief belief : beliefs) {
            if (belief.predicate().equals("state") && belief.subject().startsWith("quest:")
                    && belief.object().equals("1") && belief.lastSeen() >= cameIn.get()) {
                try {
                    quests.add(Integer.parseInt(belief.subject().substring("quest:".length())));
                } catch (NumberFormatException notAQuest) {
                    // not one the server numbered
                }
            }
        }
        return quests;
    }

    private static final int WALL_CHECK_EVERY = 50;
    private static final int WALLED_IN_CHECKS = 12;
    /** QuestActionHandler's action 3. */
    private static final int QUEST_FORFEIT = 3;
    private int walledInFor;
    private int walledInMap;

    /** Whether it has gone so long without progress that it should be logged back in. */
    public boolean isStalled() {
        return steps - lastProgressAt >= RELOG_AFTER;
    }

    /** About twenty and forty minutes of steps. */
    private static final int RETHINK_AFTER = 2000;
    private static final int RELOG_AFTER = 4000;
    private int lastProgressAt;
    private boolean rethought;

    /** Whether this NPC's offer is known to go to a map known to have nothing to do. */
    private boolean leadsNowhereWorthGoing(int npcId) {
        return leadsNowhereWorthGoing(npcId, mind.semantic().liveBeliefs());
    }

    static boolean leadsNowhereWorthGoing(int npcId, List<Belief> beliefs) {
        String who = "npc:" + npcId;
        Set<String> goesTo = new HashSet<>();
        Set<String> pointless = new HashSet<>();
        Set<String> carriesOn = new HashSet<>();
        for (Belief belief : beliefs) {
            if (belief.predicate().equals("takes_you_to") && belief.subject().equals(who)) {
                goesTo.add(belief.object());
            } else if (belief.predicate().equals("nothing_to_do")) {
                pointless.add(belief.subject());
            } else if (belief.predicate().equals(KnownWorld.CARRIES_YOU_TO)) {
                carriesOn.add(belief.subject());
            }
        }
        // The deck before the boat leaves has nothing on it, and is the way to Orbis.
        pointless.removeAll(carriesOn);
        return !goesTo.isEmpty() && pointless.containsAll(goesTo);
    }

    /** Nothing to hunt here and nowhere new to walk to: any way out is a good one. */
    private boolean strandedNow() {
        return world.monsterCount() == 0 && nowhereLeftToGo();
    }

    /**
     * Notices arriving somewhere, and if it was an NPC's doing, writes down where their offer
     * goes. That is the only way to know what an offer is worth before taking it again.
     */
    private void noticeWhereOffersGo() {
        int map = world.mapId();
        if (map <= 0 || map == lastMap) {
            return;
        }
        if (lastMap > 0 && wentAlongWith > 0 && steps - acceptedAtStep <= TAKEN_WITHIN) {
            mind.saw("npc:" + wentAlongWith, "takes_you_to", "map:" + map, perceiver.currentTick());
            // And from where: the same NPC can stand in several places and take you somewhere
            // different from each, which is what lets a route use the ride.
            mind.saw(KnownWorld.rideRef(lastMap, wentAlongWith), "ride_to", "map:" + map, perceiver.currentTick());
            // Somebody offering a calling who then took it somewhere has sent it on to whoever
            // gives it: the Warrior's statue's part is done once it has sent a fighter to Perion,
            // and still holding it as the errand had the fighter take the cab straight back.
            String guide = "npc:" + wentAlongWith;
            boolean offersACalling = mind.semantic().liveBeliefs().stream()
                    .anyMatch(b -> b.subject().equals(guide) && b.predicate().equals("offers_calling"));
            if (offersACalling && world.job() == 0) {
                mind.infer(guide, "sent_you_on", "map:" + map, perceiver.currentTick());
            }
        }
        lastMap = map;
    }

    private boolean businessHereFirst(int npcId) {
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
        // Finishing a quest is staying paying off, so the count of refusals starts again.
        int done = (int) states.values().stream().filter("2"::equals).count();
        if (done != questsDoneWhenRefusing) {
            stayedFor.clear();
            questsDoneWhenRefusing = done;
        }
        if (stayedFor.merge(npcId, 1, Integer::sum) > STAY_FOR_AT_MOST) {
            return false;   // refused enough times with nothing finished; it is not coming
        }
        Set<Integer> inSight = new HashSet<>();
        world.visibleNpcs().forEach(npc -> inSight.add(npc.typeId()));
        return businessHereFirst(npcId, states, asked, offeredBack, inSight, world.level(), world.job())
                || questToFinishOnFoot(states);
    }

    /**
     * A quest under way whose finisher it has seen somewhere it can walk to from here. Being
     * offered passage away from them is not the moment to go: the whole new generation stood
     * at the Maple Island ferry with Roger's apple, Nina's brother and Pio's recycling open,
     * their finishers a few maps away, and only a quest somebody in sight finished held them.
     */
    private boolean questToFinishOnFoot(java.util.Map<Integer, String> states) {
        String here = KnownWorld.mapRef(world.mapId());
        java.util.Map<String, KnownWorld.Hop> reach = null;
        for (java.util.Map.Entry<Integer, String> quest : states.entrySet()) {
            if (!"1".equals(quest.getValue())) {
                continue;
            }
            int ender = agents.world.QuestBoard.enderOf(quest.getKey());
            if (ender < 0) {
                continue;
            }
            String npc = "npc:" + ender;
            Optional<String> map = mind.semantic().liveBeliefs().stream()
                    .filter(b -> b.subject().equals(npc)
                            && (b.predicate().equals("present_in") || b.predicate().equals("talks_in")))
                    .map(Belief::object)
                    .findFirst();
            if (map.isEmpty()) {
                continue;
            }
            if (map.get().equals(here)) {
                return true;
            }
            if (reach == null) {
                reach = KnownWorld.rememberedBy(mind.semantic().liveBeliefs()).reachableFrom(here);
            }
            if (reach.containsKey(map.get())) {
                return true;
            }
        }
        return false;
    }

    private int questsDoneWhenRefusing = -1;

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
        return businessHereFirst(npcId, questStates, askedFor, offeredBack, npcsInSight, -1, -1);
    }

    /** As above, counting only quests a character of this level and job could start; a job of -1 counts them all. */
    static boolean businessHereFirst(int npcId, java.util.Map<Integer, String> questStates,
                                     Set<Integer> askedFor, Set<Integer> offeredBack,
                                     Set<Integer> npcsInSight, int levelNow, int jobNow) {
        for (int quest : agents.world.QuestBoard.offeredBy(npcId)) {
            if (!questStates.containsKey(quest) && !askedFor.contains(quest)
                    && (jobNow < 0 || levelNow <= 0 || agents.world.QuestBoard.canStart(quest, levelNow, jobNow, questStates::get))) {
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
     * How many times one NPC's offer is turned down for this with no quest finished in
     * between. Some quests can never be finished - a level too low, a job not had, things
     * nobody drops - and those would otherwise keep an agent refusing the one way on for good.
     */
    private static final int STAY_FOR_AT_MOST = 4;

    /** The NPC offering it a calling that suits it, and when, to see the conversation through. */
    private int callingTalkWith = -1;
    private int callingTalkAt = Integer.MIN_VALUE / 2;
    private static final int CALLING_TALK = 60;
    private final java.util.Map<Integer, Integer> stayedFor = new java.util.HashMap<>();

    /**
     * Whether this agent is somewhere with no way out and has been for long enough to be
     * sure it is not simply mid-transition.
     *
     * The server's own answer to this map is forcedReturn, which it applies on login - so
     * the recovery is to log back in, and it is verified: an agent stuck in 1020100 came
     * back in Split Road of Destiny, exactly as that map's returnMap specifies.
     */
    /**
     * Notices a ride being paid for: something taken out of its bag just after saying yes to
     * somebody who then moved it. Cherry at Ellinia Station asks "Do you want to go to Orbis?",
     * moves the agent to the deck and takes the ticket; she never says what she wanted, so
     * this is the only way to know the ticket was the price. What she took is written down as
     * what she wants first, and the ride is under way.
     */
    private void noticePaidPassage(Observation observation) {
        if (!(observation instanceof Observation.ItemGained taken) || taken.quantity() >= 0
                || wentAlongWith <= 0 || steps - acceptedAtStep > TAKEN_WITHIN
                || world.mapId() == mapWhenAccepted) {
            return;
        }
        mind.saw("npc:" + wentAlongWith, "wants_first", -taken.quantity() + " item:" + taken.itemId(),
                perceiver.currentTick());
        inTransitUntil = steps + TRANSIT_PATIENCE;
        lastMapCarried = world.mapId();
        log.info("{} paid npc:{} item:{} for passage to map {}", mind.name(), wentAlongWith,
                taken.itemId(), world.mapId());
    }

    /**
     * Notices being moved while in transit without walking into a door or saying yes to
     * anybody - carried, as the boat carries everyone on its deck when it leaves - and writes
     * down where from and where to, which is the only way such a crossing gets onto its map
     * of the world.
     */
    private void noticeBeingCarried() {
        int map = world.mapId();
        if (map <= 0 || map == lastMapCarried) {
            return;
        }
        int from = lastMapCarried;
        lastMapCarried = map;
        if (!inTransit() || from <= 0) {
            return;
        }
        boolean walked = steps - lastPortalAt <= DOOR_WITHIN;
        boolean taken = steps - acceptedAtStep <= TAKEN_WITHIN;
        if (walked || taken) {
            return;
        }
        mind.saw(KnownWorld.mapRef(from), KnownWorld.CARRIES_YOU_TO, KnownWorld.mapRef(map), perceiver.currentTick());
        inTransitUntil = steps + TRANSIT_PATIENCE;
        log.info("{} was carried from map {} to map {}", mind.name(), from, map);
    }

    /**
     * Inside a side room on the way across its map, leaves by the door the way was planned
     * through. Left to the policy, the room's doors all lead back to the same map and the
     * first of them - the one it came in by - would do as well as any, which is back down to
     * the foot of the tree.
     */
    private boolean crossingASideRoom() {
        Optional<agents.world.MapGeometry.RoomHop> through = executor.throughRoom();
        if (through.isEmpty()) {
            return false;
        }
        agents.world.MapGeometry.RoomHop hop = through.get();
        if (world.mapId() != hop.room()) {
            // Out of it again, or never let in: either way the room is behind it.
            if (stepsInSideRoom > 0
                    || System.currentTimeMillis() - executor.throughRoomAtMillis() > SIDE_ROOM_ENTRY_MILLIS) {
                executor.leftTheRoom();
                stepsInSideRoom = 0;
            }
            return false;
        }
        if (++stepsInSideRoom > SIDE_ROOM_PATIENCE) {
            log.info("{} gives up crossing side room {} for door {}", mind.name(), hop.room(), hop.roomExit());
            // And plans its way across without this room for a while, or it is straight back in.
            agents.world.Navigator.blockRoom(hop, ROOM_SET_ASIDE_MILLIS);
            executor.leftTheRoom();
            stepsInSideRoom = 0;
            return false;
        }
        Optional<java.awt.Point> door = MapGeometry.portalsIn(hop.room()).stream()
                .filter(p -> p.name().equals(hop.roomExit()))
                .map(MapGeometry.PortalSighting::position).findFirst();
        if (door.isEmpty()) {
            executor.leftTheRoom();
            return false;
        }
        executor.execute(new Intent.EnterPortal(hop.roomExit(), door.get()), world);
        return true;
    }

    private int stepsInSideRoom;

    /** A minute and a half to cross a side room, and ten seconds to be let into one. */
    private static final int SIDE_ROOM_PATIENCE = 150;
    private static final long SIDE_ROOM_ENTRY_MILLIS = 10_000;

    /** How long a side room it failed to cross is left out of its ways across. */
    private static final long ROOM_SET_ASIDE_MILLIS = 30 * 60_000;

    /** Whether a ride it paid for is still under way. */
    boolean inTransit() {
        return steps < inTransitUntil;
    }

    /** "Already travelling, please be patient for the next one": a wait, not a no. */
    private static final java.util.regex.Pattern WAIT_FOR_THE_NEXT = java.util.regex.Pattern.compile(
            "(?i)be patient|the next one|already travel|ready to take off");

    private void noticeToldToWait(Observation observation) {
        if (observation instanceof Observation.DialogueShown said && said.npcId() > 0
                && WAIT_FOR_THE_NEXT.matcher(said.text()).find()) {
            policy.toldToWait(said.npcId());
        }
    }

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
        lastSpokeAt = steps;
        shownStyle = dialogue.style();
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
        // Which calling somebody offers is what lets the next errand be to the right one - noted
        // before anything answers, since a ride taken from a statue answered first and the
        // Thief's statue was never written down as offering Thief.
        Optional<String> callingHere = callingOffered(dialogue.text());
        if (callingHere.isPresent() && world.job() == 0) {
            mind.saw("npc:" + dialogue.npcId(), "offers_calling", callingHere.get(), perceiver.currentTick());
        }
        // The second job is seen through the same way: with a first job and the level for the
        // next, its trainer, whoever the trainer sends it to, and anybody in a test map get a
        // yes to every question, and from a list the calling it is cut out for - or "I'll
        // choose my occupation" to get to that list. A list was otherwise walked away from, so
        // no agent could ever have picked Fighter, Page or Spearman.
        if (secondJobTalk(dialogue.npcId())) {
            if (dialogue.style() == MENU) {
                int pick = optionForSecondJob(dialogue.text(), secondJobBranch(world.job(),
                        agents.mind.Fighting.weaponKind(world), disposition));
                // Out of anything to heal with and badly hurt in the test, the way out is taken.
                if (pick < 0 && outOfHealing()) {
                    pick = optionLeaving(dialogue.text());
                }
                if (pick >= 0) {
                    log.info("{} picks option {} from npc:{} on the way to its second job", mind.name(), pick, dialogue.npcId());
                    reply(MENU, NPC_YES_OR_NEXT, pick);
                    return;
                }
            } else {
                noteAnswer(dialogue.npcId(), dialogue.style(), NPC_YES_OR_NEXT);
                reply(dialogue.style(), NPC_YES_OR_NEXT, NO_SELECTION);
                return;
            }
        }
        // Somewhere off limits, a way out is a yes whatever else is on offer; an offer going
        // somewhere off limits is a no whatever the policy wants.
        String hereRef = KnownWorld.mapRef(world.mapId());
        if (isAQuestion(dialogue.style())) {
            if (KnownWorld.isOffLimits(hereRef) && LEAVING.matcher(dialogue.text()).find()) {
                log.info("{} takes npc:{} up on a way out of map {}", mind.name(), dialogue.npcId(), world.mapId());
                noteAnswer(dialogue.npcId(), dialogue.style(), NPC_YES_OR_NEXT);
                reply(dialogue.style(), NPC_YES_OR_NEXT, NO_SELECTION);
                return;
            }
            if (leadsOffLimits(dialogue.npcId(), dialogue.text())) {
                log.info("{} turns down npc:{} - it goes somewhere off limits", mind.name(), dialogue.npcId());
                reply(dialogue.style(), NPC_NO, NO_SELECTION);
                return;
            }
        }
        // A ride the policy has decided to take: yes, and from a list the option that goes.
        // Spinel's way back from Zipangu is a list - "Yes, I'm done with traveling. Can I go
        // back?" or "No, I'd like to continue exploring" - and a list is otherwise walked away
        // from, so nobody ever came back from a world tour.
        // Not a ride that is a calling it does not want: the Thief's statue offers its ride as
        // "I can send you to the hideout if you want to be a Thief", and a wanderer cut out
        // to be a Bowman rode it, then turned the Dark Lord down when it got there.
        boolean unwantedCalling = callingHere.isPresent() && world.job() == 0
                && !disposition.callingsThatSuit().contains(callingHere.get())
                && world.level() < agents.mind.Disposition.ANY_CALLING_AFTER;
        // Buying the fare for a ride it means to take: the ticket seller asks "are you sure?"
        if (dialogue.npcId() == policy.fetchingFrom() && isAQuestion(dialogue.style())) {
            noteAnswer(dialogue.npcId(), dialogue.style(), NPC_YES_OR_NEXT);
            log.info("{} buys what the ride costs from npc:{}", mind.name(), dialogue.npcId());
            reply(dialogue.style(), NPC_YES_OR_NEXT, NO_SELECTION);
            return;
        }
        if (dialogue.npcId() == policy.rideWanted() && !unwantedCalling) {
            if (dialogue.style() == MENU) {
                int going = optionThatGoes(dialogue.text(), policy.rideTo());
                if (going >= 0) {
                    wentAlongWith = dialogue.npcId();
                    acceptedAtStep = steps;
                    mapWhenAccepted = world.mapId();
                    log.info("{} asks npc:{} for the ride (option {})", mind.name(), dialogue.npcId(), going);
                    reply(MENU, NPC_YES_OR_NEXT, going);
                    return;
                }
            } else if (isAQuestion(dialogue.style())) {
                noteAnswer(dialogue.npcId(), dialogue.style(), NPC_YES_OR_NEXT);
                log.info("{} takes npc:{} up on the ride", mind.name(), dialogue.npcId());
                reply(dialogue.style(), NPC_YES_OR_NEXT, NO_SELECTION);
                return;
            }
        }
        // Nor a calling that does not suit it. The job statues in Lith Harbor send a character
        // straight to an instructor, and a fighter one level short of Warrior took the
        // Magician's statue to Ellinia and turned the Magician down when it got there.
        // Not only a question: Athena Pierce makes a Bowman of whoever clicks Next through "So you
        // decided to become a bowman?" and "It is an important and final choice", and a fighter
        // did. So whatever kind of line offers the wrong calling, the conversation is closed.
        Optional<String> offered = callingOffered(dialogue.text());
        if (world.job() == 0 && offered.isPresent()
                && !disposition.callingsThatSuit().contains(offered.get())
                && world.level() < agents.mind.Disposition.ANY_CALLING_AFTER) {
            log.info("{} turns down npc:{} - a {} is not for it", mind.name(), dialogue.npcId(), offered.get());
            reply(dialogue.style(), isAQuestion(dialogue.style()) ? NPC_NO : NPC_CLOSE, NO_SELECTION);
            return;
        }
        // And a calling that suits it is seen through by the agent itself, not left to a model
        // that might think the moment wrong: yes when asked, and on through what follows. The
        // Warrior's and Bowman's instructors never ask - "Do you want to become a warrior?" is a
        // statement, then "It is an important and final choice", then the job - and closing any
        // of those lines is turning the calling down.
        if (world.job() == 0 && offered.isPresent()
                && (disposition.callingsThatSuit().contains(offered.get())
                    || world.level() >= agents.mind.Disposition.ANY_CALLING_AFTER)) {
            callingTalkWith = dialogue.npcId();
            callingTalkAt = steps;
            log.info("{} says yes to npc:{} - a {} is what it wants to be", mind.name(), dialogue.npcId(), offered.get());
        }
        if (world.job() == 0 && dialogue.npcId() == callingTalkWith && steps - callingTalkAt < CALLING_TALK
                && dialogue.style() != MENU) {
            noteAnswer(dialogue.npcId(), dialogue.style(), NPC_YES_OR_NEXT);
            reply(dialogue.style(), NPC_YES_OR_NEXT, NO_SELECTION);
            return;
        }
        // Nor somebody whose offer goes somewhere with nothing to do - unless that is no worse
        // than here. Rooney in Sleepywood offers HappyVille: nothing to hunt and no way out
        // but asking, and three agents took him up on it over and over for eight hours,
        // getting out each time and going straight back.
        // Stalled on foot, or told to go and take passage, an offer from somebody known to offer
        // passage gets a yes before anything here can hold it back. Joel's "are you sure you
        // want to purchase?" was turned down - "there is still something to do here" - by
        // Agent1 and Agent2, sent to Ellinia Station to take the boat, every time they asked.
        if (isAQuestion(dialogue.style()) && policy.levellingStalled() && offersPassage(dialogue.npcId())) {
            noteAnswer(dialogue.npcId(), dialogue.style(), NPC_YES_OR_NEXT);
            log.info("{} takes npc:{} up on passage", mind.name(), dialogue.npcId());
            reply(dialogue.style(), NPC_YES_OR_NEXT, NO_SELECTION);
            return;
        }
        if (isAQuestion(dialogue.style()) && leadsNowhereWorthGoing(dialogue.npcId()) && !strandedNow()) {
            log.info("{} turns down npc:{} - it goes somewhere with nothing to do", mind.name(), dialogue.npcId());
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
        lastRepliedAt = steps;
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
        closedAt = steps;
        greetedWithoutAnswer = 0;
    }

    private static final int GREETINGS_BEFORE_CLOSING = 3;

    /**
     * Closes a conversation once nobody has said anything in it for a few steps, as a player
     * presses Escape on a window left hanging.
     *
     * Waiting for three greetings to go unanswered before closing was too slow. In the Nautilus'
     * stable the calves leave the conversation open when there is no milk to spill, and the
     * greetings that went unheard were nearly always the ones to the second cow, the only one
     * that would have filled the jug: eighty-five visits to the first cow and nine to the
     * second. Closing a conversation the script has already ended does nothing; closing one it
     * has not lets the next greeting be heard - so long as it comes half a second later, since
     * the server ignores a greeting that soon after a conversation ends. Not while the model is
     * still reading what was said, and not just after greeting somebody, whose answer is on
     * its way.
     */
    private void closeWhatWentQuiet() {
        if (shownStyle < 0 || shownStyle == GET_TEXT || pendingDialogue != null || lastLineAt <= closedAt) {
            return;
        }
        if (steps - lastLineAt < GONE_QUIET || steps - lastRepliedAt < GONE_QUIET
                || steps - greetedAt < GONE_QUIET) {
            return;
        }
        connection.session().send(ClientPackets.npcTalkMore((byte) shownStyle, NPC_CLOSE, NO_SELECTION));
        closedAt = steps;
    }

    /** The style of the last line shown, and when a conversation was last closed. */
    private int shownStyle = -1;
    private int closedAt = -1;
    private int greetedAt = -1000;
    private static final int GONE_QUIET = 5;

    /**
     * sendGetText. Closing one is read as an answer with a string after it, which this does
     * not send, so it is left to the greetings' own count.
     */
    private static final int GET_TEXT = 2;

    /** Mode -1: the dialogue window closed. Scripts dispose on it whatever they were waiting for. */
    private static final byte NPC_CLOSE = -1;

    private static final java.util.regex.Pattern OPTION = java.util.regex.Pattern.compile(
            "#L(\\d+)#(.*?)(?=#l|#L\\d+#|\\r|\\n|$)", java.util.regex.Pattern.DOTALL);
    private static final java.util.regex.Pattern GOING = java.util.regex.Pattern.compile(
            "(?i)\\b(yes|take me|go back|go to|let'?s go|i'?ll go)\\b");

    private static final java.util.regex.Pattern LEAVING = java.util.regex.Pattern.compile(
            "(?i)\\b(get out|leave|go back|return|take you back)\\b");
    private static final java.util.regex.Pattern OFF_LIMITS_NAMED = java.util.regex.Pattern.compile(
            "(?i)happyville|#m2090\\d{5}#");

    /** Whether an offer goes somewhere off limits, by where this NPC is known to take people or by what it says. */
    private boolean leadsOffLimits(int npcId, String said) {
        if (OFF_LIMITS_NAMED.matcher(said).find() && !KnownWorld.isOffLimits(KnownWorld.mapRef(world.mapId()))) {
            return true;
        }
        String npc = "npc:" + npcId;
        java.util.Set<String> goes = new java.util.HashSet<>();
        for (Belief belief : mind.semantic().liveBeliefs()) {
            if (belief.subject().equals(npc) && belief.predicate().equals("takes_you_to")) {
                goes.add(belief.object());
            }
        }
        return !goes.isEmpty() && goes.stream().allMatch(KnownWorld::isOffLimits);
    }

    /** Whether this conversation is part of going for the second job. */
    private boolean secondJobTalk(int npcId) {
        int job = world.job();
        if (job <= 0 || job % 100 != 0 || world.level() < SECOND_JOB_AT) {
            return false;
        }
        int map = world.mapId();
        if (map >= TEST_MAPS_FROM && map <= TEST_MAPS_TO) {
            return true;        // the hidden maps the instructors send people to for the test
        }
        String trainer = null;
        for (Belief belief : mind.semantic().liveBeliefs()) {
            if (belief.subject().equals("self") && belief.predicate().equals("trained_by")) {
                trainer = belief.object();
            }
        }
        if (trainer == null) {
            return false;
        }
        String npc = "npc:" + npcId;
        if (npc.equals(trainer)) {
            return true;
        }
        for (Belief belief : mind.semantic().liveBeliefs()) {
            if (belief.subject().equals(trainer) && belief.predicate().equals("sends_you_to")
                    && belief.object().equals(npc)) {
                return true;    // the instructor the trainer sent it to, letter in hand
            }
        }
        return false;
    }

    private static final int SECOND_JOB_AT = 30;
    private static final int TEST_MAPS_FROM = 108000000;
    private static final int TEST_MAPS_TO = 108000999;

    /** Badly hurt with nothing it knows heals left to drink. */
    private boolean outOfHealing() {
        if (world.hp() < 0 || world.maxHp() <= 0 || world.hp() >= world.maxHp() * 0.4) {
            return false;
        }
        java.util.Set<String> healers = new java.util.HashSet<>();
        for (Belief belief : mind.semantic().liveBeliefs()) {
            if (belief.predicate().equals("restores_hp") && belief.object().equals("true")) {
                healers.add(belief.subject());
            }
        }
        return world.inventory().carried(agents.percept.Item.USE).stream()
                .noneMatch(item -> healers.contains("item:" + item.itemId()));
    }

    static int optionLeaving(String said) {
        java.util.regex.Matcher option = OPTION.matcher(said);
        while (option.find()) {
            if (option.group(2).toLowerCase().contains("leave")) {
                return Integer.parseInt(option.group(1));
            }
        }
        return -1;
    }

    /** Which second job to take: by the weapon it already fights with, or for a Magician by temperament. */
    static String secondJobBranch(int job, int weaponKind, agents.mind.Disposition disposition) {
        return switch (job) {
            case 100 -> weaponKind == 43 || weaponKind == 44 ? "Spearman"
                    : weaponKind == 32 || weaponKind == 42 ? "Page" : "Fighter";
            case 200 -> disposition.sociability() >= 0.8 ? "Cleric"
                    : disposition.aggression() >= 0.6 ? "Wizard (Fire / Poison)" : "Wizard (Ice / Lighting)";
            case 300 -> weaponKind == 46 ? "Crossbowman" : "Hunter";
            case 400 -> weaponKind == 47 ? "Assassin" : "Bandit";
            case 500 -> weaponKind == 49 ? "Gunslinger" : "Brawler";
            default -> "";
        };
    }

    /** The option naming the branch, else the one that gets to choosing; never one that only explains. */
    static int optionForSecondJob(String said, String branch) {
        java.util.regex.Matcher option = OPTION.matcher(said);
        int choose = -1;
        while (option.find()) {
            String text = option.group(2).replaceAll("#[a-z]", "").trim();
            if (text.toLowerCase().contains("explain")) {
                continue;
            }
            if (!branch.isEmpty() && text.equalsIgnoreCase(branch)) {
                return Integer.parseInt(option.group(1));
            }
            if (choose < 0 && text.toLowerCase().contains("choose")) {
                choose = Integer.parseInt(option.group(1));
            }
        }
        return choose;
    }

    /** The option in a list that says yes to going, or -1. */
    static int optionThatGoes(String said) {
        return optionThatGoes(said, null);
    }

    /**
     * The option in a list naming where it wants to go - a taxi's list is towns, each as its
     * map - or failing that the one that says yes to going; -1 if none does.
     */
    static int optionThatGoes(String said, String toMap) {
        if (toMap != null && toMap.startsWith("map:")) {
            String named = "#m" + toMap.substring(4) + "#";
            java.util.regex.Matcher option = OPTION.matcher(said);
            while (option.find()) {
                if (option.group(2).contains(named)) {
                    return Integer.parseInt(option.group(1));
                }
            }
        }
        java.util.regex.Matcher option = OPTION.matcher(said);
        while (option.find()) {
            if (GOING.matcher(option.group(2)).find()) {
                return Integer.parseInt(option.group(1));
            }
        }
        return -1;
    }

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
            acceptedAtStep = steps;
            mapWhenAccepted = world.mapId();
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
        return nowhereLeftToGo() || refusals.outgrown(npcId, mind.lastNoveltyTick())
                // Nor when hunting anything it can walk to will take a day a level: the ticket
                // seller at Ellinia Station asks "are you sure you want to purchase?", and every
                // agent in its forties said no, for days.
                || policy.levellingStalled();
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
                .routeToNearestUnopenedDoor(KnownWorld.mapRef(world.mapId()))
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
        if (policy.levellingStalled()) {
            placed.append(" Nothing you can hunt within walking distance will see you to your next"
                    + " level in good time: being taken somewhere new is how you will grow.");
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
    /**
     * Something a person tells it from outside the world - what whispering it "!know s p o"
     * would do, for when there is nobody in the world to whisper. Heard as hearsay on the
     * agent's next step, never written into its mind from another thread.
     */
    public void tell(Claim claim) {
        toldFromOutside.add(claim);
    }

    private final java.util.concurrent.ConcurrentLinkedQueue<Claim> toldFromOutside =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

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
