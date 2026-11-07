package day.alacraft.alamodlist.model;

import day.alacraft.alamodlist.wire.WireSchema;

import java.util.List;

/**
 * Everything one request carries: the root of the wire model.
 *
 * <p>The wire model is what leaves the game, and only that. Its fields are strings, numbers, booleans, lists and
 * nested records - no {@code Path}, {@code UUID}, {@code URI} or game type - so a file path or a player identity
 * cannot be put in one by accident. Each record cleans its fields in its constructor by the rules of
 * {@link WireSchema}: an instance cannot hold a value the service would refuse or a text it would clean.
 *
 * <p>A category the player left out is {@code null} (an object) or empty (a list).
 *
 * @param source who sends it, as {@code client/version}
 * @param locale the language of the link handed back
 * @param title  the player's name for the list
 * @param note   the player's note
 */
public record Snapshot(
        String source,
        String locale,
        String title,
        String note,
        EnvironmentInfo environment,
        List<ModEntry> mods,
        List<PackEntry> resourcePacks,
        List<ShaderEntry> shaderPacks,
        List<DataPackEntry> dataPacks,
        LauncherInfo launcher,
        ServerInfo server,
        List<PluginEntry> plugins) {

    public Snapshot {
        source = WireSchema.clean(Snapshot.class, "source", source);
        locale = WireSchema.clean(Snapshot.class, "locale", locale);
        title = WireSchema.clean(Snapshot.class, "title", title);
        note = WireSchema.clean(Snapshot.class, "note", note);
        environment = WireSchema.clean(Snapshot.class, "environment", environment);
        mods = WireSchema.clean(Snapshot.class, "mods", mods);
        resourcePacks = WireSchema.clean(Snapshot.class, "resource_packs", resourcePacks);
        shaderPacks = WireSchema.clean(Snapshot.class, "shader_packs", shaderPacks);
        dataPacks = WireSchema.clean(Snapshot.class, "data_packs", dataPacks);
        launcher = WireSchema.clean(Snapshot.class, "launcher", launcher);
        server = WireSchema.clean(Snapshot.class, "server", server);
        plugins = WireSchema.clean(Snapshot.class, "plugins", plugins);
    }
}
