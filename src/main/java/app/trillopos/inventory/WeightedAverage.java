package app.trillopos.inventory;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The entire costing model (spec §9.1): only inflows move the average; outflows carry it.
 * The live posting path and the rebuild replay both call this, so they cannot disagree.
 */
public final class WeightedAverage {

    /** Four decimals even for Kyat: the average is divided hundreds of times. */
    public static final int SCALE = 4;

    private WeightedAverage() {
    }

    public static BigDecimal afterInflow(BigDecimal heldQuantity, BigDecimal heldAverage, BigDecimal inQuantity,
            BigDecimal inUnitCost) {
        if (inQuantity.signum() <= 0) {
            throw new IllegalArgumentException("an inflow has a positive quantity");
        }
        if (heldQuantity.signum() <= 0) {
            // no stock (or negative from an oversell): the arriving cost is the truth
            return inUnitCost.setScale(SCALE, RoundingMode.HALF_UP);
        }
        BigDecimal heldValue = heldQuantity.multiply(heldAverage);
        BigDecimal addedValue = inQuantity.multiply(inUnitCost);
        return heldValue.add(addedValue).divide(heldQuantity.add(inQuantity), SCALE, RoundingMode.HALF_UP);
    }
}
