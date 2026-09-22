package app.ordaro.finance;

import static app.ordaro.support.Api.json;
import static app.ordaro.support.Api.read;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

/** Step 5b over HTTP: a supplier delivery, its payable, a payment, the void rule, roles. */
class PayableApiTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    Api api;

    @BeforeEach
    void client() {
        api = new Api(mvc);
    }

    @Test
    void aDeliveryShowsAsAPayableThatAPaymentSettles() throws Exception {
        Owner owner = api.signup();
        String oil = api.product(owner, "Oil", 4500);
        String supplier = read(api.call(json(post("/suppliers"), """
                {"name":"Golden Harvest","paymentTermsDays":14}"""), owner.token())
                .andExpect(status().isCreated()), "$.id");

        String document = read(api.call(json(post("/stock-documents"), """
                {"type":"STOCK_IN","locationId":"%s","supplierId":"%s","post":true,
                 "lines":[{"productId":"%s","quantity":10,"unitCost":3600}]}"""
                .formatted(owner.mainLocationId(), supplier, oil)), owner.token())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payableId").isNotEmpty()), "$.id");

        String payable = read(api.call(get("/payables?supplierId=" + supplier), owner.token())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].sourceId").value(document))
                .andExpect(jsonPath("$[0].supplierName").value("Golden Harvest"))
                .andExpect(jsonPath("$[0].originalAmount").value(36000))
                .andExpect(jsonPath("$[0].overdue").value(false)), "$[0].id");

        String payment = """
                {"amount":36000,"method":"BANK_TRANSFER","locationId":"%s","referenceNo":"TRF-1","idempotencyKey":"pay-1"}"""
                .formatted(owner.mainLocationId());
        api.call(json(post("/payables/" + payable + "/settlements"), payment), owner.token())
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replay", "false"))
                .andExpect(jsonPath("$.status").value("SETTLED"))
                .andExpect(jsonPath("$.settlements[0].referenceNo").value("TRF-1"));
        api.call(json(post("/payables/" + payable + "/settlements"), payment), owner.token())
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replay", "true"));

        // paid: the delivery can no longer be voided
        api.call(post("/stock-documents/" + document + "/void"), owner.token())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("payable_settled"));
        api.call(get("/payables?status=all"), owner.token()).andExpect(jsonPath("$.length()").value(1));
        api.call(get("/payables"), owner.token()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void cashiersDoNotSeePayablesAndOnlyOwnersRecordOldDebts() throws Exception {
        Owner owner = api.signup();
        String cashier = api.member(owner, "CASHIER");
        String manager = api.member(owner, "STOCK_MANAGER");
        String supplier = read(api.call(json(post("/suppliers"), "{\"name\":\"Shwe Pyi\"}"), owner.token()), "$.id");

        api.call(get("/payables"), cashier).andExpect(status().isForbidden());
        String manual = """
                {"supplierId":"%s","locationId":"%s","amount":250000,"note":"May invoices"}"""
                .formatted(supplier, owner.mainLocationId());
        api.call(json(post("/payables"), manual), manager).andExpect(status().isForbidden());
        String id = read(api.call(json(post("/payables"), manual), owner.token())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceType").value("MANUAL")), "$.id");
        api.call(json(post("/payables/" + id + "/settlements"), """
                {"amount":50000,"method":"CASH","locationId":"%s"}""".formatted(owner.mainLocationId())), manager)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.outstandingAmount").value(200000));
    }
}
