package app.trillopos.finance;

import static app.trillopos.support.Api.json;
import static app.trillopos.support.Api.read;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import app.trillopos.support.Api;
import app.trillopos.support.Api.Owner;
import app.trillopos.support.IntegrationTest;

/** Step 5d over HTTP: categories, a drawer expense, the list, roles. */
class ExpenseApiTest extends IntegrationTest {

    @Autowired
    MockMvc mvc;

    Api api;

    @BeforeEach
    void client() {
        api = new Api(mvc);
    }

    @Test
    void aCashierRecordsADrawerExpenseAndTheOwnerSeesItInTheList() throws Exception {
        Owner owner = api.signup();
        String cashier = api.member(owner, "CASHIER");
        String shift = api.openShift(owner, 50000);

        api.call(json(post("/expenses/categories"), "{\"name\":\"Transport\"}"), cashier)
                .andExpect(status().isForbidden());
        String transport = read(api.call(json(post("/expenses/categories"), "{\"name\":\"Transport\"}"),
                owner.token()).andExpect(status().isCreated()), "$.id");
        api.call(json(post("/expenses/categories"), "{\"name\":\"transport\"}"), owner.token())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("category_exists"));

        String expense = read(api.call(json(post("/expenses"), """
                {"categoryId":"%s","locationId":"%s","cashierShiftId":"%s","amount":3500,"method":"CASH",
                 "description":"taxi to the wholesaler"}""".formatted(transport, owner.mainLocationId(), shift)),
                cashier)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.categoryName").value("Transport"))
                .andExpect(jsonPath("$.amount").value(3500)), "$.id");

        api.call(get("/expenses"), owner.token())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(expense));
        api.call(get("/shifts/" + shift + "/drawer"), cashier)
                .andExpect(jsonPath("$.cashExpenses").value(3500))
                .andExpect(jsonPath("$.expectedCash").value(46500));

        api.call(post("/expenses/" + expense + "/void"), cashier).andExpect(status().isForbidden());
        api.call(post("/expenses/" + expense + "/void"), owner.token())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voidedAt").isNotEmpty());
        api.call(get("/shifts/" + shift + "/drawer"), cashier)
                .andExpect(jsonPath("$.expectedCash").value(50000));

        Owner other = api.signup();
        api.call(get("/expenses/" + expense), other.token()).andExpect(status().isNotFound());
    }
}
