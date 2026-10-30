package cn.safebank.wealth;
import cn.safebank.BankException;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.*;
import java.time.*;
@RestController @RequestMapping("/api/wealth") public class WealthController {
    private final WealthCatalog catalog;private final WealthHistoryCatalog histories;
    public WealthController(WealthCatalog catalog,WealthHistoryCatalog histories){this.catalog=catalog;this.histories=histories;}
    /** 只读产品目录；沿用项目现有会话身份规则。 */
    @GetMapping("/products") public Map<String,Object> products(HttpSession session){
        require(session);
        return catalog.catalog();
    }
    /**
     * 校验页面所选两个产品：ID 必须是当前目录中的两个互异产品，且选择所依据的目录版本未过期。
     * 返回规范 ID 与显示名，供页面拼装问题；不把客户端文字当作产品条款。
     */
    @GetMapping("/validate") public Map<String,Object> validate(HttpSession session,
        @RequestParam(value="ids",defaultValue="") String ids,
        @RequestParam(value="revision",defaultValue="") String revision){
        require(session);
        var list=new ArrayList<String>();
        for(var id:ids.split(","))if(!id.isBlank())list.add(id.trim());
        var selected=catalog.validateSelection(list,revision);
        return Map.of("valid",true,"dataset_id",catalog.catalog().get("dataset_id"),
            "catalog_revision",catalog.revision(),"products",selected);
    }
    /** 页面用的按月历史表现：整月请求按业务日期截断，并明确实际端点。 */
    @GetMapping("/history") public WealthHistoryVO history(HttpSession session,@RequestParam("productId") String productId,@RequestParam("month") String month){
        require(session);
        String display=histories.products().contains(productId)?catalog.product(productId).path("display_name").asText(productId):productId;
        String[] range=histories.resolveMonth(month);
        return histories.query(productId,range[0],range[1],display);
    }
    private void require(HttpSession session){
        if(session==null||session.getAttribute("user")==null)throw new BankException("UNAUTHORIZED","请先建立演示会话后再查询产品");
    }
}
