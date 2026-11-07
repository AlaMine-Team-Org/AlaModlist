package day.alacraft.alamodlist.arch;

import com.tngtech.archunit.core.domain.JavaAccess;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMembers;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Architecture rules of the shared game code, checked on its BYTECODE - what the code does, not how it
 * is written: a text search would miss a dependency that arrives through a type and flag the same name
 * inside a comment.
 *
 * <p>Every rule is proven able to fail: {@link #eachRuleCatchesItsViolator()} runs it on a class written
 * to break it (and on a clean counterpart). A rule nobody has seen fail is indistinguishable from a rule
 * that cannot fail - in one of our mods two custom conditions were inverted and stayed green for months.
 */
class ArchitectureRules {

    private static final String ROOT = "day.alacraft.alamodlist";

    /** The sources of this module; Gradle runs its tests with the module as the working directory. */
    private static final Path MAIN_SOURCES = Path.of("src/main/java");

    /** Shared code knows no loader - a loader type here means the other loader's jar cannot load the class. */
    static final ArchRule SHARED_CODE_IS_LOADER_NEUTRAL = noClasses()
            .that().resideInAPackage(ROOT + "..")
            .should().dependOnClassesThat().resideInAnyPackage("net.fabricmc..", "net.neoforged..", "com.terraformersmc..")
            .as("shared code does not depend on a loader API");

    /**
     * Client types only in client packages. A client type in a field or signature of a common class is a
     * NoClassDefFoundError on a dedicated server - invisible in a dev client, and invisible to a server
     * game test in dev too, because the dev runtime has every client class on the classpath.
     */
    static final ArchRule CLIENT_TYPES_STAY_IN_CLIENT_PACKAGES = noClasses()
            .that().resideInAPackage(ROOT + "..")
            .and().resideOutsideOfPackages(ROOT + ".client..", ROOT + ".mixin.client..")
            .should().dependOnClassesThat().resideInAnyPackage("net.minecraft.client..", "com.mojang.blaze3d..")
            .as("client types stay inside the client packages");

    /**
     * No registration in shared code. NeoForge freezes its registries before a mod constructor runs, so a
     * Registry.register in shared code crashes there ("already frozen") while Fabric shows nothing.
     * Registration belongs to the loaders, which play the shared lists.
     */
    static final ArchRule SHARED_CODE_DOES_NOT_REGISTER = noClasses()
            .that().resideInAPackage(ROOT + "..")
            .should(callRegistryRegister())
            .as("shared code does not call Registry.register");

    /** Minecraft 26.2 renders through OpenGL or (experimentally) Vulkan; mod code must work on both. */
    static final ArchRule RENDERING_STAYS_BACKEND_AGNOSTIC = noClasses()
            .that().resideInAPackage(ROOT + "..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.lwjgl.opengl..", "org.lwjgl.vulkan..", "com.mojang.blaze3d.opengl..", "com.mojang.blaze3d.vulkan..")
            .as("rendering names no graphics backend");

    /**
     * Where the iteration order decides what happens or what a player sees, only ordered collections
     * (ADR-010): HashMap/HashSet iterate by hash, and Set.of/Map.of by a salt new on every JVM run - a
     * test passes alone and fails in the full run, a world behaves differently after a restart. The zone
     * is the list of packages below; it grows with the mod. {@code ..registry..} also covers the fixture
     * package {@code archfixture.registry}, which is how the negative control reaches this exact rule.
     *
     * <p>The zone may be empty: a client-only mod has no registries, and ArchUnit refuses by default a rule
     * that checked no class. That refusal is lifted for THIS rule only, because the negative control keeps it
     * honest - it still catches its violators in {@link #eachRuleCatchesItsViolator()}.
     */
    static final ArchRule ORDER_SENSITIVE_CODE_USES_ORDERED_COLLECTIONS = noClasses()
            .that().resideInAnyPackage(ROOT + "..registry..")
            .should(useUnorderedCollections())
            .as("order-sensitive code uses ordered collections")
            .allowEmptyShould(true);

    /**
     * A secret is not a setting (ADR-036). A {@code @Knob} lands in the operator's config file, which
     * launchers export with the modpack, and in the settings screen; secrets live in {@code SecretStore}.
     * The rule goes by the name, so it also stops a token that was merely named innocently wrong - rename
     * the knob (a count of tokens is {@code maxTokens} nowhere in this mod), do not loosen the rule.
     */
    static final ArchRule SECRETS_ARE_NOT_KNOBS = noMembers()
            .that().areAnnotatedWith("day.alacraft.alamodlist.config.Knob")
            .should().haveNameMatching("(?i).*(token|secret|password|passwd|api_?key|credential).*")
            .as("a secret is not a knob (use SecretStore)");

    private static final List<ArchRule> RULES = List.of(SHARED_CODE_IS_LOADER_NEUTRAL,
            CLIENT_TYPES_STAY_IN_CLIENT_PACKAGES, SHARED_CODE_DOES_NOT_REGISTER, RENDERING_STAYS_BACKEND_AGNOSTIC,
            ORDER_SENSITIVE_CODE_USES_ORDERED_COLLECTIONS, SECRETS_ARE_NOT_KNOBS);

    /** Types that iterate by hash. */
    private static final Set<String> HASH_TYPES = Set.of("java.util.HashMap", "java.util.HashSet");
    /** Factories whose result iterates by hash or by a per-run salt: owner.method. */
    private static final Set<String> SALTED_FACTORIES = Set.of(
            "java.util.Set.of", "java.util.Set.copyOf", "java.util.Map.of", "java.util.Map.copyOf",
            "java.util.Map.ofEntries", "java.util.stream.Collectors.toSet", "java.util.stream.Collectors.toMap",
            "java.util.stream.Collectors.toUnmodifiableSet", "java.util.stream.Collectors.toUnmodifiableMap",
            "java.util.stream.Collectors.groupingBy");

    @Test
    void theSharedCodeFollowsEveryRule() {
        JavaClasses main = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
        // A rule over no classes proves nothing: fail loudly if the import went blind. Every source file
        // of this module yields at least one class, so fewer classes than files means classes went missing.
        long sources = javaFilesOfMain();
        assertTrue(sources > 0, "no .java files found under " + MAIN_SOURCES.toAbsolutePath());
        assertTrue(main.size() >= sources,
                "only " + main.size() + " classes imported from " + ROOT + ", the module has " + sources + " source files");
        for (ArchRule rule : RULES) {
            rule.check(main);
        }
    }

    @Test
    void eachRuleCatchesItsViolator() {
        // Imported from the class FILES by package: the fixtures reference game classes that are not on
        // the test runtime classpath, so they must never be loaded - only read.
        JavaClasses fixtures = new ClassFileImporter()
                .importPackages(ROOT + ".archfixture", ROOT + ".client.archfixture");
        assertTrue(fixtures.size() >= 5, "fixtures not found: " + fixtures.size());
        Map<ArchRule, List<String>> violators = Map.of(
                SHARED_CODE_IS_LOADER_NEUTRAL, List.of("LoaderApiViolator"),
                CLIENT_TYPES_STAY_IN_CLIENT_PACKAGES, List.of("ClientTypeViolator"),
                SHARED_CODE_DOES_NOT_REGISTER, List.of("RegistrationViolator"),
                RENDERING_STAYS_BACKEND_AGNOSTIC, List.of("BackendViolator"),
                ORDER_SENSITIVE_CODE_USES_ORDERED_COLLECTIONS, List.of("SaltedCallViolator", "HashReferenceViolator"),
                SECRETS_ARE_NOT_KNOBS, List.of("apiToken", "clientSecret", "PASSWORD", "api_key", "accountCredentials"));
        assertTrue(violators.keySet().containsAll(RULES), "every rule needs a violator");
        for (Map.Entry<ArchRule, List<String>> entry : violators.entrySet()) {
            List<String> details = entry.getKey().evaluate(fixtures).getFailureReport().getDetails();
            for (String violator : entry.getValue()) {
                assertTrue(details.stream().anyMatch(detail -> detail.contains(violator)),
                        entry.getKey().getDescription() + " did not catch " + violator + ": " + details);
            }
        }
        // ...and the clean counterparts are allowed: a client type inside a client package, ordered
        // collections inside the order-sensitive zone.
        List<String> clientDetails = CLIENT_TYPES_STAY_IN_CLIENT_PACKAGES.evaluate(fixtures).getFailureReport().getDetails();
        assertFalse(clientDetails.stream().anyMatch(detail -> detail.contains("ClientTypeUser")),
                "a client type inside a client package was flagged: " + clientDetails);
        List<String> orderDetails = ORDER_SENSITIVE_CODE_USES_ORDERED_COLLECTIONS.evaluate(fixtures)
                .getFailureReport().getDetails();
        assertFalse(orderDetails.stream().anyMatch(detail -> detail.contains("OrderedUser")),
                "ordered collections were flagged: " + orderDetails);
        List<String> secretDetails = SECRETS_ARE_NOT_KNOBS.evaluate(fixtures).getFailureReport().getDetails();
        assertFalse(secretDetails.stream().anyMatch(detail -> detail.contains("showHud")),
                "an ordinary knob was flagged as a secret: " + secretDetails);
    }

    private static long javaFilesOfMain() {
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            return files.filter(path -> path.toString().endsWith(".java")).count();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Reports each call to a {@code register...} method of {@code net.minecraft.core.Registry}.
     *
     * <p>The event is SATISFIED when the forbidden call is found: {@code noClasses().should(...)} turns a
     * satisfied event into the violation. Reporting it as violated is the inversion that keeps such a
     * rule green forever - which is what the negative control above is for.
     */
    private static ArchCondition<JavaClass> callRegistryRegister() {
        return new ArchCondition<>("call Registry.register") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (JavaMethodCall call : item.getMethodCallsFromSelf()) {
                    if (call.getTargetOwner().isAssignableTo("net.minecraft.core.Registry")
                            && call.getName().startsWith("register")) {
                        events.add(SimpleConditionEvent.satisfied(item, call.getDescription()));
                    }
                }
            }
        };
    }

    /**
     * Reports every ACCESS - call, constructor call, method or constructor reference, field access - to a
     * hash-ordered type or a salted factory. Accesses rather than calls: {@code HashSet::new} is a
     * reference, and a check of calls alone let it through in one of our mods. Satisfied = found, as above.
     */
    private static ArchCondition<JavaClass> useUnorderedCollections() {
        return new ArchCondition<>("use a hash-ordered or salted collection") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (JavaAccess<?> access : item.getAccessesFromSelf()) {
                    String owner = access.getTargetOwner().getName();
                    if (HASH_TYPES.contains(owner) || SALTED_FACTORIES.contains(owner + "." + access.getName())) {
                        events.add(SimpleConditionEvent.satisfied(item, access.getDescription()));
                    }
                }
            }
        };
    }
}
