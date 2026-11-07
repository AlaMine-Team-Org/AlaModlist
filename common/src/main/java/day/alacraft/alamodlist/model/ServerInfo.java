package day.alacraft.alamodlist.model;

import day.alacraft.alamodlist.wire.WireSchema;

import java.util.List;

/**
 * What the server itself told the client. The address and the name of the server are not part of it.
 *
 * @param connection     {@code multiplayer}, {@code lan} or {@code realms}
 * @param pingVersion    the version from the server list ping, when the ping succeeded
 * @param connectionType {@code neoforge} or {@code other}, on NeoForge only
 * @param channels       heuristic: the network channels the connection uses
 * @param namespaces     heuristic: the registry namespaces the server synchronised
 * @param mods           parsed from the reply to the NeoForge mods command
 */
public record ServerInfo(
        String connection,
        String brand,
        String pingVersion,
        Integer pingProtocol,
        String connectionType,
        List<ChannelEntry> channels,
        List<NamespaceEntry> namespaces,
        List<String> featureFlags,
        Boolean hasServerResourcePack,
        Integer serverLinksCount,
        List<ServerModEntry> mods,
        List<CommandReply> commandReplies) {

    public ServerInfo {
        connection = WireSchema.clean(ServerInfo.class, "connection", connection);
        brand = WireSchema.clean(ServerInfo.class, "brand", brand);
        pingVersion = WireSchema.clean(ServerInfo.class, "ping_version", pingVersion);
        pingProtocol = WireSchema.clean(ServerInfo.class, "ping_protocol", pingProtocol);
        connectionType = WireSchema.clean(ServerInfo.class, "connection_type", connectionType);
        channels = WireSchema.clean(ServerInfo.class, "channels", channels);
        namespaces = WireSchema.clean(ServerInfo.class, "namespaces", namespaces);
        featureFlags = WireSchema.clean(ServerInfo.class, "feature_flags", featureFlags);
        hasServerResourcePack = WireSchema.clean(ServerInfo.class, "has_server_resource_pack", hasServerResourcePack);
        serverLinksCount = WireSchema.clean(ServerInfo.class, "server_links_count", serverLinksCount);
        mods = WireSchema.clean(ServerInfo.class, "mods", mods);
        commandReplies = WireSchema.clean(ServerInfo.class, "command_replies", commandReplies);
    }
}
