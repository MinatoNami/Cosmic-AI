package agents.control;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.EnvironmentVariables;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Puts the agents' characters back to nothing.
 *
 * Deleting the character rather than editing it down to level 1 is what gives a genuinely
 * clean slate: the server recreates the character on the next login, in the starting map,
 * with no items, no quests and no skills. Editing would leave a level 1 character holding
 * whatever a level 6 had accumulated.
 *
 * The owned-row list is copied from {@code Character.deleteCharFromDB}, which is the
 * server's own answer to what belongs to a character. The schema declares no cascades, so
 * anything not on that list stays behind as an orphan - the equipment sweep at the end is
 * there because {@code inventoryequipment} hangs off {@code inventoryitems} rather than off
 * the character, and would otherwise survive both.
 *
 * Plain JDBC rather than the server's pooled {@code DatabaseConnection}, because that reads
 * config.yaml and this process should not need the game server's configuration file to clear
 * three rows.
 */
public class CharacterReset {
    private static final Logger log = LoggerFactory.getLogger(CharacterReset.class);

    /** Straight out of Character.deleteCharFromDB. */
    private static final List<String> OWNED_BY_CHARACTER = List.of(
            "famelog", "inventoryitems", "keymap", "queststatus", "savedlocations",
            "trocklocations", "skillmacros", "skills", "eventstats");

    private final String url;
    private final String user;
    private final String password;
    private final String namePattern;

    public CharacterReset(String url, String user, String password, String namePattern) {
        this.url = url;
        this.user = user;
        this.password = password;
        this.namePattern = namePattern;
    }

    /** Reads the same DB settings the server does, so one .env configures both. */
    public static CharacterReset fromEnvironment() {
        String host = setting("DB_HOST", "localhost");
        String port = setting("DB_PORT", "3306");
        String name = setting("DB_NAME", "cosmic");
        return new CharacterReset(
                "jdbc:mysql://" + host + ":" + port + "/" + name
                        + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                setting("DB_USER", "root"),
                setting("DB_PASS", ""),
                "Agent%");
    }

    /**
     * @param note what a person should know about what just happened, empty when nothing
     */
    public record Result(int characters, int ownedRows, String note) {
    }

    public Result wipe() {
        try (Connection db = DriverManager.getConnection(url, user, password)) {
            db.setAutoCommit(false);
            List<Integer> ids = agentCharacterIds(db);
            if (ids.isEmpty()) {
                return new Result(0, 0, "no agent characters were in the database");
            }

            int rows = 0;
            for (int id : ids) {
                for (String table : OWNED_BY_CHARACTER) {
                    rows += deleteBy(db, "DELETE FROM `" + table + "` WHERE characterid = ?", id);
                }
                rows += deleteBy(db, "DELETE FROM `mts_cart` WHERE cid = ?", id);
            }

            int characters = 0;
            for (int id : ids) {
                characters += deleteBy(db, "DELETE FROM `characters` WHERE id = ?", id);
            }

            // inventoryequipment hangs off the item, not the character, so it outlives both.
            try (PreparedStatement sweep = db.prepareStatement(
                    "DELETE ie FROM `inventoryequipment` ie "
                            + "LEFT JOIN `inventoryitems` ii ON ie.inventoryitemid = ii.inventoryitemid "
                            + "WHERE ii.inventoryitemid IS NULL")) {
                rows += sweep.executeUpdate();
            }

            db.commit();
            log.info("Reset {} agent character(s), clearing {} owned row(s)", characters, rows);
            return new Result(characters, rows, "");
        } catch (SQLException e) {
            throw new IllegalStateException("Could not reset characters: " + e.getMessage(), e);
        }
    }

    private List<Integer> agentCharacterIds(Connection db) throws SQLException {
        List<Integer> ids = new ArrayList<>();
        try (PreparedStatement query = db.prepareStatement(
                "SELECT id FROM `characters` WHERE name LIKE ?")) {
            query.setString(1, namePattern);
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    ids.add(rows.getInt("id"));
                }
            }
        }
        return ids;
    }

    private static int deleteBy(Connection db, String sql, int id) throws SQLException {
        try (PreparedStatement statement = db.prepareStatement(sql)) {
            statement.setInt(1, id);
            return statement.executeUpdate();
        }
    }

    private static String setting(String key, String fallback) {
        String fromProcess = System.getenv(key);
        if (fromProcess != null && !fromProcess.isBlank()) {
            return fromProcess;
        }
        String fromFile = EnvironmentVariables.instance().getAll().get(key);
        return fromFile != null && !fromFile.isBlank() ? fromFile : fallback;
    }
}
