package ai.kumbuka.dispatch.contract;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The contract document, read as the source of every expected value.
 *
 * <p><strong>This is the whole point of the conformance probes.</strong> The
 * tool descriptions, the refusal patterns and the {@code next} table are read
 * out of {@code contract/assistant-surface.md} — a verbatim copy of the
 * ratified concept document — and never out of the declaration they check. An
 * expectation written beside the code it checks is green for ever and asserts
 * nothing; an expectation read from a document somebody else owns goes red when
 * the two drift, which is the only useful behaviour a conformance probe has.
 *
 * <p>The parser is deliberately simple and deliberately strict: it finds the
 * blockquote under each bold tool name in section 5, and the rows of the table
 * in section 4.4. Where the document's shape changes, this throws rather than
 * silently finding nothing — a probe that expected nothing would pass.
 */
final class Contract {

    private static final String RESOURCE = "/contract/assistant-surface.md";

    private Contract() {
    }

    /** The whole document, as it was copied. */
    static String text() {
        try (InputStream in = Contract.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                    RESOURCE + " is not on the test classpath. The probes take every "
                        + "expected value from it; without it they would assert nothing "
                        + "and pass.");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The tool names and their normative descriptions, from section 5.
     *
     * <p>A tool is introduced as {@code **`dispatch_x`**} on its own line and
     * described by the blockquote directly under it. The blockquote's lines are
     * joined with single spaces, because a line break inside a paragraph of
     * Markdown is not part of the text — and a probe that compared line breaks
     * would go red on a re-wrap that changed nothing.
     */
    static Map<String, String> describedCalls() {
        Map<String, String> described = new LinkedHashMap<>();
        List<String> lines = List.of(text().split("\n", -1));

        for (int i = 0; i < lines.size(); i++) {
            String name = boldCallName(lines.get(i));
            if (name == null) {
                continue;
            }
            List<String> quoted = new ArrayList<>();
            for (int j = i + 1; j < lines.size(); j++) {
                String line = lines.get(j);
                if (line.startsWith(">")) {
                    quoted.add(line.substring(1).trim());
                } else if (!quoted.isEmpty()) {
                    break;
                } else if (!line.isBlank()) {
                    break;
                }
            }
            if (!quoted.isEmpty()) {
                described.put(name, String.join(" ", quoted).trim());
            }
        }

        if (described.isEmpty()) {
            throw new IllegalStateException(
                "no tool descriptions were found in the contract. The document's shape "
                    + "changed and this parser did not; a probe that found nothing would "
                    + "have passed.");
        }
        return described;
    }

    /** {@code **`dispatch_take`**} on its own line, or null. */
    private static String boldCallName(String line) {
        String trimmed = line.trim();
        if (!trimmed.startsWith("**`") || !trimmed.endsWith("`**")) {
            return null;
        }
        return trimmed.substring(3, trimmed.length() - 3);
    }

    /**
     * The reason codes of the table in section 4.4.
     *
     * <p>Read from the first column of every table row whose cell is a single
     * inline-code span, plus the slash-separated pairs the table writes as one
     * row ({@code RECEIPT_MISSING / RECEIPT_WRONG}).
     */
    static List<String> declaredReasons() {
        List<String> reasons = new ArrayList<>();
        boolean inTable = false;

        for (String line : text().split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("| Reason |")) {
                inTable = true;
                continue;
            }
            if (inTable && !trimmed.startsWith("|")) {
                break;
            }
            if (!inTable || trimmed.startsWith("|---")) {
                continue;
            }

            String first = trimmed.split("\\|")[1].trim();
            for (String code : first.split("/")) {
                String bare = code.replace("`", "").trim();
                if (bare.matches("[A-Z_]+")) {
                    reasons.add(bare);
                }
            }
        }

        if (reasons.isEmpty()) {
            throw new IllegalStateException(
                "no reason codes were found in the contract's section 4.4.");
        }
        return List.copyOf(reasons);
    }

    /**
     * The message pattern of one reason, from the second column of its row.
     *
     * <p>Returns the cell as written, backticks and all. The probe compares the
     * SHAPE — which placeholders appear, in which order — rather than the exact
     * characters, because the document writes its patterns with angle brackets
     * ({@code <call>}) and the service fills braces ({@code {call}}). Comparing
     * characters would be comparing two notations for one thing.
     */
    static String patternOf(String reason) {
        boolean inTable = false;
        for (String line : text().split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("| Reason |")) {
                inTable = true;
                continue;
            }
            if (inTable && !trimmed.startsWith("|")) {
                break;
            }
            if (!inTable || trimmed.startsWith("|---")) {
                continue;
            }
            String[] cells = trimmed.split("\\|");
            if (cells.length > 2 && cells[1].contains(reason)) {
                return cells[2].trim();
            }
        }
        return null;
    }

    /**
     * The {@code next} table of section 6, as rows of
     * {@code state | caller | calls | waiting_for}.
     *
     * <p>The probe reads the third column and checks that the service offers
     * exactly those calls. That is A3's expected value, and it is the one place
     * where reading it from the contract matters most: a {@code next} list
     * derived from the service's own transition table would agree with the
     * service by construction.
     */
    static List<Row> nextTable() {
        List<Row> rows = new ArrayList<>();
        boolean inTable = false;

        for (String line : text().split("\n", -1)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("| S | C is |")) {
                inTable = true;
                continue;
            }
            if (inTable && !trimmed.startsWith("|")) {
                break;
            }
            if (!inTable || trimmed.startsWith("|---")) {
                continue;
            }

            String[] cells = trimmed.split("\\|", -1);
            if (cells.length < 5) {
                continue;
            }
            rows.add(new Row(clean(cells[1]), clean(cells[2]), calls(cells[3]),
                clean(cells[4])));
        }

        if (rows.isEmpty()) {
            throw new IllegalStateException(
                "no rows were found in the contract's section 6 table.");
        }
        return List.copyOf(rows);
    }

    /** One row of the {@code next} table. */
    record Row(String state, String caller, List<String> calls, String waitingFor) {
    }

    /** The call names in a cell, ignoring the parenthetical about bracket roots. */
    private static List<String> calls(String cell) {
        List<String> found = new ArrayList<>();
        java.util.regex.Matcher m =
            java.util.regex.Pattern.compile("`(dispatch_[a-z_]+)`").matcher(cell);
        while (m.find()) {
            found.add(m.group(1));
        }
        return List.copyOf(found);
    }

    private static String clean(String cell) {
        return cell.replace("`", "").replace("*", "").trim();
    }
}
