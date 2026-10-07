package ai.kumbuka.dispatch.surface;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * How a whole number is read, over every shape a parser can hand in: the
 * surfaces carry a JSON number or text, and which Java type a JSON number
 * becomes is the parser's choice, not the caller's.
 */
class CallArgumentsTest {

    static Stream<Arguments> taken() {
        return Stream.of(
            Arguments.of(10, 10),
            Arguments.of(10L, 10),
            Arguments.of((short) 3, 3),
            Arguments.of((byte) 4, 4),
            Arguments.of(10.0d, 10),
            Arguments.of(2147483647.0d, Integer.MAX_VALUE),
            Arguments.of(-2147483648L, Integer.MIN_VALUE),
            Arguments.of(new BigDecimal("1E+1"), 10),
            Arguments.of(new BigDecimal("0.000"), 0),
            Arguments.of(BigInteger.valueOf(7), 7),
            Arguments.of(" 12 ", 12),
            Arguments.of("+5", 5),
            Arguments.of("-0", 0),
            Arguments.of("2147483647", Integer.MAX_VALUE),
            Arguments.of("-2147483648", Integer.MIN_VALUE),
            Arguments.of("0000000000000000000000000000001", 1));
    }

    static Stream<Object> notWhole() {
        return Stream.of(1.5d, Double.NaN, Double.POSITIVE_INFINITY, new BigDecimal("1.5"),
            "1.0", "abc", "", "1e3", "١٢", Boolean.TRUE);
    }

    static Stream<Object> tooLarge() {
        return Stream.of(4294967297L, 2147483648L, -2147483649L, 4294967297.0d,
            new BigDecimal("1E+400"), new BigDecimal("-1E+400"),
            new BigInteger("123456789012345678901234567890"),
            "4294967297", "-2147483649",
            "99999999999999999999999999999999999999", "-99999999999999999999999999999999");
    }

    @ParameterizedTest
    @MethodSource("taken")
    void a_whole_number_inside_the_range_is_taken(Object given, int read) {
        assertThat(query(given).integer("limit")).isEqualTo(read);
    }

    @ParameterizedTest
    @MethodSource("notWhole")
    void a_value_that_is_no_whole_number_is_refused_as_such(Object given) {
        Refused refused = catchThrowableOfType(Refused.class, () -> query(given));
        assertThat(refused.code()).isEqualTo(RefusalCode.ARGUMENT_INVALID);
        assertThat(refused.getMessage()).contains("limit").endsWith("it is a whole number.");
    }

    @ParameterizedTest
    @MethodSource("tooLarge")
    void a_whole_number_past_the_range_is_refused_as_too_large(Object given) {
        Refused refused = catchThrowableOfType(Refused.class, () -> query(given));
        assertThat(refused.code()).isEqualTo(RefusalCode.ARGUMENT_INVALID);
        assertThat(refused.getMessage()).contains("limit")
            .contains("too large for a whole number here, which lies from -2147483648 to "
                + "2147483647");
    }

    private static CallArguments query(Object limit) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("scope", "probe-scope");
        arguments.put("selector", "sprint");
        arguments.put("limit", limit);
        return new CallArguments(ProcessVerb.QUERY, "dispatch_query", arguments);
    }
}
