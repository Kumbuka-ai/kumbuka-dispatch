package ai.kumbuka.dispatch.api;

import ai.kumbuka.dispatch.api.mcp.McpTools;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The conformance probe, static half: does this surface project exactly the
 * specified verb set, and nothing else.
 *
 * <p>Two halves, and both are needed. <strong>Coverage</strong> — every
 * specified form is reachable. <strong>Closure</strong> — no route exists that
 * is not specified. Coverage alone passes a surface that has grown a back
 * door; closure alone passes a surface that has lost half its verbs. An
 * undeclared route is a violation, which is what makes this a probe rather
 * than an inventory.
 *
 * <h2>The expectation is not derived from the code</h2>
 *
 * It is read from {@code verb-surface.tsv}, transcribed from the dispatch and
 * from the two ratified concept documents. A probe that read its expectation
 * out of the thing it probes asserts that one copy equals itself. Both
 * expositions are probed against the same file and neither is the source for
 * the other — share-nothing applied to the surface.
 *
 * <h2>Why this half is static and where the other half lives</h2>
 *
 * Closure is a property of what was built, not of what happens to answer
 * today: no call can enumerate the routes that do not exist, so it is read
 * from the annotations, which is what the framework reads. Coverage of the
 * outward forms is the opposite — a form is reachable or it is not, and only
 * a call can say — so it is measured end to end in
 * {@code SurfaceConformanceIT} against a running database.
 */
class VerbSurfaceConformanceTest {

    /** Where the built classes are. The probe walks what the framework loads. */
    private static final String CLASS_ROOT = "target/classes";
    private static final String PACKAGE_ROOT = "ai/kumbuka/dispatch";

    /** {@code {name:regex}} is one template parameter, however it is spelled. */
    private static final Pattern TEMPLATE = Pattern.compile("\\{(\\w+)\\s*:[^{}]*}");

    /** Below this the probe is not walking the tree it thinks it is. */
    private static final int MINIMUM_ROUTES = 8;

    // =======================================================================
    // Closure, on the bindings
    // =======================================================================

    @Test
    void no_route_exists_outside_the_declared_bindings() {
        Set<String> declared = VerbSurfaceSpecification.routesOf("binding", "extension");

        List<String> undeclared = builtRoutes().stream()
            .filter(route -> !declared.contains(route))
            .toList();

        assertThat(undeclared)
            .as("REST is extensible while an extension adds no effect, and every extension "
                + "is DECLARED — so a route nobody wrote into the specification is a "
                + "violation and not a feature. State changes come from a verb, without "
                + "exception")
            .isEmpty();
    }

    @Test
    void every_declared_binding_is_registered() {
        Set<String> built = builtRoutes();

        List<String> missing = VerbSurfaceSpecification.routesOf("binding", "extension").stream()
            .filter(route -> !built.contains(route))
            .toList();

        assertThat(missing)
            .as("a binding the specification declares and the framework never registered "
                + "is a form nothing can reach, however carefully the verb behind it was "
                + "written")
            .isEmpty();
    }

    /**
     * The probe must have found routes at all.
     *
     * <p>A walk that finds none satisfies closure trivially, and it does so for
     * every wrong reason there is: wrong directory, stale pattern, classes not
     * built.
     */
    @Test
    void the_probe_walked_a_tree_that_has_routes_in_it() {
        assertThat(builtRoutes())
            .as("a probe that finds no routes passes closure while measuring nothing")
            .hasSizeGreaterThanOrEqualTo(MINIMUM_ROUTES);
    }

    // =======================================================================
    // Coverage and closure, on the colon verbs
    // =======================================================================

    /**
     * The colon verbs are routed from a table rather than by the framework, so
     * the table is where coverage and closure are checked for them.
     *
     * <p>Both directions in one assertion, deliberately: a verb in the table
     * and not in the specification is an undeclared act, and a verb in the
     * specification and not in the table is one no path reaches.
     */
    @Test
    void the_colon_verbs_are_exactly_the_specified_ones_at_each_depth() {
        assertThat(tableVerbs(CustomMethod.Depth.ITEM))
            .as("the item-depth custom methods must be the specified ones exactly. One too "
                + "many is a route nobody declared; one too few is a verb no caller can "
                + "reach")
            .containsExactlyInAnyOrderElementsOf(VerbSurfaceSpecification.colonVerbsAt("item"));

        assertThat(tableVerbs(CustomMethod.Depth.COLLECTION))
            .as("and at collection depth, where the only declarable set semantics is "
                + "exactly one")
            .containsExactlyInAnyOrderElementsOf(VerbSurfaceSpecification.colonVerbsAt("collection"));
    }

