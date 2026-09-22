package app.ordaro.crm;

import static app.ordaro.support.Api.json;
import static app.ordaro.support.Api.read;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import app.ordaro.support.Api;
import app.ordaro.support.Api.Owner;
import app.ordaro.support.IntegrationTest;

/** Step 5a over HTTP: customers, a credit sale, the receivable and its repayment. */
class CustomerApiTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    Api api;

    @BeforeEach
    void client() {
        api = new Api(mvc);
    }

    @Test
    void aCashierRegistersCustomersButOnlyAnOwnerSetsCreditTerms() throws Exception {
        Owner owner = api.signup();
        String cashier = api.member(owner, "CASHIER");

        String id = read(api.call(json(post("/customers"), """
                {"name":"Daw Hla","phone":"+95 9 7711 2233"}"""), cashier)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.phone").value("+95977112233"))
                .andExpect(jsonPath("$.type").value("MEMBER"))
                .andExpect(jsonPath("$.defaultPriceType").value("RETAIL"))
                .andExpect(jsonPath("$.creditLimit").value(0)), "$.id");

        api.call(json(patch("/customers/" + id), "{\"creditLimit\":50000}"), cashier)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("owner_only"));
        api.call(json(patch("/customers/" + id), "{\"creditLimit\":50000,\"creditTermDays\":14}"), owner.token())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.creditLimit").value(50000))
                .andExpect(jsonPath("$.availableCredit").value(50000));

        api.call(json(post("/customers"), "{\"name\":\"Someone else\",\"phone\":\"+959771122 33\"}"), owner.token())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("phone_in_use"));
        api.call(get("/customers?q=hla"), cashier)
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].name").value("Daw Hla"));
        api.call(get("/customers?q=7711"), cashier).andExpect(jsonPath("$.length()").value(1));
        api.call(post("/customers/" + id + "/archive"), cashier).andExpect(status().isForbidden());
    }

    @Test
    void aCreditSaleOverHttpShowsAsAReceivableAndARepaymentSettlesIt() throws Exception {
        Owner owner = api.signup();
        String oil = api.stockedProduct(owner, "Oil", 4500, 10, 3600);
        String shift = api.openShift(owner, 50000);
        String customer = read(api.call(json(post("/customers"), """
                {"name":"Ko Min","phone":"%s","creditLimit":100000,"creditTermDays":30}"""
                .formatted(Api.randomPhone())), owner.token()).andExpect(status().isCreated()), "$.id");

        String sale = read(api.call(json(post("/sales/checkout"), """
                {"idempotencyKey":"credit-1","locationId":"%s","channel":"POS","cashierShiftId":"%s",
                 "customerId":"%s","lines":[{"productId":"%s","quantity":2}],
                 "payments":[{"method":"CREDIT","amount":9000}]}""".formatted(owner.mainLocationId(), shift, customer,
                oil)), owner.token())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.customerId").value(customer))
                .andExpect(jsonPath("$.payments[0].method").value("CREDIT")), "$.id");

        String receivable = read(api.call(get("/receivables?customerId=" + customer), owner.token())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].sourceId").value(sale))
                .andExpect(jsonPath("$[0].customerName").value("Ko Min"))
                .andExpect(jsonPath("$[0].outstandingAmount").value(9000))
                .andExpect(jsonPath("$[0].overdue").value(false)), "$[0].id");
        api.call(get("/customers/" + customer), owner.token())
                .andExpect(jsonPath("$.outstanding").value(9000))
                .andExpect(jsonPath("$.availableCredit").value(91000));

        String cashier = api.member(owner, "CASHIER");
        String repayment = """
                {"amount":4000,"method":"CASH","locationId":"%s","cashierShiftId":"%s","idempotencyKey":"rp-1"}"""
                .formatted(owner.mainLocationId(), shift);
        api.call(json(post("/receivables/" + receivable + "/settlements"), repayment), cashier)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "false"))
                .andExpect(jsonPath("$.status").value("PARTIALLY_SETTLED"))
                .andExpect(jsonPath("$.outstandingAmount").value(5000))
                .andExpect(jsonPath("$.settlements.length()").value(1));
        api.call(json(post("/receivables/" + receivable + "/settlements"), repayment), cashier)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"))
                .andExpect(jsonPath("$.outstandingAmount").value(5000));

        api.call(get("/shifts/" + shift + "/drawer"), cashier)
                .andExpect(jsonPath("$.cashRepayments").value(4000))
                .andExpect(jsonPath("$.expectedCash").value(54000));
        api.call(post("/receivables/" + receivable + "/write-off"), cashier).andExpect(status().isForbidden());
        api.call(post("/receivables/" + receivable + "/write-off"), owner.token())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WRITTEN_OFF"))
                .andExpect(jsonPath("$.writtenOffAmount").value(5000));
    }

    @Test
    void anotherShopCannotSeeOrSettleTheDebt() throws Exception {
        Owner alpha = api.signup();
        Owner beta = api.signup();
        String customer = read(api.call(json(post("/customers"), "{\"name\":\"Ko Min\"}"), alpha.token()), "$.id");
        String receivable = read(api.call(json(post("/receivables"), """
                {"customerId":"%s","locationId":"%s","amount":20000,"note":"notebook"}"""
                .formatted(customer, alpha.mainLocationId())), alpha.token())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceType").value("MANUAL")), "$.id");

        api.call(get("/receivables/" + receivable), beta.token()).andExpect(status().isNotFound());
        api.call(get("/customers/" + customer), beta.token()).andExpect(status().isNotFound());
        api.call(json(post("/receivables/" + receivable + "/settlements"), """
                {"amount":1,"method":"CASH","locationId":"%s"}""".formatted(beta.mainLocationId())), beta.token())
                .andExpect(status().isNotFound());
        api.call(get("/receivables"), beta.token()).andExpect(jsonPath("$.length()").value(0));
    }
}
