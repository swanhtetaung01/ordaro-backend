package app.trillopos.sales;

import static app.trillopos.support.Api.json;
import static app.trillopos.support.Api.read;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import app.trillopos.support.Api;
import app.trillopos.support.Api.Owner;
import app.trillopos.support.IntegrationTest;

/** Step 5c over HTTP: sell, return one damaged and one good, see stock, the drawer and the sale status. */
class ReturnApiTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    Api api;

    @BeforeEach
    void client() {
        api = new Api(mvc);
    }

    @Test
    void aCashierReturnsGoodsAndTheCostStaysHidden() throws Exception {
        Owner owner = api.signup();
        String oil = api.stockedProduct(owner, "Oil", 1000, 10, 600);
        String shift = api.openShift(owner, 50000);
        String cashier = api.member(owner, "CASHIER");

        var sold = api.call(json(post("/sales/checkout"), """
                {"idempotencyKey":"s-1","locationId":"%s","channel":"POS","cashierShiftId":"%s",
                 "lines":[{"productId":"%s","quantity":3}],
                 "payments":[{"method":"CASH","amount":3000,"tenderedAmount":3000}]}"""
                .formatted(owner.mainLocationId(), shift, oil)), cashier).andExpect(status().isCreated());
        String sale = read(sold, "$.id");
        String saleLine = read(sold, "$.lines[0].id");

        String body = """
                {"saleId":"%s","locationId":"%s","cashierShiftId":"%s","refundMethod":"CASH","reason":"leaking",
                 "idempotencyKey":"r-1","lines":[{"saleLineId":"%s","quantity":1,"restock":false},
                                                 {"saleLineId":"%s","quantity":1}]}"""
                .formatted(sale, owner.mainLocationId(), shift, saleLine, saleLine);
        String returnId = read(api.call(json(post("/returns"), body), cashier)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "false"))
                .andExpect(jsonPath("$.returnNumber").value(org.hamcrest.Matchers.startsWith("MAIN-RTN-")))
                .andExpect(jsonPath("$.refundAmount").value(2000))
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[0].restock").value(false))
                .andExpect(jsonPath("$.lines[0].unitCost").doesNotExist()), "$.id");
        api.call(json(post("/returns"), body), cashier)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.id").value(returnId));

        api.call(get("/returns/" + returnId), owner.token())
                .andExpect(jsonPath("$.lines[1].unitCost").value(600));
        api.call(get("/stock-balances?productId=" + oil), owner.token())
                .andExpect(jsonPath("$[0].quantity").value(8)); // 10 − 3 + 1 restocked
        api.call(get("/sales/" + sale), cashier)
                .andExpect(jsonPath("$.status").value("PARTIALLY_REFUNDED"));
        api.call(get("/shifts/" + shift + "/drawer"), cashier)
                .andExpect(jsonPath("$.cashRefunds").value(2000))
                .andExpect(jsonPath("$.expectedCash").value(51000));

        api.call(json(post("/returns"), """
                {"saleId":"%s","locationId":"%s","refundMethod":"CASH",
                 "lines":[{"saleLineId":"%s","quantity":2}]}""".formatted(sale, owner.mainLocationId(), saleLine)),
                cashier)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("return_exceeds_sold"));
    }

    @Test
    void anotherShopCannotReturnAgainstTheSale() throws Exception {
        Owner alpha = api.signup();
        Owner beta = api.signup();
        String oil = api.stockedProduct(alpha, "Oil", 1000, 5, 600);
        String shift = api.openShift(alpha, 0);
        var sold = api.call(json(post("/sales/checkout"), """
                {"idempotencyKey":"s-1","locationId":"%s","channel":"POS","cashierShiftId":"%s",
                 "lines":[{"productId":"%s","quantity":1}],"payments":[{"method":"CASH","amount":1000}]}"""
                .formatted(alpha.mainLocationId(), shift, oil)), alpha.token());
        api.call(json(post("/returns"), """
                {"saleId":"%s","locationId":"%s","refundMethod":"CASH","lines":[{"saleLineId":"%s","quantity":1}]}"""
                .formatted(read(sold, "$.id"), beta.mainLocationId(), read(sold, "$.lines[0].id"))), beta.token())
                .andExpect(status().isNotFound());
    }
}
