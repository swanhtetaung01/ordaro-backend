package app.trillopos.sales;

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

/** The sales log and the held-cart list, and the customer's name on a receipt. */
class SaleListApiTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    Api api;

    @BeforeEach
    void client() {
        api = new Api(mvc);
    }

    @Test
    void theLogListsCompletedSalesAndTheHeldListOnlyParkedCarts() throws Exception {
        Owner owner = api.signup();
        String oil = api.stockedProduct(owner, "Oil", 1000, 20, 600);
        String shift = api.openShift(owner, 50000);
        String customer = read(api.call(json(post("/customers"), """
                {"name":"Ko Min","phone":"%s","creditLimit":50000,"creditTermDays":7}"""
                .formatted(Api.randomPhone())), owner.token()), "$.id");

        String sale = read(api.call(json(post("/sales/checkout"), """
                {"idempotencyKey":"s-1","locationId":"%s","channel":"POS","cashierShiftId":"%s","customerId":"%s",
                 "lines":[{"productId":"%s","quantity":3}],"payments":[{"method":"CREDIT","amount":3000}]}"""
                .formatted(owner.mainLocationId(), shift, customer, oil)), owner.token())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerName").value("Ko Min")), "$.id");
        api.call(json(post("/sales"), """
                {"locationId":"%s","channel":"POS","cashierShiftId":"%s","hold":true,
                 "lines":[{"productId":"%s","quantity":2}]}""".formatted(owner.mainLocationId(), shift, oil)),
                owner.token()).andExpect(status().isCreated());

        api.call(get("/sales?status=COMPLETED&status=PARTIALLY_REFUNDED&status=REFUNDED"), owner.token())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(sale))
                .andExpect(jsonPath("$[0].customerName").value("Ko Min"))
                .andExpect(jsonPath("$[0].total").value(3000))
                .andExpect(jsonPath("$[0].lineCount").value(1))
                .andExpect(jsonPath("$[0].receiptNumber").isNotEmpty());
        api.call(get("/sales?status=HELD"), owner.token())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("HELD"))
                .andExpect(jsonPath("$[0].receiptNumber").doesNotExist());
        api.call(get("/sales"), owner.token()).andExpect(jsonPath("$.length()").value(2));
        api.call(get("/sales?customerId=" + customer), owner.token()).andExpect(jsonPath("$.length()").value(1));
        api.call(get("/sales?from=" + LocalDate.now().plusDays(1)), owner.token())
                .andExpect(jsonPath("$.length()").value(0));
        api.call(get("/sales?locationId=" + owner.mainLocationId() + "&limit=1"), owner.token())
                .andExpect(jsonPath("$.length()").value(1));

        Owner other = api.signup();
        api.call(get("/sales"), other.token()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void aCashierSeesTheLogAndAPackerDoesNot() throws Exception {
        Owner owner = api.signup();
        api.stockedProduct(owner, "Oil", 1000, 5, 600);
        String cashier = api.member(owner, "CASHIER");
        String packer = api.member(owner, "PACKER");

        api.call(get("/sales"), cashier).andExpect(status().isOk());
        api.call(get("/sales"), packer)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("forbidden"));
    }
}
