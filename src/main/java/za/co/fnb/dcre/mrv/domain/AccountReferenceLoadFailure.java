package za.co.fnb.dcre.mrv.domain;

/**
 * Every refusal on the account reference load path. One type, always carrying a message
 * that names the offending value and both sides of whatever comparison failed, because a
 * loader that reports "an exception was thrown" leaves the operator to guess which of the
 * seven gates refused.
 *
 * <p>There is no recoverable variant on purpose. A load either applies the whole
 * projection or leaves the table exactly as it was, so every gate here is terminal.
 */
public class AccountReferenceLoadFailure extends RuntimeException {

    public AccountReferenceLoadFailure(final String message) {
        super(message);
    }

    public AccountReferenceLoadFailure(final String message, final Throwable cause) {
        super(message, cause);
    }
}
