package cn.safebank.service;

import cn.safebank.model.DebugRequestBudget;
import cn.safebank.vo.ModelBudgetVO;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import java.util.Set;

@Service @Profile("langgraph-agent")
public class ModelBudgetService {
    private final DebugRequestBudget budget;
    private final Environment environment;
    public ModelBudgetService(DebugRequestBudget budget,Environment environment){this.budget=budget;this.environment=environment;}
    public ModelBudgetVO summary(){
        var snapshot=budget.snapshot();
        boolean applies=Set.of("deepseek","openai").contains(environment.getProperty("bank.agent.model-mode","mock"));
        return new ModelBudgetVO(applies,snapshot.limit(),snapshot.reserved(),snapshot.remaining());
    }
}
