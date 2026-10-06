package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.adapter.mcp.McpTools;
import ai.kumbuka.dispatch.adapter.rest.RestRoute;
import ai.kumbuka.dispatch.contract.TargetSurface;
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
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The REST surface against its specification, and the two statements of the
 * surface against each other.
 *
 * <p>Three questions, each with its own source of truth. Whether the
 * framework registered exactly the declared bindings is read from the
 * annotations against {@code verb-surface.tsv}. Whether the routes of
 * {@link RestRoute}, written by hand, are exactly the calls of the declaration
 * -- one route per call, no route without a call -- is the test that holds the
 * two statements together while REST is not generated (dispatch 200.7, part B,
 * point 7). And whether those routes take the outward forms the specification
 * writes is read against the file again.
 *
 * <p>Red probes, observed: a route in {@link RestRoute} for a name the
 * declaration does not carry, and a declared call whose route was removed,
 * each turn {@link #every_declared_call_has_exactly_one_route_and_no_route_stands_without_one}
 * red.
 */
class VerbSurfaceConformanceTest {

    /** Where the build puts the application's classes, relative to the module. */
    private static final String CLASS_ROOT = "target/classes";

    /** The application's package, as a path under the class root. */
    private static final String PACKAGE_ROOT = "ai/kumbuka/dispatch";

    /** A path template with a regex, {@code {id: [0-9.]+}}, reduced to {@code {id}}. */
    private static final Pattern TEMPLATE = Pattern.compile("\\{(\\w+)\\s*:[^{}]*}");

    /** Fewer routes than this means the probe is walking the wrong tree. */
    private static final int MINIMUM_ROUTES = 8;

    // =======================================================================
    // Closure and coverage, on the bindings
    // =======================================================================

    @Test
    void no_route_exists_outside_the_declared_bindings() {
        Set<String> declared = VerbSurfaceSpecification.routesOf("binding", "extension");
        List<String> undeclared = builtRoutes().stream()
            .filter(route -> !declared.contains(route))
            .toList();

        assertThat(undeclared)
            .as("a route nobody wrote into the specification is a violation and not a "
                + "feature. State changes come from a call, without exception")
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
                + "is a form nothing can reach")
            .isEmpty();
    }

    @Test
    void the_probe_walked_a_tree_that_has_routes_in_it() {
        assertThat(builtRoutes())
            .as("a probe that finds no routes passes closure while measuring nothing")
            .hasSizeGreaterThanOrEqualTo(MINIMUM_ROUTES);
    }

    // =======================================================================
    // The two statements of the surface
    // =======================================================================

    @Test
    void every_declared_call_has_exactly_one_route_and_no_route_stands_without_one() {
        List<String> declared = ProcessVerb.names(Surface.REST);
        Map<String, Long> routed = Arrays.stream(RestRoute.values())
            .map(RestRoute::call)
            .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        assertThat(declared.stream().filter(call -> routed.getOrDefault(call, 0L) != 1L)
                .toList())
            .as("every call of the declaration has exactly one route. A call with none is "
                + "a call no REST caller can make; one with two is a call answered in two "
                + "places that will drift")
            .isEmpty();
        assertThat(routed.keySet().stream().filter(call -> !declared.contains(call)).toList())
            .as("and no route stands without a declared call: a route for a name the "
                + "declaration does not carry is a second surface nobody checks")
            .isEmpty();
    }

    @Test
    void the_routes_take_the_forms_the_specification_writes() {
        assertThat(Arrays.stream(RestRoute.values()).map(RestRoute::form).toList())
            .as("the outward form of every call, method and path, as verb-surface.tsv "
                + "writes it by hand")
            .containsExactlyInAnyOrderElementsOf(VerbSurfaceSpecification.callForms());
        assertThat(VerbSurfaceSpecification.calls())
            .as("and the specification names the twenty-five calls of TAR-0004 section 3")
            .containsExactlyInAnyOrderElementsOf(TargetSurface.REST);
    }

    @Test
    void the_verb_is_split_off_the_end_and_not_off_the_front() {
        String[] split = RestRoute.split("a:b/164.0:send");
        assertThat(split[0]).isEqualTo("a:b/164.0");
        assertThat(split[1]).isEqualTo("send");
        assertThat(RestRoute.split("164.0")[1])
            .as("a plain address is not a malformed verb, and the two must not be answered "
                + "the same way")
            .isNull();
    }

    // =======================================================================
    // The MCP projection
    // =======================================================================

    @Test
    void every_mcp_tool_is_a_declared_call_under_the_prefix() {
        assertThat(McpTools.declared().stream().map(McpTools.Tool::name).toList())
            .as("the assistant surface carries the same calls under the same names, each "
                + "prefixed dispatch_ because a tool list is flat and shared with other "
                + "services' tools")
            .containsExactlyInAnyOrderElementsOf(TargetSurface.MCP);
    }

    @Test
    @SuppressWarnings("unchecked")
    void every_mcp_tool_declares_an_argument_schema_closed_at_every_level() {
        List<String> open = new ArrayList<>();
        for (McpTools.Tool tool : McpTools.declared()) {
            if (!Boolean.FALSE.equals(tool.inputSchema().get("additionalProperties"))) {
                open.add(tool.name());
            }
            Map<String, Object> properties =
                (Map<String, Object>) tool.inputSchema().get("properties");
            Object fields = properties.get("fields");
            if (fields != null
                    && !Boolean.FALSE.equals(((Map<String, Object>) fields)
                        .get("additionalProperties"))) {
                open.add(tool.name() + ".fields");
            }
        }
        assertThat(open)
            .as("an argument the surface does not know is one a caller believes in. Both "
                + "levels of every schema close additionalProperties; one level of closure "
                + "is as much use as none, because nothing looks inside the other")
            .isEmpty();
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
