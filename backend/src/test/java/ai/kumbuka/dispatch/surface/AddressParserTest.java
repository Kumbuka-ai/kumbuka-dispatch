package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.domain.ExchangeAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * What the address production admits, and what it refuses.
 *
 * <p>Every refusal here is asserted to be a <strong>form</strong> refusal, and
 * that is the substance of the class rather than a detail of it: a form error
 * and a missing object are two classes that must never merge. This parser is
 * structurally unable to produce the second one — it resolves nothing — so the
 * assertions are about the first, and the separation is a property of where
 * the code sits rather than of what it happens to return.
 */
class AddressParserTest {

    // =======================================================================
    // What is admitted
    // =======================================================================

    @Test
    void a_child_address_splits_into_its_parts() {
        ExchangeAddress address = AddressParser.item("sprint", "164.1");

        assertThat(address.selector()).isEqualTo("sprint");
        assertThat(address.number()).isEqualTo(164);
        assertThat(address.sub()).isEqualTo(1);
        assertThat(address.suffix()).as("a plain child carries no suffix").isNull();
    }

    @Test
    void a_bracket_root_is_sub_zero_and_not_a_shorter_address() {
        assertThat(AddressParser.item("sprint", "164.0").sub())
            .as("the bracket is an exchange, addressed like any other. It is not the "
                + "collection and it is not a truncation")
            .isZero();
    }

    @Test
    void an_addendum_carries_exactly_one_lower_case_letter() {
        ExchangeAddress address = AddressParser.item("sprint", "164.0a");

        assertThat(address.suffix()).isEqualTo("a");
        assertThat(address.isAddendum()).isTrue();
    }

    @Test
    void the_rendered_form_round_trips_the_parsed_one() {
        assertThat(AddressParser.render(AddressParser.item("sprint", "164.0a")))
            .as("the canonical form is generated and never echoed, so what goes into a "
                + "Location header is what this produces")
            .isEqualTo("164.0a");
    }

    // =======================================================================
    // What is refused, and refused as FORM
    // =======================================================================

    @ParameterizedTest
    @ValueSource(strings = {
        "164",        // no sub: the id part is two numbers, not one
        "164.",       // an occupied part that is empty is broken, not shorter
        "164.1.2",    // three numbers is not this scheme's id
        "164.1A",     // upper case, which is rejected and never folded
        "164.1ab",    // a suffix is exactly one letter
        "164.1-a",    // a separator the production does not have
        "07.1",       // a leading zero would be a second string for one exchange
        "164.01",     // and so would one on the sub
        "-1.2",       // there is no negative number in a circle
        "164.1 ",     // trailing space changes the string, so it changes identity
        "SPRINT.1",   // a selector in the id position
    })
    void a_malformed_id_is_a_typed_form_refusal(String id) {
        assertThatThrownBy(() -> AddressParser.item("sprint", id))
            .isInstanceOf(SurfaceException.class)
            .extracting(e -> ((SurfaceException) e).reason())
            .as("a form violation is a typed rejection at stage 1, decidable without "
                + "knowing any scope — which is why it may answer 400 and leak nothing")
            .isEqualTo(SurfaceException.Reason.ADDRESS_MALFORMED);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Probe-Scope", "probe_scope", "-probe", "probe-", "probe.scope"})
    void a_scope_that_is_not_a_dns_label_is_refused_and_never_folded(String scope) {
        assertThatThrownBy(() -> AddressParser.scope(scope))
            .isInstanceOf(SurfaceException.class)
            .as("what does not change identity is tolerated; what changes it is rejected. "
                + "Folding case would make two strings resolve to one scope, which is an "
                + "identity statement and must not arise from leniency")
            .hasMessageContaining("DNS label");
    }

    @Test
    void an_empty_part_is_broken_rather_than_shorter() {
        assertThatThrownBy(() -> AddressParser.item("sprint", ""))
            .isInstanceOf(SurfaceException.class)
            .as("truncation is recognised by which parts are OCCUPIED, so an empty "
                + "occupied part is not a truncated address")
            .hasMessageContaining("empty");
    }

    // =======================================================================
    // The MCP form: the address as a URI, scheme leading
    // =======================================================================

    @Test
    void a_complete_uri_splits_into_scope_selector_and_id() {
        AddressParser.Parts parts = AddressParser.uri("dispatch://probe-scope/sprint/164.1");

        assertThat(parts.scope()).isEqualTo("probe-scope");
        assertThat(parts.selector()).isEqualTo("sprint");
        assertThat(parts.id()).isEqualTo("164.1");
    }

    @Test
    void a_trailing_slash_is_tolerated_because_it_changes_no_identity() {
        assertThat(AddressParser.uri("dispatch://probe-scope/sprint/164.1/").id())
            .as("the occupied parts decide which object is addressed, so a trailing slash "
                + "decides nothing. Tolerated on the way in, never generated on the way out")
            .isEqualTo("164.1");
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "probe-scope/sprint/164.1",             // no scheme: the scheme leads
        "worklist://probe-scope/sprint/164.1",  // another scheme is not ours to answer
        "dispatch://probe-scope/sprint",        // truncated, and a verb here takes complete
        "dispatch://probe-scope",               // truncated further
        "dispatch://probe-scope/sprint/164.1/x" // a fourth part this scheme does not have
    })
    void a_uri_that_is_not_a_complete_dispatch_address_is_refused(String uri) {
        assertThatThrownBy(() -> AddressParser.uri(uri))
            .isInstanceOf(SurfaceException.class)
            .extracting(e -> ((SurfaceException) e).reason())
            .isEqualTo(SurfaceException.Reason.ADDRESS_MALFORMED);
    }

    @Test
    void the_uri_form_applies_the_same_productions_as_the_path_form() {
        assertThatThrownBy(() -> AddressParser.uri("dispatch://probe-scope/sprint/164"))
            .isInstanceOf(SurfaceException.class)
            .as("one grammar behind both entrances. Two would be two places for it to "
                + "drift, and the drift would show up as one adapter admitting what the "
                + "other refuses")
            .hasMessageContaining("<number>.<sub>");
    }
}
