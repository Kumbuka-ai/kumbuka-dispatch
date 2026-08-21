package ai.kumbuka.dispatch.domain;

/**
 * Where an exchange lives inside its scope: {@code sprint/149.2}, or
 * {@code sprint/149.0a} for an addendum.
 *
 * <p>A record rather than a string, because every one of these four parts is
 * checked somewhere and a string would have to be re-parsed at each of them.
 * The display form is a rendering of this, never the identity.
 *
 * @param selector the declared bracket name
 * @param number   the bracket's number in its circle
 * @param sub      0 for the bracket itself, 1..n for its children
 * @param suffix   a single letter when this addresses an addendum, else null
 */
public record ExchangeAddress(String selector, int number, int sub, String suffix) {

    public ExchangeAddress {
        if (selector == null || selector.isBlank()) {
            throw new DispatchException(DispatchException.Reason.SELECTOR_NOT_DECLARED,
                "an address must name a selector");
        }
        if (suffix != null && !suffix.matches("[a-z]")) {
            throw new DispatchException(DispatchException.Reason.ADDENDUM_MALFORMED,
                "an addendum is addressed with a single lower-case letter, not '" + suffix
                    + "'. A regular sub-number would make it an ordinary child of the "
                    + "bracket, and an ordinary child carries the handover expectation "
                    + "and counts in the terminality check.");
        }
    }

    public static ExchangeAddress bracket(String selector, int number) {
        return new ExchangeAddress(selector, number, 0, null);
    }

    public static ExchangeAddress child(String selector, int number, int sub) {
        return new ExchangeAddress(selector, number, sub, null);
    }

    public boolean isAddendum() {
        return suffix != null;
    }

    @Override
    public String toString() {
        return selector + "/" + number + "." + sub + (suffix == null ? "" : suffix);
    }
}
