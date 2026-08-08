package za.co.fnb.dcre.pai.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import za.co.fnb.dcre.pai.data.model.CreditorRef;
import za.co.fnb.dcre.pai.data.repo.AccountRepo;
import za.co.fnb.dcre.pai.data.repo.PaiVerdictRepo;
import za.co.fnb.dcre.pai.data.repo.TxEntryRepo;
import za.co.fnb.dcre.pai.data.repo.UnknownCreditorRepo;

import java.util.List;
import java.util.UUID;

/**
 * Business tier: post-CTV account init for the ENDO Payments flow
 * (CRR -> CTV -> PAI -> CIR, SCRUM-69; DB-only, R-30).
 * For every spine transaction of the arrival: creditor account present in the
 * account master -> verdict EXISTS; absent -> the sighting is recorded in PAI's
 * own unknown_creditor relation -> verdict CREATED. Verdicts are immutable
 * (insert-if-absent), so a rerun never flips CREATED to EXISTS. An all-exist run
 * is a valid no-op (A-7).
 *
 * <p><b>SCRUM-107: the absent branch no longer writes the account master.</b> It used
 * to INSERT a hardcoded row there, so PAI answered "this account does not exist" by
 * making it exist, in a relation PAI does not own and PTV validates against. The
 * counts this returns are consequently now a pure function of the master's contents
 * rather than of how many times PAI has run: before the change, a first run reported
 * CREATED and the next reported EXISTS for the same account, because the first run had
 * put it there.
 *
 * <p>The verdict VALUES are left alone. {@code CREATED} is read by PRW and PRG as a
 * cross-service contract, and renaming it to match what the branch now does is a change
 * to their repositories, not this one. It is recorded in the SCRUM-107 report.
 *
 * <p>SCRUM-42 load fix: a 300k-tx arrival written in ONE serializable
 * transaction (~600k statements) is unrefreshable; CRDB aborts it with
 * RETRY_SERIALIZABLE "can't refresh txn spans" and a step-level retry just
 * re-runs the same doomed giant transaction. Verdicts therefore commit in
 * bounded sequence slices. Committed slices stand when a later slice fails:
 * every write is create-if-absent on the business identity, so a restart
 * (step-level retry or job relaunch) no-ops over them and resumes the rest.
 */
@Service
public class AccountInitService {

    /** Per-run tallies; existing + created = transactions seen. */
    public record InitCounts(int existing, int created) {
    }

    private record SliceCounts(int existing, int created, int read, int lastSequence) {
    }

    private final TxEntryRepo entries;
    private final AccountRepo accounts;
    private final UnknownCreditorRepo unknownCreditors;
    private final PaiVerdictRepo verdicts;
    private final TransactionTemplate sliceTx;
    private final int sliceSize;

    public AccountInitService(final TxEntryRepo entries, final AccountRepo accounts,
                              final UnknownCreditorRepo unknownCreditors,
                              final PaiVerdictRepo verdicts, final PlatformTransactionManager txManager,
                              @Value("${dcre.pai.verdict-slice-size:10000}") final int sliceSize) {
        this.entries = entries;
        this.accounts = accounts;
        this.unknownCreditors = unknownCreditors;
        this.verdicts = verdicts;
        // Each slice commits in its OWN transaction so a 300k-tx arrival
        // ratchets progress slice by slice; and a CRDB 40001 abort poisons the
        // surrounding transaction (25P02 on any further statement), so a retry
        // needs a fresh transaction per attempt.
        this.sliceTx = new TransactionTemplate(txManager);
        this.sliceTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.sliceSize = sliceSize;
    }

    public InitCounts init(final UUID arrivalId) {
        int existing = 0;
        int created = 0;
        int afterSequence = 0;
        while (true) {
            SliceCounts slice = initSlice(arrivalId, afterSequence);
            existing += slice.existing();
            created += slice.created();
            if (slice.read() < sliceSize) {
                return new InitCounts(existing, created);
            }
            afterSequence = slice.lastSequence();
        }
    }

    /** One slice = one committed unit: fresh REQUIRES_NEW tx per bounded-retry attempt. */
    private SliceCounts initSlice(final UUID arrivalId, final int afterSequence) {
        return CrdbRetry.run("verdict slice arrival=%s after=%d".formatted(arrivalId, afterSequence),
                () -> sliceTx.execute(status -> writeSlice(arrivalId, afterSequence)));
    }

    /**
     * Reads its own bounded keyset page INSIDE the slice transaction (a
     * whole-arrival read at 300k blew CRDB's sql memory budget on CRW live).
     */
    private SliceCounts writeSlice(final UUID arrivalId, final int afterSequence) {
        List<CreditorRef> refs = entries.findCreditorRefsSlice(arrivalId, afterSequence, sliceSize);
        int existing = 0;
        int created = 0;
        for (CreditorRef ref : refs) {
            if (accounts.existsByAccountNumber(ref.creditorAccount())) {
                verdicts.insertIfAbsent(arrivalId, ref.sequence(), "EXISTS");
                existing++;
            } else {
                unknownCreditors.recordIfAbsent(arrivalId, ref.creditorAccount());
                verdicts.insertIfAbsent(arrivalId, ref.sequence(), "CREATED");
                created++;
            }
        }
        int lastSequence = refs.isEmpty() ? afterSequence : refs.getLast().sequence();
        return new SliceCounts(existing, created, refs.size(), lastSequence);
    }
}
