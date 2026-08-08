package za.co.fnb.dcre.mrv.domain;

/**
 * One MANDATES-shape account, which is the only projection MRV materialises.
 *
 * <p>Four columns, and that is the truth of the data rather than a subset chosen for
 * convenience: a MANDATES row carries no branch code, no balance and no UCN, exactly as a
 * COLLECTIONS row carries no account type. The two projections are never unioned and
 * neither is widened to admit the other, because the whole point of per-context
 * materialisation is that {@code dcre_man.account} keeps the NOT NULLs the mandates family
 * requires instead of relaxing them to a nullable union.
 *
 * <p>{@code productCode} is the one nullable column of the target table and is the one
 * field here that may be null.
 */
public record AccountReferenceRow(String accountNumber, String accountTypeCode,
                                  String productCode, String status) {
}
