package cn.safebank.controller;

import cn.safebank.BankException;
import cn.safebank.service.ModelBudgetService;
import cn.safebank.vo.ModelBudgetVO;
import jakarta.servlet.http.HttpSession;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController @Profile("langgraph-agent")
public class ModelBudgetController {
    private final ModelBudgetService budgets;
    public ModelBudgetController(ModelBudgetService budgets){this.budgets=budgets;}
    @GetMapping("/api/model-budget") public ModelBudgetVO read(HttpSession session){
        if(session.getAttribute("user")==null)throw new BankException("SESSION_REQUIRED","请先建立会话");
        return budgets.summary();
    }
}
