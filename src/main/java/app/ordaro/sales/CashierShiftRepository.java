package app.ordaro.sales;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Tenant-scoped by {@code @TenantId}. */
public interface CashierShiftRepository extends JpaRepository<CashierShift, UUID> {

    Optional<CashierShift> findByLocationIdAndStatus(UUID locationId, ShiftStatus status);

    List<CashierShift> findTop50ByLocationIdOrderByOpenedAtDesc(UUID locationId);

    /** Closing takes this lock; completing a sale takes {@link #lockShared}, so a close waits for them. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from CashierShift s where s.id = :id")
    Optional<CashierShift> lockForClose(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select s from CashierShift s where s.id = :id")
    Optional<CashierShift> lockShared(@Param("id") UUID id);

    /** CASH applied on this shift's completed sales (spec §6 drawer rule, sales side). */
    @Query("""
            select coalesce(sum(p.amount), 0) from Payment p, Sale s
            where p.saleId = s.id and s.cashierShiftId = :shift
              and s.status in :statuses and p.method = app.ordaro.sales.PaymentMethod.CASH""")
    BigDecimal cashTaken(@Param("shift") UUID shiftId, @Param("statuses") Collection<SaleStatus> statuses);

    /** CASH repayments of receivables taken at this drawer. */
    @Query("""
            select coalesce(sum(r.amount), 0) from ReceivableSettlement r
            where r.cashierShiftId = :shift and r.method = app.ordaro.sales.PaymentMethod.CASH""")
    BigDecimal cashCollected(@Param("shift") UUID shiftId);

    /** CASH paid to suppliers out of this drawer. */
    @Query("""
            select coalesce(sum(p.amount), 0) from PayableSettlement p
            where p.cashierShiftId = :shift and p.method = app.ordaro.sales.PaymentMethod.CASH""")
    BigDecimal cashPaidToSuppliers(@Param("shift") UUID shiftId);
}
