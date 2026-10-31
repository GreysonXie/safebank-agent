package cn.safebank.service;

import cn.safebank.BankException;
import cn.safebank.dto.FinanceRecommendationRequestDTO;
import cn.safebank.dto.FinanceRecommendationRequestDTO.Liquidity;
import cn.safebank.dto.FinanceRecommendationRequestDTO.Purpose;
import cn.safebank.dto.FinanceRecommendationRequestDTO.RiskPreference;
import cn.safebank.vo.FinanceRecommendationVO;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 理财方向的对话入口：只做"按偏好筛选方向"，不做申购、不承诺收益。
 *
 * <p>偏好必须由客户明确给出；缺项时返回缺哪几项，由模型追问，不替客户默认。
 * 取舍规则、排除原因与风险披露全部来自 {@link FinanceRecommendationService} 的既有实现。</p>
 */
@Service @Profile("agent-gateway")
public class AgentFinanceService {
    private final FinanceRecommendationService recommendations;

    public AgentFinanceService(FinanceRecommendationService recommendations){this.recommendations=recommendations;}

    public Object recommend(JsonNode args){
        var missing=new ArrayList<String>();
        var risk=enumValue(args,"riskPreference",RiskPreference.class);if(risk==null)missing.add("riskPreference");
        var purpose=enumValue(args,"purpose",Purpose.class);if(purpose==null)missing.add("purpose");
        var liquidity=enumValue(args,"liquidity",Liquidity.class);if(liquidity==null)missing.add("liquidity");
        var amount=args.path("amountMinor");
        if(!amount.isIntegralNumber()||amount.asLong()<100)missing.add("amountMinor");
        var horizon=args.path("horizonMonths");
        if(!horizon.isIntegralNumber()||horizon.asInt()<1)missing.add("horizonMonths");
        var capital=args.path("capitalProtection");
        if(!capital.isBoolean())missing.add("capitalProtection");
        if(!missing.isEmpty())return Map.of("code","FINANCE_PREFERENCES_REQUIRED","missing",List.copyOf(missing));
        try{
            var result=recommendations.recommend(new FinanceRecommendationRequestDTO(
                risk,purpose,liquidity,amount.asLong(),horizon.asInt(),capital.asBoolean()));
            return Map.of("financeRecommendation",result);
        }catch(BankException e){
            return Map.of("code","FINANCE_PREFERENCES_REJECTED","message",Objects.toString(e.getMessage(),"推荐偏好没有被接受"));
        }
    }

    /** 事实目录按接口 JSON 直接取值：不依赖 record 反序列化；金额单位为分。 */
    public Map<String,Object> facts(com.fasterxml.jackson.databind.JsonNode vo){
        var result=new LinkedHashMap<String,Object>();
        result.put("mode",vo.path("mode").asText());
        result.put("source",vo.path("source").asText());
        result.put("summary",vo.path("summary").asText());
        result.put("disclosure",vo.path("disclosure").asText());
        result.put("evaluated_count",vo.path("evaluatedCount").asInt());
        result.put("catalog_version",vo.path("catalogVersion").asText());
        result.put("candidates",vo.path("candidates"));
        result.put("exclusions",vo.path("exclusions"));
        result.put("rules",vo.path("rules"));
        return Map.copyOf(result);
    }

    private static <T extends Enum<T>> T enumValue(JsonNode args,String name,Class<T> type){
        var value=args.path(name);
        if(!value.isTextual())return null;
        try{return Enum.valueOf(type,value.asText().strip());}catch(IllegalArgumentException e){return null;}
    }
}
