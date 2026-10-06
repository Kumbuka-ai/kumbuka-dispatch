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
 * The contract copy, read as the source of the expected texts.
 *
 * <p>{@code contract/assistant-surface.md} is written by hand from TAR-0004
 * and the concept document, and the descriptions and refusal patterns the
 * declaration carries are held against it. The expectation is never read out
 * of the declaration it checks: an expectation written beside the code it
 * checks is green for ever and asserts nothing.
 *
 * <p>The parser is simple and strict: it finds the blockquote under each bold
 * call name, the blockquote under a named heading, and the rows of the reason
 * table. Where the document's shape changes, it throws rather than silently
 * finding nothing.
 */
public final class Contract {

    private static final String RESOURCE = "/contract/assistant-surface.md";

    private Contract() {
    }

    /** The whole document. */
    public static String text() {
        try (InputStream in = Contract.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(RESOURCE + " is not on the test classpath.");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Every call the document describes, with its description, whitespace
     * collapsed: the document's line breaks are its layout.
     */
    public static Map<String, String> describedCalls() {
        Map<String, String> described = new LinkedHashMap<>();
        List<String> lines = List.of(text().split("\n", -1));
        for (int i = 0; i < lines.size(); i++) {
            String name = boldCallName(lines.get(i));
            if (name != null) {
                described.put(name, quotedFrom(lines, i + 1));
            }
        }
        if (described.isEmpty()) {
            throw new IllegalStateException("no call descriptions were found in the contract.");
        }
        return described;
    }

    /** The blockquote that follows a heading, whitespace collapsed. */
    public static String quotedUnder(String heading) {
        List<String> lines = List.of(text().split("\n", -1));
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(heading)) {
                return quotedFrom(lines, i + 1);
            }
        }
        throw new IllegalStateException("no heading '" + heading + "' in the contract.");
    }

    /** The reasons of the table, in its order. */
    public static List<String> declaredReasons() {
        return List.copyOf(table().keySet());
    }

    /** One reason's message pattern, as the table writes it. */
    public static String patternOf(String reason) {
        String[] row = table().get(reason);
        return row == null ? null : row[0];
    }

    /** One reason's remedy, as the table writes it. */
    public static String remedyOf(String reason) {
        String[] row = table().get(reason);
        return row == null ? null : row[1];
    }

    /** Collapses every run of whitespace to one space, for comparing prose. */
    public static String collapsed(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    private static Map<String, String[]> table() {
        Map<String, String[]> rows = new LinkedHashMap<>();
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
            String[] cells = trimmed.split("\\|", -1);
            rows.put(unquote(cells[1]), new String[] {unquote(cells[2]), cells[3].trim()});
        }
        if (rows.isEmpty()) {
            throw new IllegalStateException("no reasons were found in the contract's table.");
        }
        return rows;
    }

    private static String boldCallName(String line) {
        String trimmed = line.trim();
        if (!trimmed.startsWith("**`")) {
            return null;
        }
        int end = trimmed.indexOf("`**");
        return end < 0 ? null : trimmed.substring(3, end);
    }

    private static String quotedFrom(List<String> lines, int start) {
        List<String> quoted = new ArrayList<>();
        for (int j = start; j < lines.size(); j++) {
            String line = lines.get(j);
            if (line.startsWith(">")) {
                quoted.add(line.substring(1).trim());
            } else if (!quoted.isEmpty() || !line.isBlank()) {
                break;
            }
        }
        if (quoted.isEmpty()) {
            throw new IllegalStateException("a heading in the contract has no blockquote.");
        }
        return collapsed(String.join(" ", quoted));
    }

    private static String unquote(String cell) {
        String trimmed = cell.trim();
        return trimmed.startsWith("`") && trimmed.endsWith("`") && trimmed.length() > 1
            ? trimmed.substring(1, trimmed.length() - 1)
            : trimmed;
    }
}
