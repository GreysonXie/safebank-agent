package cn.safebank;

import cn.safebank.service.AccountService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:mybatis-account;DB_CLOSE_DELAY=-1","bank.model.api-key="})
class MyBatisAccountTest extends BankTestFixture {
    @Autowired AccountService accounts;
    @Autowired ObjectMapper json;

    @Test void accountReadsAreOwnedTypedAndKeepPublicJson() {
        var result=accounts.list("u1");
        assertEquals(java.util.List.of("checking","travel"),result.stream().map(a->a.id()).toList());
        assertEquals(1243000,accounts.balance("u1","checking"));
        var failure=assertThrows(BankException.class,()->accounts.balance("u1","other"));
        assertEquals("NOT_FOUND",failure.code);
        var view=json.valueToTree(result.getFirst());
        assertEquals(1243000,view.path("balance_minor").asLong());
        assertFalse(view.has("user_id"));assertFalse(view.has("balanceMinor"));
    }
}
