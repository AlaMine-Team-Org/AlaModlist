package day.alacraft.alamodlist.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Repeatable;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * One section of a config file, declared on the class that holds its {@link Knob} fields. Declaration
 * order is the order the sections appear in the file; the doc is written above the section.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Repeatable(Section.List.class)
public @interface Section {

    /** Json key of the section object. */
    String id();

    /** One line written as {@code _comment_<id>} above the section. */
    String doc();

    /** Container for repeated {@link Section} annotations; not used directly. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface List {
        Section[] value();
    }
}
