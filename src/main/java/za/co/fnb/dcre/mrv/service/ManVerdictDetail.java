package za.co.fnb.dcre.mrv.service;

import za.co.fnb.dcre.mrv.service.VerdictChain.Entry;
import za.co.fnb.dcre.platform.model.MandateOutcome;

/**
 * Short human diagnosis persisted in man_validation_log.detail alongside the
 * machine outcome code (R-21: the code is the contract, the detail is for
 * operators + the MIR NACK narrative). PASS carries no detail.
 */
final class ManVerdictDetail {

    private ManVerdictDetail() {
    }

    static String of(final MandateOutcome outcome, final Entry entry) {
        return switch (outcome) {
            case PASS -> null;
            case FAIL_STRUCTURE -> "malformed record: action='%s' recordType='%s' ref='%s'"
                    .formatted(entry.actionCode(), entry.recordType(), entry.mandateRef());
            case FAIL_DUPLICATE_REF -> "duplicate mandate_ref '%s' (in-file or prior registration)"
                    .formatted(entry.mandateRef());
            case FAIL_ACCOUNT_NOT_FOUND -> "debtor account '%s' absent from dcre_man account master"
                    .formatted(entry.debtorAccount());
            case FAIL_ACCOUNT_TYPE_DISALLOWED -> "account '%s' type does not allow mandates (AG01)"
                    .formatted(entry.debtorAccount());
            case FAIL_CONTRACT_FORMAT -> "contract_ref '%s' fails the format rule"
                    .formatted(entry.contractRef());
            case FAIL_AMEND_UNKNOWN_REF -> "AMEND targets unknown mandate_ref '%s'".formatted(entry.mandateRef());
            case FAIL_CANCEL_UNKNOWN_REF -> "CANCEL targets unknown mandate_ref '%s'".formatted(entry.mandateRef());
            default -> outcome.name();
        };
    }
}