    /**
     * The split has to survive an address that grows segments.
     *
     * <p>Split at the last colon rather than the first: the address part is
     * the thing that may grow and the verb is the thing that may not, so a
     * first-colon split would let a future multi-segment id shadow the verb.
     */
    @Test
    void the_verb_is_split_off_the_end_and_not_off_the_front() {
        CustomMethod.Split split = CustomMethod
            .split("a:b/164.0:send", CustomMethod.Depth.ITEM)
            .orElseThrow();

        assertThat(split.address()).isEqualTo("a:b/164.0");
        assertThat(split.method()).isEqualTo(CustomMethod.SEND);
    }

    @Test
    void a_segment_with_no_colon_carries_no_verb_at_all() {
        assertThat(CustomMethod.split("164.0", CustomMethod.Depth.ITEM))
            .as("a plain address is not a malformed verb, and the two must not be answered "
                + "the same way")
            .isEmpty();
    }

    // =======================================================================
    // The MCP projection
    // =======================================================================

    @Test
    void the_mcp_exposition_declares_exactly_the_carried_verbs() {
        Set<String> carried = VerbSurfaceSpecification.carriedVerbs();

        Set<String> declared = McpTools.declared().stream()
            .map(McpTools.Tool::name)
            .collect(Collectors.toCollection(LinkedHashSet::new));

        assertThat(declared)
            .as("MCP omits and never adds, and today there is no declared omission — so the "
                + "tool list is the carried verb set exactly. A tool with no verb behind it "
                + "would be an addition, and a verb with no tool would be an omission "
                + "nobody declared")
            .containsExactlyInAnyOrderElementsOf(carried);
    }

    @Test
    void every_mcp_tool_declares_a_closed_argument_schema() {
        for (McpTools.Tool tool : McpTools.declared()) {
            assertThat(tool.inputSchema())
                .as("the schema of '%s' must close additionalProperties: an argument the "
                    + "surface does not know is one a caller believes in, and accepting it "
                    + "silently is how a client comes to depend on a field nobody reads",
                    tool.name())
                .containsEntry("additionalProperties", false);
        }
    }

    // =======================================================================
    // What was built
    // =======================================================================

    /** Every JAX-RS route in this application, as {@code METHOD path}. */
    private static Set<String> builtRoutes() {
        Set<String> routes = new LinkedHashSet<>();

        for (Class<?> type : applicationClasses()) {
            Path onClass = type.getAnnotation(Path.class);
            if (onClass == null) {
                continue;
            }
            for (Method method : type.getDeclaredMethods()) {
                String verb = httpMethodOf(method);
                if (verb != null) {
                    Path onMethod = method.getAnnotation(Path.class);
                    routes.add(verb + " " + normalise(onClass.value(),
                        onMethod == null ? "" : onMethod.value()));
                }
            }
        }
        return routes;
    }

    /**
     * The HTTP method a resource method answers, or null if it answers none.
     *
     * <p>Read through {@link HttpMethod} rather than by listing {@code @GET},
     * {@code @POST} and the rest: a meta-annotation is how JAX-RS itself
     * decides, and a hand-written list would miss a custom method the day
     * somebody adds one — which is exactly the day closure needs to notice.
     */
    private static String httpMethodOf(Method method) {
        for (Annotation annotation : method.getAnnotations()) {
            HttpMethod http = annotation.annotationType().getAnnotation(HttpMethod.class);
            if (http != null) {
                return http.value();
            }
        }
        return null;
    }

    /** Joins a class path and a method path, and reduces a template to its name. */
    private static String normalise(String onClass, String onMethod) {
        String joined = (onClass + "/" + onMethod).replaceAll("/{2,}", "/");
        if (joined.length() > 1 && joined.endsWith("/")) {
            joined = joined.substring(0, joined.length() - 1);
        }
        if (!joined.startsWith("/")) {
            joined = "/" + joined;
        }
        return TEMPLATE.matcher(joined).replaceAll("{$1}");
    }

    /** The verbs the routing table carries at one depth. */
    private static Set<String> tableVerbs(CustomMethod.Depth depth) {
        return CustomMethod.at(depth).stream()
            .map(CustomMethod::verb)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Every class of this application, loaded from where the build put them. */
    private static List<Class<?>> applicationClasses() {
        java.nio.file.Path root = Paths.get(CLASS_ROOT, PACKAGE_ROOT);
        assertThat(root)
            .as("the probe reads the built classes, so the build must have produced them")
            .exists();

        try (Stream<java.nio.file.Path> tree = Files.walk(root)) {
            List<Class<?>> classes = new ArrayList<>();
            for (java.nio.file.Path file : tree.filter(p -> p.toString().endsWith(".class"))
                    .toList()) {
                String name = Paths.get(CLASS_ROOT).relativize(file).toString()
                    .replace(".class", "")
                    .replace(java.io.File.separatorChar, '.');
                classes.add(Class.forName(name, false,
                    VerbSurfaceConformanceTest.class.getClassLoader()));
            }
            return classes;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("a built class could not be loaded", e);
        }
    }

}
