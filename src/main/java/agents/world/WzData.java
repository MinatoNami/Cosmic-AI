package agents.world;

import provider.DataProvider;
import provider.DataProviderFactory;
import provider.wz.WZFiles;

import java.util.EnumMap;
import java.util.Map;

/**
 * One provider per WZ file, shared by everything the agents read from the game data.
 *
 * {@link DataProviderFactory} builds a new provider on every call, and building one walks the
 * file's whole directory tree - five and a half thousand files for Map.wz. The server asks
 * rarely; route planning asks for every map it considers, up to three times each. On a fresh
 * daemon that was hundreds of directory walks on the agents' own threads before their first
 * route, long enough that the server dropped three of four agents as idle after every restart.
 * A provider reads its files on demand and holds no other state, so one is as good as many.
 */
public final class WzData {
    private static final Map<WZFiles, DataProvider> PROVIDERS = new EnumMap<>(WZFiles.class);

    private WzData() {
    }

    public static synchronized DataProvider of(WZFiles file) {
        return PROVIDERS.computeIfAbsent(file, DataProviderFactory::getDataProvider);
    }
}
