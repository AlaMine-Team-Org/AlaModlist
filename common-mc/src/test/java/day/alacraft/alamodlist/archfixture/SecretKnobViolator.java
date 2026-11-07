package day.alacraft.alamodlist.archfixture;

import day.alacraft.alamodlist.config.Knob;

/**
 * Breaks SECRETS_ARE_NOT_KNOBS on purpose: knobs named like secrets (a settings file is exported with
 * modpacks and shown in the settings screen). Never run. {@code showHud} is the clean counterpart: an
 * ordinary knob that the rule must leave alone.
 */
public final class SecretKnobViolator {

    @Knob(section = "x", doc = "violator")
    public static String apiToken = "";

    @Knob(section = "x", doc = "violator")
    public static String clientSecret = "";

    @Knob(section = "x", doc = "violator")
    public static String PASSWORD = "";

    @Knob(section = "x", doc = "violator")
    public static String api_key = "";

    @Knob(section = "x", doc = "violator")
    public static String accountCredentials = "";

    @Knob(section = "x", doc = "clean")
    public static boolean showHud = true;
}
