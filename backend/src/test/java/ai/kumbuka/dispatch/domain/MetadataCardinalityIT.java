package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.platform.PlatformFixture;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import ai.kumbuka.dispatch.tenancy.TenantContext;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The seven acceptance criteria of BUG-52, one test each.
 *
 * <h2>What this is about</h2>
 *
 * <p>Metadata values widen from a single identifier to either a single
 * identifier <strong>or</strong> a list of them — one level deep, and nothing
 * else. What broke in production was the read path: a bestand carrying lists
 * for the keys {@code tracks} and {@code task} could not be deserialised into
 * {@code Map<String, String>}, and every {@code query} that reached one such
 * row answered 500. The columns are {@code jsonb}, so the write path already
 * held the shape; the storage held the truth and the read model refused it.
 *
 * <p>This class simulates that bestand with a direct SQL insert of a row that
 * carries {@code "tracks": [...]}, and reads it back through the verbs. It
 * also exercises the write side: a list arrives through {@code send} and
 * survives a read. The list widens {@link Metadata#validate} too — the length
 * check and the credential check now apply per element — and lists of nested
 * shapes are refused with a typed error rather than flattened or accepted.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class MetadataCardinalityIT {

    static final UUID SCOPE = UUID.fromString("00000000-0000-0000-0000-000000000010");
    static final String SELECTOR = "sprint";

    static final Actor CONSOLE = new Actor("md-console", Actor.Kind.CONSOLE);

    @Inject ExchangeService exchanges;
    @Inject TenantContext tenantContext;

    private UUID tenant;
    private AutoCloseable binding;

    @BeforeEach
    void freshTenant() {
        tenant = UUID.randomUUID();
        DomainFixture.declareSelector(tenant, SCOPE, SELECTOR);
        binding = tenantContext.bind(tenant);
    }

    @AfterEach
    void unbind() throws Exception {
        binding.close();
    }

    // ---------------------------------------------------------------------
    // Criterion 1 — a query over a selector whose bestand carries a list
    // metadata value answers instead of 500.
    // ---------------------------------------------------------------------

    @Test
    void query_over_a_selector_with_a_list_metadata_row_in_the_bestand_answers() {
        insertBestandRow("in-the-bestand", Map.of("tracks", List.of("a", "b", "c")));

        List<ExchangeView> found = exchanges.query(SCOPE, SELECTOR, QueryFilter.none(), CONSOLE);

        assertThat(found)
            .as("the read path deserialises a jsonb value that is a list, rather than "
                + "refusing it with a jackson type mismatch. One such row in the "
                + "selector was enough to poison the entire verb before this")
            .singleElement()
            .satisfies(v -> assertThat(v.title()).isEqualTo("in-the-bestand"));
    }

    // ---------------------------------------------------------------------
    // Criterion 2 — read of a row carrying `tracks` as a list returns the
    // list unchanged: same elements, same order.
    // ---------------------------------------------------------------------

    @Test
    void read_of_a_tracks_list_returns_the_list_unchanged() {
        UUID id = insertBestandRow("with-tracks", Map.of("tracks", List.of("t1", "t2", "t3")));

        Exchange read = readByRowId(id);

        assertThat(read.dispatchMetadata)
            .as("the key is carried through unchanged")
            .containsKey("tracks");
        assertThat(read.dispatchMetadata.get("tracks"))
            .as("and the value is the same list, in the same order — the read path "
                + "reconstitutes it as a list rather than as a string or a set")
            .isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> tracks = (List<String>) read.dispatchMetadata.get("tracks");
        assertThat(tracks).containsExactly("t1", "t2", "t3");
    }

    // ---------------------------------------------------------------------
    // Criterion 3 — a write with a list value survives a read: the roundtrip
    // runs through the verb surface, not only through the entity.
    // ---------------------------------------------------------------------

    @Test
    void a_list_written_through_send_returns_from_read_unchanged() {
        Exchange draft = exchanges.openBracket(SCOPE, SELECTOR, "a commission with tracks",
            "code", LocalDate.now(), CONSOLE);

        Map<String, Object> md = new LinkedHashMap<>();
        md.put("tracks", List.of("alpha", "beta"));
        md.put("task", List.of("BUG-52"));
        exchanges.send(SCOPE, at(draft), CONSOLE, md);

        Exchange readBack = exchanges.read(SCOPE, at(draft));

        assertThat(readBack.dispatchMetadata.get("tracks"))
            .as("a list written through send is a list read back through read — the "
                + "verb surface roundtrip closes rather than the storage roundtrip")
            .isEqualTo(List.of("alpha", "beta"));
        assertThat(readBack.dispatchMetadata.get("task"))
            .isEqualTo(List.of("BUG-52"));
    }

    // ---------------------------------------------------------------------
    // Criterion 4 — a single string stays a string and is not silently
    // wrapped into a one-element list.
    // ---------------------------------------------------------------------

    @Test
    void a_single_string_value_is_carried_as_a_string_not_wrapped_into_a_list() {
        Exchange draft = exchanges.openBracket(SCOPE, SELECTOR, "a commission with a pointer",
            "code", LocalDate.now(), CONSOLE);

        Map<String, Object> md = new LinkedHashMap<>();
        md.put("pull-request", "https://example.invalid/pr/1");
        exchanges.send(SCOPE, at(draft), CONSOLE, md);

        Exchange readBack = exchanges.read(SCOPE, at(draft));

        assertThat(readBack.dispatchMetadata.get("pull-request"))
            .as("a caller who supplied a string reads back a string. Wrapping it into "
                + "a one-element list would change the shape of every existing single "
                + "value and break the reader")
            .isInstanceOf(String.class)
            .isEqualTo("https://example.invalid/pr/1");
    }

    // ---------------------------------------------------------------------
    // Criterion 5 — a list element longer than MAX_VALUE_LENGTH is refused,
    // with the same reason a too-long single value is refused with.
    // ---------------------------------------------------------------------

    @Test
    void a_list_element_longer_than_the_bound_is_refused() {
        Exchange draft = exchanges.openBracket(SCOPE, SELECTOR, "a commission",
            "code", LocalDate.now(), CONSOLE);

        Map<String, Object> md = new LinkedHashMap<>();
        md.put("tracks", List.of("short-one", "x".repeat(Metadata.MAX_VALUE_LENGTH + 1)));

        assertThatThrownBy(() -> exchanges.send(SCOPE, at(draft), CONSOLE, md))
            .as("the length bound applies per element. A list of ten short identifiers "
                + "is not one long value, and a single long element in a list is prose "
                + "in the wrong place — the same refusal as a single long value earns")
            .isInstanceOfSatisfying(DispatchException.class, x -> assertThat(x.reason())
                .isEqualTo(DispatchException.Reason.METADATA_REFUSED));
    }

    // ---------------------------------------------------------------------
    // Criterion 6 — a list element that carries credentials in a URL is
    // refused, per element.
    // ---------------------------------------------------------------------

    @Test
    void a_list_element_carrying_credentials_is_refused() {
        Exchange draft = exchanges.openBracket(SCOPE, SELECTOR, "a commission",
            "code", LocalDate.now(), CONSOLE);

        Map<String, Object> md = new LinkedHashMap<>();
        md.put("pull-requests", List.of(
            "https://example.invalid/pr/1",
            "https://user:secret@example.invalid/pr/2"));

        assertThatThrownBy(() -> exchanges.send(SCOPE, at(draft), CONSOLE, md))
            .as("the credential check applies per element too: this service holds "
                + "pointers and never follows them, and a URL with userinfo hiding in "
                + "a list is still a token stored where it can only be read, not used")
            .isInstanceOfSatisfying(DispatchException.class, x -> assertThat(x.reason())
                .isEqualTo(DispatchException.Reason.METADATA_REFUSED));
    }

    // ---------------------------------------------------------------------
    // Criterion 7 — a nested object as a metadata value is refused with a
    // typed error, and no row is written. This is the guard: cardinality
    // widens, typefreedom does not.
    // ---------------------------------------------------------------------

    @Test
    void a_nested_object_value_is_refused_with_a_typed_error_and_no_row_is_written() {
        Exchange draft = exchanges.openBracket(SCOPE, SELECTOR, "an attempt at freeform",
            "code", LocalDate.now(), CONSOLE);

        Map<String, Object> md = new LinkedHashMap<>();
        md.put("shape", Map.of("nested", "value"));

        assertThatThrownBy(() -> exchanges.send(SCOPE, at(draft), CONSOLE, md))
            .as("a nested object is a different rule than a list of identifiers. "
                + "Storing it would let metadata become a junk drawer of arbitrary "
                + "JSON, and the doctrine says otherwise — with a typed refusal "
                + "rather than a silent flattening")
            .isInstanceOfSatisfying(DispatchException.class, x -> assertThat(x.reason())
                .isEqualTo(DispatchException.Reason.METADATA_REFUSED));

        Exchange stillDraft = exchanges.read(SCOPE, at(draft));
        assertThat(stillDraft.dispatchMetadata)
            .as("the refusal is at the send gate, so nothing landed in dispatch_metadata")
            .isNull();
        assertThat(stillDraft.status())
            .as("and the exchange is still a draft — the transition never ran")
            .isEqualTo(ExchangeStatus.DRAFT);
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    /**
     * Inserts a sent exchange with the given metadata into the database, past
     * the verb surface — so the test can reason about a bestand row that
     * carries a shape the writer never had to produce.
     */
    private UUID insertBestandRow(String title, Map<String, Object> dispatchMetadata) {
        UUID id = UUID.randomUUID();
        int number = takeNextNumberOnCircle();
        String json = jsonOf(dispatchMetadata);

        PlatformFixture.run(
            "INSERT INTO dispatch.exchange ("
                + "id, tenant_id, scope_id, selector, number, sub, "
                + "status, title, apparatus, dispatch_date, sent_at, dispatch_metadata) "
                + "VALUES ('" + id + "', '" + tenant + "', '" + SCOPE + "', "
                + "'" + SELECTOR + "', " + number + ", 0, 'open', "
                + "'" + title.replace("'", "''") + "', 'code', CURRENT_DATE, now(), "
                + "'" + json.replace("'", "''") + "'::jsonb)");
        return id;
    }

    /**
     * Takes the next bracket number from the circle, so the inserted row does
     * not collide with anything the domain will insert next. The write is
     * outside the domain's transaction and it stays consistent with it.
     */
    private int takeNextNumberOnCircle() {
        // The circle is created by DomainFixture.declareSelector. Read and
        // bump it here so a domain openBracket in the same test would not
        // reuse the number.
        int[] taken = new int[1];
        var config = org.eclipse.microprofile.config.ConfigProvider.getConfig();
        try (var c = java.sql.DriverManager.getConnection(
                config.getValue("test.db.url", String.class),
                config.getValue("test.db.admin.username", String.class),
                config.getValue("test.db.admin.password", String.class));
             var stmt = c.prepareStatement(
                 "UPDATE dispatch.number_circle SET next_number = next_number + 1 "
                     + "WHERE tenant_id = ? AND scope_id = ? AND selector = ? "
                     + "RETURNING next_number - 1")) {
            stmt.setObject(1, tenant);
            stmt.setObject(2, SCOPE);
            stmt.setString(3, SELECTOR);
            try (var rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("no number circle for the selector");
                }
                taken[0] = rs.getInt(1);
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("could not bump number circle", e);
        }
        return taken[0];
    }

    /**
     * Reads back the row by id, through the domain's read path but using the
     * address on the row. The row was inserted with the same identity, so
     * the address is derivable rather than looked up.
     */
    private Exchange readByRowId(UUID id) {
        var config = org.eclipse.microprofile.config.ConfigProvider.getConfig();
        try (var c = java.sql.DriverManager.getConnection(
                config.getValue("test.db.url", String.class),
                config.getValue("test.db.admin.username", String.class),
                config.getValue("test.db.admin.password", String.class));
             var stmt = c.prepareStatement(
                 "SELECT selector, number, sub, addendum_suffix FROM dispatch.exchange "
                     + "WHERE id = ?")) {
            stmt.setObject(1, id);
            try (var rs = stmt.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("row not found: " + id);
                }
                ExchangeAddress addr = new ExchangeAddress(
                    rs.getString("selector"),
                    rs.getInt("number"),
                    rs.getInt("sub"),
                    rs.getString("addendum_suffix"));
                return exchanges.read(SCOPE, addr);
            }
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("could not read row " + id, e);
        }
    }

    /**
     * Minimal JSON writer for the bestand shapes this test needs — string
     * value or list-of-string value. Rejects anything else so a future test
     * that reaches for it fails loudly rather than silently.
     */
    private static String jsonOf(Map<String, Object> metadata) {
        StringBuilder b = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : metadata.entrySet()) {
            if (!first) b.append(',');
            first = false;
            b.append('"').append(escape(e.getKey())).append("\":");
            Object v = e.getValue();
            if (v instanceof String s) {
                b.append('"').append(escape(s)).append('"');
            } else if (v instanceof List<?> list) {
                b.append('[');
                boolean firstElem = true;
                for (Object element : list) {
                    if (!firstElem) b.append(',');
                    firstElem = false;
                    if (!(element instanceof String s)) {
                        throw new IllegalArgumentException(
                            "the bestand fixture writes strings in a list; "
                                + "got a " + (element == null ? "null" : element.getClass()));
                    }
                    b.append('"').append(escape(s)).append('"');
                }
                b.append(']');
            } else {
                throw new IllegalArgumentException(
                    "the bestand fixture writes strings or lists of strings; got a "
                        + (v == null ? "null" : v.getClass()));
            }
        }
        return b.append('}').toString();
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static ExchangeAddress at(Exchange e) {
        return new ExchangeAddress(e.selector, e.number, e.sub, e.addendumSuffix);
    }
}
