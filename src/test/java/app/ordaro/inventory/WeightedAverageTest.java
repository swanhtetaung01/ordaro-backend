package app.ordaro.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/** Spec §9.1, the whole costing model, without a database. */
class WeightedAverageTest {

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    @Test
    void intoAnEmptyBalanceTheArrivingCostIsTheAverage() {
        assertThat(WeightedAverage.afterInflow(d("0"), d("0"), d("10"), d("1000"))).isEqualByComparingTo("1000");
    }

    @Test
    void anInflowBlendsWithWhatIsHeld() {
        // 10 @ 1000 + 5 @ 1300 = 16,500 / 15
        assertThat(WeightedAverage.afterInflow(d("10"), d("1000"), d("5"), d("1300"))).isEqualByComparingTo("1100");
    }

    @Test
    void theResultHasFourDecimalsRoundedHalfUp() {
        // 1 @ 1 + 2 @ 2 = 5 / 3 = 1.66666…
        BigDecimal average = WeightedAverage.afterInflow(d("1"), d("1"), d("2"), d("2"));
        assertThat(average).isEqualTo(d("1.6667"));
        // 1 @ 0 + 7 @ 1 = 0.875 exactly; 1 @ 0 + 15 @ 1 = 0.9375; halves round up
        assertThat(WeightedAverage.afterInflow(d("1"), d("0"), d("15"), d("1"))).isEqualTo(d("0.9375"));
        assertThat(WeightedAverage.afterInflow(d("3"), d("0"), d("13"), d("1"))).isEqualTo(d("0.8125"));
        assertThat(WeightedAverage.afterInflow(d("1"), d("0"), d("31"), d("1"))).isEqualTo(d("0.9688"));
    }

    @Test
    void afterAnOversellTheArrivingCostIsTheTruth() {
        // −4 on hand at 900: the old average describes stock that no longer exists
        assertThat(WeightedAverage.afterInflow(d("-4"), d("900"), d("10"), d("1200"))).isEqualByComparingTo("1200");
    }

    @Test
    void aZeroCostInflowPullsTheAverageDown() {
        // free samples received: 10 @ 1000 + 10 @ 0
        assertThat(WeightedAverage.afterInflow(d("10"), d("1000"), d("10"), d("0"))).isEqualByComparingTo("500");
    }

    @Test
    void fractionalQuantitiesWork() {
        // 2.5 kg @ 4000 + 0.75 kg @ 4400 = 13,300 / 3.25 = 4092.3077
        assertThat(WeightedAverage.afterInflow(d("2.5"), d("4000"), d("0.75"), d("4400"))).isEqualTo(d("4092.3077"));
    }

    @Test
    void onlyInflowsAreAccepted() {
        assertThatThrownBy(() -> WeightedAverage.afterInflow(d("10"), d("1000"), d("-1"), d("1000")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
