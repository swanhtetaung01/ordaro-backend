package app.trillopos.reports;

import static app.trillopos.support.Api.json;
import static app.trillopos.support.Api.read;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import app.trillopos.support.Api;
import app.trillopos.support.Api.Owner;
import app.trillopos.support.IntegrationTest;

/** Step 6a over HTTP: the dashboard endpoints, and who may read them. */
class ReportApiTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    Api api;

    @BeforeEach
    void client() {
        api = new Api(mvc);
    }

    @Test
    void theDashboardReadsItsTilesFromOneSummary() throws Exception {
        Owner owner = api.signup();
        String oil = api.stockedProduct(owner, "Oil", 1000, 10, 600);
        String shift = api.openShift(owner, 50000);
        String sale = read(api.call(json(post("/sales/checkout"), """
                {"idempotencyKey":"s-1","locationId":"%s","channel":"POS","cashierShiftId":"%s",
                 "lines":[{"productId":"%s","quantity":4}],"payments":[{"method":"CASH","amount":4000}]}"""
                .formatted(owner.mainLocationId(), shift, oil)), owner.token())
                .andExpect(status().isCreated()), "$.id");
        String line = read(api.call(get("/sales/" + sale), owner.token()), "$.lines[0].id");
        api.call(json(post("/returns"), """
                {"saleId":"%s","locationId":"%s","refundMethod":"CASH","lines":[{"saleLineId":"%s","quantity":1}]}"""
                .formatted(sale, owner.mainLocationId(), line)), owner.token()).andExpect(status().isCreated());

        api.call(get("/reports/summary"), owner.token())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.salesCount").value(1))
                .andExpect(jsonPath("$.grossSales").value(4000))
                .andExpect(jsonPath("$.netRevenue").value(3809.52))
                .andExpect(jsonPath("$.cogs").value(2400))
                .andExpect(jsonPath("$.returnCount").value(1))
                .andExpect(jsonPath("$.cogsReversed").value(600))
                .andExpect(jsonPath("$.grossProfit").value(1057.14))
                .andExpect(jsonPath("$.from").value(LocalDate.now().toString()));
        api.call(get("/reports/sales-by-day"), owner.token())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].salesCount").value(1));
        api.call(get("/reports/top-products?limit=5"), owner.token())
                .andExpect(jsonPath("$[0].sku").isNotEmpty())
                .andExpect(jsonPath("$[0].quantity").value(4));
        api.call(get("/reports/payment-mix"), owner.token())
                .andExpect(jsonPath("$[0].method").value("CASH"))
                .andExpect(jsonPath("$[0].amount").value(4000));
        api.call(get("/reports/low-stock"), owner.token()).andExpect(jsonPath("$.length()").value(0));
        api.call(get("/reports/summary?from=2020-01-02&to=2020-01-01"), owner.token())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_range"));
    }

    @Test
    void theTillDoesNotSeeMargins() throws Exception {
        Owner owner = api.signup();
        String cashier = api.member(owner, "CASHIER");
        String manager = api.member(owner, "STOCK_MANAGER");

        api.call(get("/reports/summary"), cashier)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
        api.call(get("/reports/top-products"), cashier).andExpect(status().isForbidden());
        api.call(get("/reports/low-stock"), manager).andExpect(status().isOk());
        api.call(get("/reports/summary"), manager).andExpect(status().isOk());

        Owner other = api.signup();
        api.call(get("/reports/summary"), other.token())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.grossSales").value(0));
    }
}
