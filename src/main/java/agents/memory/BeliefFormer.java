package agents.memory;

import agents.memory.Belief.Provenance;
import agents.percept.Observation;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns an episode into the beliefs it states outright, and nothing more.
 *
 * The line this class holds is the important part. It restates - "the packet said that
 * player 3 is called Agent1, so believe that player 3 is called Agent1" - and it never
 * infers. It will not conclude that a monster is dangerous, that an NPC sells something, or
 * that exp is worth having. Those are the conclusions the whole project exists to watch an
 * agent reach, and a rule quietly supplying them here would hollow that out while leaving
 * the demo looking better.
 *
 * Inference belongs to the policy, which arrives with the LLM.
 */
public class BeliefFormer {

    /**
     * The one piece of state kept here: which map the agent is currently in, so sightings can
     * be located. Derived entirely from observations, like everything else.
     */
    private int currentMap = -1;

    /**
     * The agent's own character id, so it does not record itself as a source. Map chat comes
     * back off the server including your own messages, and "player:2 said ..." about yourself
     * is noise that also makes an agent look like it learned something from someone else.
     */
    private int selfCharacterId = -1;

    public List<Triple> beliefsFrom(Episode episode) {
        List<Triple> triples = new ArrayList<>();
        Observation observation = episode.observation();

        switch (observation) {
            case Observation.SelfDescribed self -> {
                currentMap = self.mapId();
                selfCharacterId = self.characterId();
                triples.add(Triple.firstHand("self", "named", self.name()));
                triples.add(Triple.firstHand("self", "level", String.valueOf(self.level())));
                triples.add(Triple.firstHand("self", "job", String.valueOf(self.job())));
                triples.add(Triple.firstHand("self", "in_map", mapRef(self.mapId())));
            }
            case Observation.MapEntered entered -> {
                currentMap = entered.mapId();
                triples.add(Triple.firstHand("self", "in_map", mapRef(entered.mapId())));
            }
            case Observation.StatsChanged changed -> changed.stats().forEach((stat, value) ->
                    triples.add(Triple.firstHand("self", stat.toLowerCase(), String.valueOf(value))));
            case Observation.PlayerAppeared player -> {
                String ref = "player:" + player.characterId();
                triples.add(Triple.firstHand(ref, "named", player.name()));
                triples.add(Triple.firstHand(ref, "level", String.valueOf(player.level())));
                triples.add(Triple.firstHand(ref, "in_map", mapRef(currentMap)));
            }
            case Observation.NpcAppeared npc ->
                    triples.add(Triple.firstHand("npc:" + npc.npcId(), "present_in", mapRef(currentMap)));
            case Observation.MonsterAppeared monster ->
                    triples.add(Triple.firstHand("monster:" + monster.monsterId(), "present_in", mapRef(currentMap)));
            case Observation.DropAppeared drop ->
                    triples.add(Triple.firstHand(itemRef(drop), "dropped_in", mapRef(currentMap)));
            case Observation.DialogueShown dialogue ->
                    triples.add(Triple.firstHand("npc:" + dialogue.npcId(), "talks_in", mapRef(currentMap)));
            case Observation.QuestStateChanged quest ->
                    triples.add(Triple.firstHand("quest:" + quest.questId(), "state",
                            String.valueOf(quest.state())));
            case Observation.ChatHeard chat -> {
                if (chat.speakerId() != selfCharacterId) {
                    triples.add(Triple.firstHand("player:" + chat.speakerId(), "said", chat.text()));
                }
            }

            // Deliberately produce nothing:
            //  - ThingMoved changes many times a second, so a position is working state for a
            //    world model rather than something to hold a long-term belief about.
            //  - MonsterDied and PlayerLeft say an episode happened, not that anything is
            //    lastingly true; the episode itself is the record.
            //  - NoticeShown and Unrecognised carry nothing an agent can state as a fact yet.
            default -> {
            }
        }
        return triples;
    }

    private static String mapRef(int mapId) {
        return mapId < 0 ? "map:unknown" : "map:" + mapId;
    }

    private static String itemRef(Observation.DropAppeared drop) {
        return drop.meso() ? "meso" : "item:" + drop.itemId();
    }

    /** A triple waiting to be asserted, with the confidence class it should carry. */
    public record Triple(String subject, String predicate, String object, Provenance provenance) {
        static Triple firstHand(String subject, String predicate, String object) {
            return new Triple(subject, predicate, object, Provenance.FIRST_HAND);
        }
    }
}
