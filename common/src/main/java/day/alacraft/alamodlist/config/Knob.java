package day.alacraft.alamodlist.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares a {@code public static} non-final field as a tunable of a config file.
 *
 * <p><b>This annotation IS the registration.</b> There is no second list to keep in step: a field
 * carrying it is loaded, validated, written and reset by {@link KnobConfig}; a field without it is an
 * ordinary static the file never sees. (A hand-kept mirror list is how knobs became silently
 * unsaveable in a mod before this engine existed.)
 *
 * <p>Supported types: {@code boolean}, {@code int}, {@code long}, {@code double}, {@code String}.
 * A field of any other type fails when the config is built, instead of being skipped.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface Knob {

    /** Id of the section object this key lives in; must be declared with {@link Section} on the class. */
    String section();

    /** The explanation written into the operator's own file as {@code _comment_<key>}. */
    String doc();

    /** Lowest accepted value for a number. An out-of-range value falls back to the default. */
    double min() default Double.NEGATIVE_INFINITY;

    /** Highest accepted value for a number. An out-of-range value falls back to the default. */
    double max() default Double.POSITIVE_INFINITY;
}
