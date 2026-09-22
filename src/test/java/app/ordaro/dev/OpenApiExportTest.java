package app.ordaro.dev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.jayway.jsonpath.JsonPath;

import io.swagger.v3.oas.annotations.media.Schema;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.RestController;

import app.ordaro.support.IntegrationTest;

/** Writes the live OpenAPI document so ordaro-web can generate its client. */
@TestPropertySource(properties = "springdoc.api-docs.enabled=true")
class OpenApiExportTest extends IntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ApplicationContext context;

    @Test
    void export() throws Exception {
        String body = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        assertThat(body).contains("/auth/login");
        Path out = Path.of("target", "openapi.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, body);
    }

    /**
     * springdoc names a schema after the record's simple name and silently keeps only one when two
     * controllers use the same name — the generated client then types a stock-document line like a
     * sale line. Every clash needs {@code @Schema(name = …)}; this fails when a new one appears.
     */
    @Test
    void everyRequestAndResponseRecordHasItsOwnSchemaName() throws Exception {
        Map<String, List<String>> byName = new HashMap<>();
        for (Object controller : context.getBeansWithAnnotation(RestController.class).values()) {
            Class<?> type = controller.getClass();
            if (!type.getPackageName().startsWith("app.ordaro")) {
                continue;
            }
            for (Class<?> nested : type.getDeclaredClasses()) {
                if (!nested.isRecord()) {
                    continue;
                }
                Schema schema = nested.getAnnotation(Schema.class);
                String name = schema != null && !schema.name().isBlank() ? schema.name() : nested.getSimpleName();
                byName.computeIfAbsent(name, key -> new ArrayList<>()).add(nested.getName());
            }
        }
        List<String> clashes = byName.entrySet().stream().filter(e -> e.getValue().size() > 1)
                .map(e -> e.getKey() + " ← " + e.getValue()).toList();
        assertThat(clashes).as("schema names used by more than one record").isEmpty();

        String body = mvc.perform(get("/v3/api-docs")).andReturn().getResponse().getContentAsString();
        Map<String, Object> schemas = JsonPath.read(body, "$.components.schemas");
        assertThat(schemas).containsKeys("SaleLineView", "SaleLineRequest", "StockDocumentLineView",
                "StockDocumentLineRequest", "ReturnLineView", "ReturnLineRequest", "ReceivableSettlementView",
                "PayableSettlementView", "ManualReceivableRequest", "ManualPayableRequest", "ExpenseCategoryView");
        assertThat(schemas).doesNotContainKeys("LineView", "LineRequest", "SettlementView", "ManualRequest");
    }
}
