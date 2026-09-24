package com.supremebilliardshall.billiards_hall_system.service.impl;

import com.supremebilliardshall.billiards_hall_system.dto.voucher.RedeemVoucherRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.voucher.VoucherBatchRequestDTO;
import com.supremebilliardshall.billiards_hall_system.dto.voucher.VoucherBatchResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.voucher.VoucherRedemptionResponseDTO;
import com.supremebilliardshall.billiards_hall_system.dto.voucher.VoucherResponseDTO;
import com.supremebilliardshall.billiards_hall_system.entity.AppUser;
import com.supremebilliardshall.billiards_hall_system.entity.Bill;
import com.supremebilliardshall.billiards_hall_system.entity.BillLine;
import com.supremebilliardshall.billiards_hall_system.entity.BillLineKind;
import com.supremebilliardshall.billiards_hall_system.entity.BillStatus;
import com.supremebilliardshall.billiards_hall_system.entity.PoolTable;
import com.supremebilliardshall.billiards_hall_system.entity.RateOverrideKind;
import com.supremebilliardshall.billiards_hall_system.entity.TableSession;
import com.supremebilliardshall.billiards_hall_system.entity.Voucher;
import com.supremebilliardshall.billiards_hall_system.entity.VoucherBatch;
import com.supremebilliardshall.billiards_hall_system.exception.BusinessRuleException;
import com.supremebilliardshall.billiards_hall_system.exception.ResourceNotFoundException;
import com.supremebilliardshall.billiards_hall_system.repository.AppUserRepository;
import com.supremebilliardshall.billiards_hall_system.repository.BillLineRepository;
import com.supremebilliardshall.billiards_hall_system.repository.BillRepository;
import com.supremebilliardshall.billiards_hall_system.repository.BranchRepository;
import com.supremebilliardshall.billiards_hall_system.repository.PoolTableRepository;
import com.supremebilliardshall.billiards_hall_system.repository.TableSessionRepository;
import com.supremebilliardshall.billiards_hall_system.repository.VoucherBatchRepository;
import com.supremebilliardshall.billiards_hall_system.repository.VoucherRepository;
import com.supremebilliardshall.billiards_hall_system.security.BranchContext;
import com.supremebilliardshall.billiards_hall_system.service.AuditService;
import com.supremebilliardshall.billiards_hall_system.service.BillService;
import com.supremebilliardshall.billiards_hall_system.service.VoucherService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class VoucherServiceImpl implements VoucherService {

    // "31 October 2026" -- how the expiry is said to a customer, not how it is stored.
    private static final DateTimeFormatter EXPIRY_FORMAT =
            DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH);

    // A code repeating inside one batch is a one-in-a-million event (see VoucherCodes), so this
    // is a bound on a loop that should never run twice, not a retry policy. Exhausting it means
    // something is wrong with the generator rather than that the hall got unlucky.
    private static final int MAX_CODE_ATTEMPTS = 20;

    private static final int MINUTES_PER_HOUR = 60;

    private final VoucherRepository voucherRepository;
    private final VoucherBatchRepository voucherBatchRepository;
    private final BillRepository billRepository;
    private final BillLineRepository billLineRepository;
    private final TableSessionRepository tableSessionRepository;
    private final PoolTableRepository poolTableRepository;
    private final AppUserRepository appUserRepository;
    private final BranchRepository branchRepository;
    private final BillService billService;
    private final AuditService auditService;
    private final BranchContext branchContext;

    public VoucherServiceImpl(VoucherRepository voucherRepository,
                              VoucherBatchRepository voucherBatchRepository,
                              BillRepository billRepository,
                              BillLineRepository billLineRepository,
                              TableSessionRepository tableSessionRepository,
                              PoolTableRepository poolTableRepository,
                              AppUserRepository appUserRepository,
                              BranchRepository branchRepository,
                              BillService billService,
                              AuditService auditService,
                              BranchContext branchContext) {
        this.voucherRepository = voucherRepository;
        this.voucherBatchRepository = voucherBatchRepository;
        this.billRepository = billRepository;
        this.billLineRepository = billLineRepository;
        this.tableSessionRepository = tableSessionRepository;
        this.poolTableRepository = poolTableRepository;
        this.appUserRepository = appUserRepository;
        this.branchRepository = branchRepository;
        this.billService = billService;
        this.auditService = auditService;
        this.branchContext = branchContext;
    }


    /*
     * Fifty codes in one transaction, returned so the owner can print them.
     *
     * All or nothing, deliberately: a batch that half-generated would leave the owner holding a
     * list of forty-one codes for a giveaway advertised as fifty, with no way to tell which
     * nine are missing. There is no partial success to report here.
     */
    @Override
    @Transactional
    public VoucherBatchResponseDTO createBatch(VoucherBatchRequestDTO voucherBatchRequestDTO) {
        int minutes = minutesFromHours(voucherBatchRequestDTO.getHours());
        UUID actorId = branchContext.getCurrentUserId();

        VoucherBatch batch = new VoucherBatch();
        batch.setBranchId(branchContext.getCurrentBranchId());
        batch.setMinutes(minutes);
        batch.setQuantity(voucherBatchRequestDTO.getQuantity());
        batch.setExpiresOn(voucherBatchRequestDTO.getExpiresOn());
        batch.setNote(voucherBatchRequestDTO.getNote() == null
                ? null : voucherBatchRequestDTO.getNote().trim());
        batch.setCreatedBy(actorId);
        VoucherBatch savedBatch = voucherBatchRepository.saveAndFlush(batch);

        List<Voucher> vouchers = new ArrayList<>();
        for (int i = 0; i < voucherBatchRequestDTO.getQuantity(); i++) {
            vouchers.add(generateVoucher(savedBatch));
        }

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("minutes", minutes);
        after.put("quantity", savedBatch.getQuantity());
        after.put("expiresOn", savedBatch.getExpiresOn());
        // The codes themselves are NOT in the audit row. The audit screen is readable by the
        // owner alone today, but an audit log is the one table that is never pruned, and
        // putting live giveaway codes in it makes every future reader of that screen able to
        // spend them.
        auditService.record("VOUCHER_BATCH_CREATED", "voucher_batch", savedBatch.getId(),
                null, after, savedBatch.getNote());

        VoucherBatchResponseDTO responseDto = toResponseDto(savedBatch,
                savedBatch.getQuantity(), 0, 0, savedBatch.getQuantity());
        responseDto.setCodes(vouchers.stream()
                .map(voucher -> toResponseDto(voucher, currentBusinessDate()))
                .toList());
        return responseDto;
    }

    @Override
    @Transactional(readOnly = true)
    public List<VoucherBatchResponseDTO> getBatches() {
        LocalDate today = currentBusinessDate();
        Map<UUID, VoucherBatchRepository.BatchCountsProjection> counts = new HashMap<>();
        for (VoucherBatchRepository.BatchCountsProjection row : voucherBatchRepository.countsByBatch(today)) {
            counts.put(row.getBatchId(), row);
        }

        return voucherBatchRepository.findAllNewestFirst().stream()
                .map(batch -> {
                    VoucherBatchRepository.BatchCountsProjection row = counts.get(batch.getId());
                    return row == null
                            ? toResponseDto(batch, 0, 0, 0, 0)
                            : toResponseDto(batch, row.getIssued(), row.getRedeemed(),
                                            row.getExpired(), row.getOutstanding());
                })
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<VoucherResponseDTO> getVouchers(UUID batchId, String status) {
        LocalDate today = currentBusinessDate();
        String wanted = status == null || status.isBlank() ? null : status.trim().toUpperCase();
        return voucherRepository.search(batchId, wanted, today).stream()
                .map(voucher -> toResponseDto(voucher, today))
                .toList();
    }


    /*
     * Spending a code against a bill.
     *
     * The refusals come first and the write comes last, in that order for a reason: every check
     * below is read-only, so a bill that fails one of them leaves the code untouched and still
     * spendable. Only when the redemption is certain to be legal does the conditional UPDATE
     * run -- and that update is where single use is decided, not here.
     */
    @Override
    @Transactional
    public VoucherRedemptionResponseDTO redeem(UUID billId, RedeemVoucherRequestDTO redeemVoucherRequestDTO) {
        Bill bill = requireBill(billId);
        LocalDate today = currentBusinessDate();

        if (bill.getStatus() != BillStatus.OPEN) {
            throw new BusinessRuleException("Bill is already " + bill.getStatus()
                    + "; a voucher can only be used on a bill that is still open.");
        }
        // One voucher per bill. Not a database constraint because bill.voucher_id is a single
        // column and cannot hold two -- this is the message that explains it.
        if (bill.getVoucherId() != null) {
            throw new BusinessRuleException("This bill already has voucher "
                    + VoucherCodes.display(codeOf(bill.getVoucherId()))
                    + " on it. Remove that one before entering another.");
        }

        String code = VoucherCodes.normalise(redeemVoucherRequestDTO.getCode());
        // A code from another branch is simply absent: findByCode is branch-scoped, and saying
        // "that belongs to Las Piñas" would tell one hall about another's giveaway.
        Voucher voucher = code == null
                ? null
                : voucherRepository.findByCode(code).orElse(null);
        if (voucher == null) {
            throw new ResourceNotFoundException("Voucher",
                    VoucherCodes.display(code == null ? redeemVoucherRequestDTO.getCode() : code));
        }

        // Serialize redemption with setup archive/delete. A code cannot become used between
        // the lifecycle eligibility check and removal of its batch.
        VoucherBatch batch = voucherBatchRepository.findByIdForUpdate(voucher.getBatchId())
                .orElseThrow(() -> new ResourceNotFoundException("Voucher batch", voucher.getBatchId()));
        if (batch.getArchivedAt() != null) {
            throw new BusinessRuleException("That voucher batch is archived. Ask an administrator to restore it first.");
        }

        /*
         * Expiry is judged against the BUSINESS date, not the calendar date.
         *
         * A code expiring 31 October is good for the whole of the night of the 31st, which runs
         * to 05:00 on 1 November. Comparing against LocalDate.now() would refuse it at midnight
         * with the customer still at the table, halfway through the session it was meant to
         * pay for -- and they would be right to argue.
         */
        if (voucher.getExpiresOn().isBefore(today)) {
            throw new BusinessRuleException("That voucher expired on "
                    + EXPIRY_FORMAT.format(voucher.getExpiresOn()) + ".");
        }
        // Read before the write as a courtesy, so the common case -- the same customer trying
        // the same code twice -- gets the message naming the receipt. It is NOT the guarantee:
        // the conditional update below is, and it is what a second till loses against.
        if (voucher.getRedeemedAt() != null) {
            throw alreadyRedeemed(voucher);
        }

        refuseNonStandardPricing(bill);

        List<BillLine> timeLines = liveTimeLines(bill.getId());
        if (timeLines.isEmpty()) {
            throw new BusinessRuleException("There is no table time on this bill to cover. "
                    + "Close the table session first, then enter the voucher.");
        }

        Coverage coverage = cover(timeLines, voucher.getMinutes());
        if (coverage.amount().signum() <= 0) {
            throw new BusinessRuleException("There is no table time on this bill to cover.");
        }

        /*
         * A voucher and a discount can sit on one bill, but not past zero.
         *
         * The discount was agreed against what the bill came to at the time; the voucher then
         * takes off a further slice of the same subtotal. If the two together exceed it the
         * refusal names the order that works, because there is one -- the voucher covers a
         * fixed quantity of time whatever else happens, so it goes on first and the discount is
         * agreed on what is left.
         */
        BigDecimal subtotal = subtotalOf(bill.getId());
        BigDecimal reductions = bill.getDiscountAmount().add(coverage.amount());
        if (reductions.compareTo(subtotal) > 0) {
            throw new BusinessRuleException("This bill is already discounted by "
                    + bill.getDiscountAmount().toPlainString() + ", and the voucher would cover a further "
                    + coverage.amount().toPlainString() + " of the " + subtotal.toPlainString()
                    + " on it. Remove the discount, enter the voucher, then agree the discount "
                    + "on what is left.");
        }

        UUID actorId = branchContext.getCurrentUserId();

        /*
         * THE WRITE THAT DECIDES IT, and the first write of this transaction.
         *
         * Two tills that reach here at the same moment have both passed every check above --
         * neither saw a redeemed_at, both bills are open. This UPDATE is where they are
         * separated: it matches on redeemed_at IS NULL, so exactly one of them changes a row
         * and the other changes none. Zero rows affected IS the already-redeemed refusal, and
         * it is re-read to name the receipt the winner put it on.
         *
         * It runs before the bill is touched precisely so that the persistence-context clear it
         * carries has nothing dirty to detach; the bill is re-read below.
         */
        int redeemed = voucherRepository.redeem(voucher.getId(), bill.getId(), actorId,
                OffsetDateTime.now());
        if (redeemed == 0) {
            throw alreadyRedeemed(voucherRepository.findById(voucher.getId())
                    .orElseThrow(() -> new ResourceNotFoundException("Voucher", voucher.getId())));
        }

        Bill live = requireBill(billId);
        live.setVoucherId(voucher.getId());
        live.setVoucherAmount(coverage.amount());
        live.setVoucherMinutesCovered(coverage.minutes());
        // Bumps @Version, which is what a checkout tab still holding the pre-voucher total
        // collides with. That collision is the point.
        billRepository.saveAndFlush(live);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("code", voucher.getCode());
        after.put("voucherMinutes", voucher.getMinutes());
        after.put("minutesCovered", coverage.minutes());
        after.put("voucherAmount", coverage.amount());
        auditService.record("VOUCHER_REDEEMED", "bill", live.getId(),
                null, after, VoucherCodes.display(voucher.getCode()));

        return toRedemptionResponseDto(live, voucher, coverage);
    }

    /*
     * Putting a code back, for one typed against the wrong bill.
     *
     * The mirror of redeem in every respect including the conditional update: the release
     * matches on redeemed_bill_id, so a till holding a different bill cannot free a code that
     * is not on it, and a second release finds nothing to do.
     */
    @Override
    @Transactional
    public VoucherRedemptionResponseDTO release(UUID billId) {
        Bill bill = requireBill(billId);

        if (bill.getStatus() != BillStatus.OPEN) {
            throw new BusinessRuleException("Bill is already " + bill.getStatus()
                    + "; the voucher on it can no longer be removed.");
        }
        if (bill.getVoucherId() == null) {
            throw new BusinessRuleException("There is no voucher on this bill to remove.");
        }

        Voucher voucher = voucherRepository.findById(bill.getVoucherId())
                .orElseThrow(() -> new ResourceNotFoundException("Voucher", bill.getVoucherId()));
        Integer minutesCovered = bill.getVoucherMinutesCovered();
        BigDecimal amount = bill.getVoucherAmount();

        int released = voucherRepository.release(voucher.getId(), bill.getId());
        if (released == 0) {
            throw new BusinessRuleException("That voucher is no longer recorded against this bill.");
        }

        Bill live = requireBill(billId);
        // All three together, back to the shape a bill that never had a voucher has:
        // bill_voucher_together_chk admits no half-cleared row.
        live.setVoucherId(null);
        live.setVoucherAmount(BigDecimal.ZERO);
        live.setVoucherMinutesCovered(null);
        billRepository.saveAndFlush(live);

        Map<String, Object> before = new LinkedHashMap<>();
        before.put("code", voucher.getCode());
        before.put("minutesCovered", minutesCovered);
        before.put("voucherAmount", amount);
        // The code is on the BEFORE side, because releasing has no code of its own and the
        // question the owner will ask of this row is which voucher went back in the pot.
        auditService.record("VOUCHER_RELEASED", "bill", live.getId(),
                before, null, VoucherCodes.display(voucher.getCode()));

        Voucher restored = voucherRepository.findById(voucher.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Voucher", voucher.getId()));
        return toRedemptionResponseDto(live, restored,
                new Coverage(0, BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY)));
    }


    // ---- the arithmetic ------------------------------------------------------------------

    // What the voucher reached: the minutes it actually consumed and what they were worth.
    private record Coverage(int minutes, BigDecimal amount) {
    }

    /*
     * Walking the bill's TIME lines, consuming minutes until the voucher runs out.
     *
     * The same shape SessionServiceImpl.overrideBilledMinutes distributes a reduction in, and
     * for the same reason: one session can hold several TIME lines -- one per segment, each
     * with its own snapshotted rate -- and a bill can hold several sessions. Taking the minutes
     * in seq order spends the voucher on the earliest time played, which is the order a
     * customer would expect if anyone ever asked.
     *
     * A FULLY covered line contributes its lineTotal VERBATIM rather than lineTotal x 1. That
     * is what makes a wholly covered bill land on exactly 0.00: multiplying and re-rounding a
     * figure that is already exact is how a centavo of drift appears in production with nothing
     * to explain it. A partly covered line is prorated on its own snapshotted total, never by
     * joining back to a rate -- the line is the record of what that time cost.
     */
    private Coverage cover(List<BillLine> timeLines, int voucherMinutes) {
        int remaining = voucherMinutes;
        int covered = 0;
        BigDecimal amount = BigDecimal.ZERO.setScale(2, RoundingMode.UNNECESSARY);

        for (BillLine line : timeLines) {
            if (remaining <= 0) {
                break;
            }
            int lineMinutes = line.getBilledMinutes() == null ? 0 : line.getBilledMinutes();
            BigDecimal lineTotal = line.getLineTotal() == null ? BigDecimal.ZERO : line.getLineTotal();
            if (lineMinutes <= 0 || lineTotal.signum() <= 0) {
                continue;
            }

            int take = Math.min(remaining, lineMinutes);
            BigDecimal share = take == lineMinutes
                    ? lineTotal
                    : lineTotal.multiply(BigDecimal.valueOf(take))
                               .divide(BigDecimal.valueOf(lineMinutes), 2, RoundingMode.HALF_UP);

            amount = amount.add(share);
            covered += take;
            remaining -= take;
        }

        return new Coverage(covered, amount);
    }

    /*
     * One pricing story per session, the same rule the flat rate and the overrides already
     * follow -- and the message names which, because "this session is not eligible" leaves the
     * cashier holding up a queue with nothing to tell the customer.
     *
     * A voucher is standard-rate time expressed as minutes. Against a session already priced
     * some other way it has no meaning: on a flat fee there are no minutes to cover, and on a
     * promo or a friend rate the hall would be giving the same time away twice.
     */
    private void refuseNonStandardPricing(Bill bill) {
        for (TableSession session : tableSessionRepository.findByBillId(bill.getId())) {
            String pricing = null;
            if (session.getFlatAmount() != null) {
                pricing = "a flat fee of " + session.getFlatAmount().toPlainString();
            } else if (session.getRateOverrideKind() == RateOverrideKind.PROMO) {
                pricing = "a promo rate";
            } else if (session.getRateOverridePerMinute() != null) {
                pricing = "a friend rate";
            }
            if (pricing != null) {
                throw new BusinessRuleException(tableNameOf(session.getPoolTableId())
                        + " is already on " + pricing + ". A voucher covers standard table time "
                        + "only — one pricing story per session.");
            }
        }
    }

    private List<BillLine> liveTimeLines(UUID billId) {
        return billLineRepository.findByBillId(billId).stream()
                .filter(line -> line.getVoidedAt() == null)
                .filter(line -> line.getLineKind() == BillLineKind.TIME)
                .sorted(Comparator.comparing(BillLine::getSeq))
                .toList();
    }

    // The live subtotal, summed from the lines. bill.subtotal_time and subtotal_items are still
    // zero at this point -- nothing writes them until checkout finalises the bill -- so reading
    // them off the row would compare against 0.00. Same reason applyDiscount sums its own.
    private BigDecimal subtotalOf(UUID billId) {
        return billLineRepository.findByBillId(billId).stream()
                .filter(line -> line.getVoidedAt() == null)
                .map(line -> line.getLineTotal() == null ? BigDecimal.ZERO : line.getLineTotal())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BusinessRuleException alreadyRedeemed(Voucher voucher) {
        Long receiptNo = voucher.getRedeemedBillId() == null
                ? null
                : billRepository.findById(voucher.getRedeemedBillId())
                        .map(Bill::getReceiptNo)
                        .orElse(null);
        // The receipt number is what the cashier can actually look up. A bill that has not been
        // through checkout yet has none, so that case says where it is instead -- an open bill
        // on the floor is findable, and telling them "already used" with nowhere to look is how
        // a customer gets accused of trying it on.
        String where = receiptNo != null
                ? " against receipt #" + receiptNo
                : " and is on a bill still open at the counter";
        return new BusinessRuleException("That voucher was already redeemed on "
                + EXPIRY_FORMAT.format(voucher.getRedeemedAt().toLocalDate()) + where + ".");
    }


    // ---- generation ----------------------------------------------------------------------

    /*
     * The owner types hours; minutes are what is stored.
     *
     * A fractional minute is refused rather than rounded: 0.71 hours is 42.6 minutes, and a
     * voucher worth two thirds of a minute more than the owner meant is a figure nobody can
     * account for later. The refusal names the two nearest whole answers so the correction is
     * one keystroke.
     */
    private int minutesFromHours(BigDecimal hours) {
        BigDecimal minutes = hours.multiply(BigDecimal.valueOf(MINUTES_PER_HOUR));
        if (minutes.stripTrailingZeros().scale() > 0) {
            int down = minutes.setScale(0, RoundingMode.FLOOR).intValue();
            throw new BusinessRuleException(hours.toPlainString() + " hours is "
                    + minutes.stripTrailingZeros().toPlainString() + " minutes, which is not a whole "
                    + "number of minutes. Use " + down + " or " + (down + 1) + " minutes' worth — "
                    + "vouchers are counted in minutes, like everything else here.");
        }
        return minutes.intValue();
    }

    /*
     * One code, retried on the vanishingly rare collision.
     *
     * saveAndFlush rather than save, so the unique index answers now: batched to the end of the
     * transaction, a duplicate would surface as a flush failure on some later unrelated
     * statement, with nothing naming the voucher that caused it. The retry is what makes
     * VoucherCodes' arithmetic a question of how often rather than whether.
     */
    private Voucher generateVoucher(VoucherBatch batch) {
        for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
            String code = VoucherCodes.generate();
            if (voucherRepository.findByCode(code).isPresent()) {
                continue;
            }
            Voucher voucher = new Voucher();
            voucher.setBranchId(batch.getBranchId());
            voucher.setBatchId(batch.getId());
            voucher.setCode(code);
            // Snapshotted off the batch here and never read from it again.
            voucher.setMinutes(batch.getMinutes());
            voucher.setExpiresOn(batch.getExpiresOn());
            return voucherRepository.saveAndFlush(voucher);
        }
        throw new BusinessRuleException("Could not generate a unique voucher code after "
                + MAX_CODE_ATTEMPTS + " attempts. Nothing has been created.");
    }


    // ---- mapping -------------------------------------------------------------------------

    private VoucherBatchResponseDTO toResponseDto(VoucherBatch batch, long issued, long redeemed,
                                                  long expired, long outstanding) {
        VoucherBatchResponseDTO responseDto = new VoucherBatchResponseDTO();
        responseDto.setId(batch.getId());
        responseDto.setMinutes(batch.getMinutes());
        responseDto.setHoursLabel(hoursLabel(batch.getMinutes()));
        responseDto.setQuantity(batch.getQuantity());
        responseDto.setExpiresOn(batch.getExpiresOn());
        responseDto.setNote(batch.getNote());
        responseDto.setCreatedByUsername(usernameOf(batch.getCreatedBy()));
        responseDto.setCreatedAt(batch.getCreatedAt());
        responseDto.setIssued(issued);
        responseDto.setRedeemed(redeemed);
        responseDto.setExpired(expired);
        responseDto.setOutstanding(outstanding);
        return responseDto;
    }

    private VoucherResponseDTO toResponseDto(Voucher voucher, LocalDate today) {
        VoucherResponseDTO responseDto = new VoucherResponseDTO();
        responseDto.setId(voucher.getId());
        responseDto.setBatchId(voucher.getBatchId());
        responseDto.setCode(VoucherCodes.display(voucher.getCode()));
        responseDto.setMinutes(voucher.getMinutes());
        responseDto.setExpiresOn(voucher.getExpiresOn());
        responseDto.setStatus(statusOf(voucher, today));
        responseDto.setRedeemedAt(voucher.getRedeemedAt());
        responseDto.setRedeemedByUsername(usernameOf(voucher.getRedeemedBy()));
        responseDto.setRedeemedBillId(voucher.getRedeemedBillId());
        responseDto.setRedeemedReceiptNo(voucher.getRedeemedBillId() == null
                ? null
                : billRepository.findById(voucher.getRedeemedBillId())
                        .map(Bill::getReceiptNo)
                        .orElse(null));
        return responseDto;
    }

    private VoucherRedemptionResponseDTO toRedemptionResponseDto(Bill bill, Voucher voucher,
                                                                 Coverage coverage) {
        return new VoucherRedemptionResponseDTO(
                billService.getBill(bill.getId()),
                VoucherCodes.display(voucher.getCode()),
                voucher.getMinutes(),
                coverage.minutes(),
                // What the customer loses, computed here rather than left to the browser: no
                // change, no residual balance, the code is spent. The screen says it at the
                // moment of redemption because the cashier is the one who has to tell them.
                Math.max(voucher.getMinutes() - coverage.minutes(), 0),
                coverage.amount());
    }

    // OUTSTANDING, REDEEMED or EXPIRED. A code redeemed before its expiry that has since passed
    // it reads REDEEMED, not EXPIRED: it was spent while it was good, and what happened to it is
    // the question this answers.
    private String statusOf(Voucher voucher, LocalDate today) {
        if (voucher.getRedeemedAt() != null) {
            return "REDEEMED";
        }
        return voucher.getExpiresOn().isBefore(today) ? "EXPIRED" : "OUTSTANDING";
    }

    // "2 hours", "90 min". Hours only when it divides exactly, because "1.5 hours" and
    // "90 min" are the same thing and one of them is the way this hall talks.
    private String hoursLabel(Integer minutes) {
        if (minutes == null) {
            return null;
        }
        if (minutes % MINUTES_PER_HOUR == 0) {
            int hours = minutes / MINUTES_PER_HOUR;
            return hours + (hours == 1 ? " hour" : " hours");
        }
        return minutes + " min";
    }

    private String codeOf(UUID voucherId) {
        return voucherRepository.findById(voucherId).map(Voucher::getCode).orElse(null);
    }

    private String tableNameOf(UUID poolTableId) {
        return poolTableRepository.findById(poolTableId).map(PoolTable::getName).orElse("That table");
    }

    private String usernameOf(UUID userId) {
        if (userId == null) {
            return null;
        }
        return appUserRepository.findById(userId).map(AppUser::getUsername).orElse("someone");
    }

    // The business date, from the database. Never LocalDate.now(): the hall's day runs
    // 10:00-05:00, and a code expiring tonight must not stop working at midnight.
    private LocalDate currentBusinessDate() {
        return branchRepository.currentBusinessDate();
    }

    private Bill requireBill(UUID id) {
        return billRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Bill", id));
    }
}
