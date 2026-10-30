package cn.safebank;

import cn.safebank.wealth.WealthCatalog;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 演示理财目录：字段完整性、无收益不补、事实目录与虚构引用拒绝。 */
class WealthCatalogTest {
    private final ObjectMapper json=new ObjectMapper();
    private final WealthCatalog catalog=new WealthCatalog(json);

    private List<Map<?,?>> products(){return (List<Map<?,?>>)catalog.catalog().get("products");}

    @Test void displayNamesMapToFixedIdentitiesAndRevisionIsContentBased(){
        assertEquals("活钱管理",catalog.product("DEMO-WM-01").path("display_name").asText());
        assertEquals("固收90天持有",catalog.product("DEMO-WM-02").path("display_name").asText());
        assertEquals("固收180天持有",catalog.product("DEMO-WM-03").path("display_name").asText());
        String revision=(String)catalog.catalog().get("catalog_revision");
        assertTrue(revision.matches("[0-9a-f]{12}"));
        assertEquals(revision,catalog.revision());
        assertEquals("SafeBank演示·固收90天持有",catalog.product("DEMO-WM-02").path("name").asText(),"内部规范名称与来源保留");
        assertEquals(Boolean.TRUE,catalog.product("DEMO-WM-02").path("synthetic").asBoolean());
        assertEquals("S2",catalog.product("DEMO-WM-02").path("reference_source_ids").get(0).asText());
    }

    @Test void selectionValidationRejectsBadIdentityAndStaleRevision(){
        var ok=catalog.validateSelection(List.of("DEMO-WM-02","DEMO-WM-03"),catalog.revision());
        assertEquals(2,ok.size());
        assertEquals("DEMO-WM-02",ok.get(0).get("id"));
        assertEquals("固收90天持有",ok.get(0).get("display_name"));
        assertEquals("固收180天持有",ok.get(1).get("display_name"));
        var duplicate=assertThrows(BankException.class,()->catalog.validateSelection(List.of("DEMO-WM-02","DEMO-WM-02"),catalog.revision()));
        assertEquals("VALIDATION_ERROR",duplicate.code);
        var missing=assertThrows(BankException.class,()->catalog.validateSelection(List.of("DEMO-WM-02","DEMO-WM-09"),catalog.revision()));
        assertEquals("VALIDATION_ERROR",missing.code);
        var single=assertThrows(BankException.class,()->catalog.validateSelection(List.of("DEMO-WM-02"),catalog.revision()));
        assertEquals("VALIDATION_ERROR",single.code);
        var stale=assertThrows(BankException.class,()->catalog.validateSelection(List.of("DEMO-WM-02","DEMO-WM-03"),"stale-revision"));
        assertEquals("CATALOG_STALE",stale.code);
        var empty=assertThrows(BankException.class,()->catalog.validateSelection(List.of("DEMO-WM-02","DEMO-WM-03"),null));
        assertEquals("CATALOG_STALE",empty.code);
    }

    @Test void catalogKeepsThreeSyntheticProductsWithExpectedFields(){
        assertEquals(3,catalog.catalog().get("count"));
        assertEquals("safebank-wealth-demo-v1",catalog.catalog().get("dataset_id"));
        assertEquals("2026-10-30",catalog.catalog().get("reference_checked_on"));
        assertEquals(Boolean.TRUE,catalog.catalog().get("synthetic"));
        assertEquals(List.of("DEMO-WM-01","DEMO-WM-02","DEMO-WM-03"),List.copyOf(catalog.ids()));
        for(var product:products()){
            assertNotNull(product.get("name"));
            assertNotNull(product.get("product_type"));
            assertNotNull(product.get("risk_level"));
            assertNotNull(product.get("risk_note"));
            assertNotNull(product.get("redemption_rule"));
            assertEquals("未提供",product.get("expected_return"));
            assertNull(product.get("term_days"),"演示产品均无固定到期日，不能用 0 表示");
            assertEquals(Boolean.FALSE,product.get("capital_guaranteed"));
        }
        assertEquals(0,products().get(0).get("min_holding_days"));
        assertEquals(90,products().get(1).get("min_holding_days"));
        assertEquals(180,products().get(2).get("min_holding_days"));
        assertEquals(100,products().get(0).get("min_amount_minor"));
        assertEquals(100000,products().get(2).get("min_amount_minor"));
    }

    @Test void factsUseStablePrefixAndRejectFabricatedReferences(){
        var answer=new GroundedAnswer(json,List.of(new BankReplyComposer.Evidence("get_wealth_products",catalog.catalog())));
        var facts=answer.factCatalog();
        assertTrue(facts.containsKey("wealth_products_latest.products.count"));
        assertEquals(new BigDecimal("3"),facts.get("wealth_products_latest.products.count").number());
        assertTrue(facts.containsKey("wealth_products_latest.products.0.name"));
        assertTrue(facts.containsKey("wealth_products_latest.products.2.min_amount_minor"));
        assertTrue(facts.containsKey("wealth_products_latest.products.1.min_holding_days"));
        assertFalse(facts.containsKey("wealth_products_latest.products.0.term_days"));
        var text=answer.render(json.valueToTree(Map.of("kind","analysis","answer","演示目录共{{wealth_products_latest.products.count}}个产品，第一个的起购额是{{wealth_products_latest.products.0.min_amount_minor}}。"))).text();
        assertTrue(text.contains("3"));assertTrue(text.contains("1.00"));
        assertThrows(cn.safebank.BankException.class,()->answer.render(json.valueToTree(Map.of("kind","analysis","answer","起购额{{wealth_products_latest.products.9.min_amount_minor}}。"))));
    }
}
