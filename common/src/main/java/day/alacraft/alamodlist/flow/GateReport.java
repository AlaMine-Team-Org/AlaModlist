package day.alacraft.alamodlist.flow;

import day.alacraft.alamodlist.privacy.Finding;
import day.alacraft.alamodlist.privacy.KnownValues;
import day.alacraft.alamodlist.privacy.TextClean;
import day.alacraft.alamodlist.privacy.Verdict;

import java.util.ArrayList;
import java.util.List;

/**
 * What the player is told when the privacy gate stopped a request: what kind of thing was found and where - never
 * the value, never a piece of the text around it (the finding does not carry one).
 *
 * @param lines one per finding, at most {@link #MAX_LINES}
 * @param more  how many findings were left out of {@code lines}
 */
public record GateReport(List<Line> lines, int more) {

    /** More lines than this are a wall of text; the rest is counted. */
    public static final int MAX_LINES = 8;

    /**
     * @param labelKey the translation key of the kind of thing found
     * @param path     where, as in {@code mods[12].name}; cleaned like any text that reaches the screen
     */
    public record Line(String labelKey, String path) {
    }

    public GateReport {
        lines = List.copyOf(lines);
    }

    /** The report for a verdict that is not clean. */
    public static GateReport of(Verdict verdict) {
        List<Line> lines = new ArrayList<>();
        int total;

        if (verdict.errored()) {
            // The gate could not do its job, so nothing was judged: said as such, not as "found nothing".
            lines.add(new Line("screen.alamodlist.gate.found.gate_error", TextClean.line(80, verdict.error())));
            total = 1;
        } else {
            total = verdict.findings().size();
            for (Finding finding : verdict.findings()) {
                if (lines.size() < MAX_LINES) {
                    lines.add(new Line(labelKey(finding.label()), TextClean.line(80, finding.path())));
                }
            }
        }

        return new GateReport(lines, total - lines.size());
    }

    /** The key for a finding's label; a label this mod does not know yet reads as the generic one. */
    static String labelKey(String label) {
        return switch (label) {
            case KnownValues.PLAYER_NAME -> "screen.alamodlist.gate.found.player_name";
            case KnownValues.PLAYER_UUID -> "screen.alamodlist.gate.found.player_uuid";
            case KnownValues.SESSION -> "screen.alamodlist.gate.found.session";
            case KnownValues.ACCESS_TOKEN -> "screen.alamodlist.gate.found.access_token";
            case KnownValues.SERVER_ADDRESS -> "screen.alamodlist.gate.found.server_address";
            case KnownValues.SERVER_NAME -> "screen.alamodlist.gate.found.server_name";
            case KnownValues.HOME_DIR -> "screen.alamodlist.gate.found.home_dir";
            case KnownValues.OS_USER -> "screen.alamodlist.gate.found.os_user";
            case KnownValues.COMPUTER_NAME -> "screen.alamodlist.gate.found.computer_name";
            case KnownValues.GAME_DIR -> "screen.alamodlist.gate.found.game_dir";
            case KnownValues.CONFIG_DIR -> "screen.alamodlist.gate.found.config_dir";
            case KnownValues.WORLD_SEED -> "screen.alamodlist.gate.found.world_seed";
            case KnownValues.WORLD_NAME -> "screen.alamodlist.gate.found.world_name";
            case KnownValues.OTHER_PLAYERS -> "screen.alamodlist.gate.found.other_players";
            case "unknown_key" -> "screen.alamodlist.gate.found.unknown_key";
            case "duplicate_key" -> "screen.alamodlist.gate.found.duplicate_key";
            default -> "screen.alamodlist.gate.found.other";
        };
    }
}
