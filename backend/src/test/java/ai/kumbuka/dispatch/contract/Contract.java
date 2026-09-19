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
public final class Contract {

    private static final String RESOURCE = "/contract/assistant-surface.md";

    private Contract() {
    }

    /** The whole document, as it was copied. */
    public static String text() {
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
    public static Map<String, String> describedCalls() {
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
    public static List<String> declaredReasons() {
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
    public static String patternOf(String reason) {
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
    public static List<Row> nextTable() {
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
            rows.add(new Row(clean(cells[1]), clean(cells[2]), cells[3].trim(),
                clean(cells[4])));
        }

        if (rows.isEmpty()) {
            throw new IllegalStateException(
                "no rows were found in the contract's section 6 table.");
        }
        return List.copyOf(rows);
    }

    /**
     * One row of the {@code next} table, with its third cell kept as written.
     *
     * <p>The cell is prose around a list, and the prose carries two conditions
     * the probe has to honour: a call the row offers only on a bracket root,
     * and a call the row offers only while every child of the bracket is
     * terminal. Extracting the call names and discarding the rest — which is
     * what the predecessor did — throws away exactly the part that decides
     * which of the four cases a given exchange is in.
     *
     * @param cell the third column, verbatim, backticks and all
     */
    public record Row(String state, String caller, String cell, String waitingFor) {

        /** Every call the cell names, in the order it names them. */
        List<String> calls() {
            return callsIn(cell);
        }

        /**
         * What the row offers on an ordinary exchange — one that is not a
         * bracket root.
         *
         * <p>The part before the row's {@code -- on a root:} clause, which is
         * where the contract writes the root's deviation. A call carrying only
         * a {@code (root: ...)} parenthetical is still offered here: the
         * parenthetical narrows the ROOT case and says nothing about a child.
         */
        List<String> atChild() {
            return callsIn(beforeRootClause());
        }

        /**
         * What the row offers on a bracket root.
         *
         * <p>Three readings of the contract's own wording, in order: the
         * child's list is the starting point; {@code instead of the first two}
         * removes them; and every call the root clause names is added. Then
         * each call that the cell qualifies with "only if every child is
         * terminal" survives only when they are.
         *
         * @param childrenFinished whether every exchange of the bracket is
         *                         terminal
         */
        List<String> atRoot(boolean childrenFinished) {
            List<String> offered = new ArrayList<>(atChild());
            String rootClause = afterRootClause();

            if (rootClause.contains("instead of the first two")) {
                offered = new ArrayList<>(offered.subList(Math.min(2, offered.size()),
                    offered.size()));
            }
            for (String call : callsIn(rootClause)) {
                if (!offered.contains(call)) {
                    offered.add(call);
                }
            }
            if (!childrenFinished) {
                offered.removeIf(call -> conditionedOnChildren(call));
            }
            return List.copyOf(offered);
        }

        /** Whether this row says nothing is open to the caller. */
        boolean offersNothing() {
            return calls().isEmpty();
        }

        private String beforeRootClause() {
            int at = cell.indexOf("-- on a root:");
            return at < 0 ? cell : cell.substring(0, at);
        }

        private String afterRootClause() {
            int at = cell.indexOf("-- on a root:");
            return at < 0 ? "" : cell.substring(at);
        }

        /**
         * Whether the cell makes this call conditional on the bracket's
         * children.
         *
         * <p>The condition is written directly after the call it qualifies —
         * as a parenthetical or as a trailing clause — so the text between one
         * call and the next is the text that belongs to it. Reading the whole
         * cell instead would make every call of a row conditional as soon as
         * one of them was.
         */
        private boolean conditionedOnChildren(String call) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("`" + call + "`(.*?)(?=`dispatch_|$)", java.util.regex.Pattern.DOTALL)
                .matcher(cell);
            while (m.find()) {
                if (m.group(1).contains("every child is terminal")) {
                    return true;
                }
            }
            return false;
        }
    }

    /** The call names in a stretch of the table's prose, in order. */
    private static List<String> callsIn(String text) {
        List<String> found = new ArrayList<>();
        java.util.regex.Matcher m =
            java.util.regex.Pattern.compile("`(dispatch_[a-z_]+)`").matcher(text);
        while (m.find()) {
            if (!found.contains(m.group(1))) {
                found.add(m.group(1));
            }
        }
        return List.copyOf(found);
    }

    private static String clean(String cell) {
        return cell.replace("`", "").replace("*", "").trim();
    }
}
