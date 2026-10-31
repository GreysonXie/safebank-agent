package cn.safebank.service;

import cn.safebank.BankException;
import cn.safebank.dto.CardChangeDTO;
import cn.safebank.vo.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 卡片能力的对话入口：把"我的卡片"业务页已有的查询与预览接到自然语言。
 *
 * <p>边界与既有设计一致：这里只查询、只生成预览，**绝不确认或执行**；
 * 客户仍需在卡片页逐项核对并勾选确认。挂失等不可恢复操作同样只出预览，
 * 并在事实里写清影响，避免模型把"已生成预览"说成"已停卡"。</p>
 */
@Service @Profile("agent-gateway")
public class AgentCardService {
    private static final Map<String,String> KIND_LABEL=Map.of(
        "APPLY","申请卡片","LOCK","临时锁卡","UNLOCK","解锁卡片","REPORT_LOST","挂失",
        "UPDATE_LIMITS","调整交易限额","UPDATE_CONTROLS","调整线上/境外开关");
    private static final Map<String,String> KIND_SCOPE=Map.of(
        "APPLY","新增一张演示卡片，关联账户余额不变",
        "LOCK","临时锁定，可解锁恢复",
        "UNLOCK","仅临时锁定的卡片可解锁",
        "REPORT_LOST","挂失后此卡永久停用，不可解挂；关联账户余额保留",
        "UPDATE_LIMITS","只改演示卡片的限额设置，未接入真实消费网络",
        "UPDATE_CONTROLS","只改演示卡片的线上/境外开关，未接入真实消费网络");
    private static final Map<String,String> STATUS_LABEL=Map.of(
        "AWAITING_CONFIRMATION","待客户确认","COMPLETED","已完成","CANCELLED","已取消");

    private final CardManagementService cards;
    private final AccountService accounts;

    public AgentCardService(CardManagementService cards,AccountService accounts){this.cards=cards;this.accounts=accounts;}

    public List<BankCardVO> list(String user){return cards.list(user);}

    public List<CardOperationVO> history(String user){return cards.history(user);}

    /** 生成待确认预览；参数不足或对象不唯一时返回可读的澄清数据，不猜测。 */
    public Object preview(String user,JsonNode args){
        String kind=text(args,"kind");
        if(!KIND_LABEL.containsKey(kind))return Map.of("code","CARD_PARAMETERS_REQUIRED","missing",List.of("kind"));
        try{
            if("APPLY".equals(kind))return previewApply(user,args);
            return previewChange(user,kind,args);
        }catch(BankException e){
            // 例如已挂失卡片不可恢复：把真实原因交给模型解释，而不是变成一次失败轮次
            return Map.of("code","CARD_OPERATION_REJECTED","message",Objects.toString(e.getMessage(),"该卡片操作没有被接受"));
        }
    }

    private Object previewApply(String user,JsonNode args){
        var missing=new ArrayList<String>();
        if(text(args,"label").isEmpty())missing.add("label");
        if(!args.path("perTransactionLimitMinor").isIntegralNumber())missing.add("perTransactionLimitMinor");
        if(!args.path("dailyLimitMinor").isIntegralNumber())missing.add("dailyLimitMinor");
        if(!args.path("onlineEnabled").isBoolean())missing.add("onlineEnabled");
        if(!args.path("overseasEnabled").isBoolean())missing.add("overseasEnabled");
        if(!missing.isEmpty())return Map.of("code","CARD_PARAMETERS_REQUIRED","missing",List.copyOf(missing));
        var account=resolveAccount(user,text(args,"accountQuery"));
        if(account==null){
            // 多账户时是"还缺关联账户"而不是"办不了"：把可选账户交给客户挑
            return Map.of("code","CARD_PARAMETERS_REQUIRED","missing",List.of("accountQuery"),
                "account_options",accounts.list(user).stream().map(AccountVO::label).toList());
        }
        // 未说明卡类型时按储蓄卡处理：类别不是客户意图的关键，仍需在预览里如实展示
        String cardType=text(args,"cardType").isEmpty()?"DEBIT":text(args,"cardType");
        var dto=new CardChangeDTO("APPLY",null,account.id(),text(args,"label"),cardType,
            args.path("perTransactionLimitMinor").asLong(),args.path("dailyLimitMinor").asLong(),
            args.path("onlineEnabled").asBoolean(),args.path("overseasEnabled").asBoolean());
        return operation(cards.preview(user,dto));
    }

    private Object previewChange(String user,String kind,JsonNode args){
        var lookup=resolveCard(user,text(args,"cardQuery"));
        if(lookup.rows().size()!=1){
            var choices=lookup.rows().stream().limit(8)
                .map(c->Map.of("card_id",c.id(),"label",c.label(),"masked_number",c.maskedNumber(),"status",c.status()))
                .toList();
            return Map.of("code","CARD_CLARIFICATION_REQUIRED",
                "reason",lookup.rows().isEmpty()?"没有匹配的卡片，请客户说明是哪一张卡":"有多张卡片匹配，请客户指定卡片",
                "candidates",choices,"candidatesTruncated",lookup.rows().size()>8);
        }
        var card=lookup.rows().getFirst();
        Long per=null,daily=null;Boolean online=null,overseas=null;
        if("UPDATE_LIMITS".equals(kind)){
            if(!args.path("perTransactionLimitMinor").isIntegralNumber()||!args.path("dailyLimitMinor").isIntegralNumber())
                return Map.of("code","CARD_PARAMETERS_REQUIRED","missing",List.of("perTransactionLimitMinor","dailyLimitMinor"));
            per=args.path("perTransactionLimitMinor").asLong();daily=args.path("dailyLimitMinor").asLong();
        }else if("UPDATE_CONTROLS".equals(kind)){
            if(!args.path("onlineEnabled").isBoolean()||!args.path("overseasEnabled").isBoolean())
                return Map.of("code","CARD_PARAMETERS_REQUIRED","missing",List.of("onlineEnabled","overseasEnabled"));
            online=args.path("onlineEnabled").asBoolean();overseas=args.path("overseasEnabled").asBoolean();
        }
        var dto=new CardChangeDTO(kind,card.id(),null,null,null,per,daily,online,overseas);
        return operation(cards.preview(user,dto));
    }

    private Object operation(CardOperationVO op){return Map.of("pendingCardOperation",op);}

    private AccountVO resolveAccount(String user,String query){
        var rows=accounts.list(user);
        if(query==null||query.isBlank())return rows.size()==1?rows.getFirst():null;
        var wanted=strip(query);
        var matched=rows.stream().filter(a->strip(a.label()).equals(wanted)||strip(a.label()).contains(wanted)).toList();
        return matched.size()==1?matched.getFirst():null;
    }

    private record Lookup(List<BankCardVO> rows){}

    /** 卡片检索：未指定卡号时返回全部卡片让客户挑选；指定时先精确匹配名称/卡号后四位，再退到包含匹配。 */
    private Lookup resolveCard(String user,String query){
        var all=cards.list(user);
        if(query==null||query.isBlank())return new Lookup(all);
        var wanted=strip(query);
        var exact=all.stream().filter(c->strip(c.label()).equals(wanted)||strip(c.maskedNumber()).equals(wanted)
            ||strip(c.maskedNumber()).endsWith(wanted)).toList();
        if(!exact.isEmpty())return new Lookup(exact);
        return new Lookup(all.stream().filter(c->strip(c.label()).contains(wanted)).toList());
    }

    /** 事实目录按接口 JSON 直接构建：不依赖 record 反序列化，避免编译参数差异导致事实为空。 */
    public Map<String,Object> listFacts(com.fasterxml.jackson.databind.JsonNode rows){
        var items=new ArrayList<Map<String,Object>>();
        if(rows.isArray())for(var c:rows){
            var item=new LinkedHashMap<String,Object>();
            item.put("label",c.path("label").asText());
            item.put("masked_number",c.path("maskedNumber").asText());
            item.put("card_type","VIRTUAL".equals(c.path("cardType").asText())?"虚拟卡":"储蓄卡");
            item.put("status",statusLabel(c.path("status").asText()));
            item.put("account_label",c.path("accountLabel").asText());
            item.put("per_transaction_limit_minor",c.path("perTransactionLimitMinor").asLong());
            item.put("daily_limit_minor",c.path("dailyLimitMinor").asLong());
            item.put("online_enabled",c.path("onlineEnabled").asBoolean()?"允许":"关闭");
            item.put("overseas_enabled",c.path("overseasEnabled").asBoolean()?"允许":"关闭");
            item.put("account_balance_minor",c.path("balanceMinor").asLong());
            items.add(Map.copyOf(item));
        }
        return Map.of("count",items.size(),"items",List.copyOf(items));
    }

    public Map<String,Object> historyFacts(com.fasterxml.jackson.databind.JsonNode rows){
        var items=new ArrayList<Map<String,Object>>();
        if(rows.isArray())for(var op:rows){
            var item=new LinkedHashMap<String,Object>();
            item.put("kind_label",KIND_LABEL.getOrDefault(op.path("kind").asText(),op.path("kind").asText()));
            item.put("status_label",STATUS_LABEL.getOrDefault(op.path("status").asText(),op.path("status").asText()));
            String created=op.path("createdAt").asText("");
            if(!created.isBlank())item.put("created_at",created);
            var card=op.path("card");
            if(card.isObject())item.put("card_masked_number",card.path("maskedNumber").asText());
            String receipt=op.path("receiptId").asText("");
            if(!receipt.isBlank())item.put("receipt_id",receipt);
            if(items.size()<10)items.add(Map.copyOf(item));
        }
        return Map.of("count",items.size(),"items",List.copyOf(items));
    }

    /** 预览事实：把"待确认"与"不可恢复"写进事实，避免正文把预览说成已执行。 */
    public Map<String,Object> operationFacts(com.fasterxml.jackson.databind.JsonNode op){
        var item=new LinkedHashMap<String,Object>();
        String kind=op.path("kind").asText();
        item.put("operation_id",op.path("id").asText());
        item.put("kind_label",KIND_LABEL.getOrDefault(kind,kind));
        item.put("status_label",STATUS_LABEL.getOrDefault(op.path("status").asText(),op.path("status").asText()));
        item.put("kind_scope",KIND_SCOPE.getOrDefault(kind,""));
        item.put("confirm_location","我的卡片 → 操作预览");
        item.put("valid_minutes",10);
        var request=op.path("request");
        var card=op.path("card");
        if(card.isObject()){
            item.put("card_masked_number",card.path("maskedNumber").asText());
            if(!card.path("label").asText("").isBlank())item.put("card_label",card.path("label").asText());
            if(!card.path("accountLabel").asText("").isBlank())item.put("account_label",card.path("accountLabel").asText());
        }
        if(!request.path("label").asText("").isBlank())item.put("card_label",request.path("label").asText());
        if(request.hasNonNull("perTransactionLimitMinor")){
            item.put("per_transaction_limit_minor",request.path("perTransactionLimitMinor").asLong());
            item.put("daily_limit_minor",request.path("dailyLimitMinor").asLong());
        }
        if(request.hasNonNull("onlineEnabled")){
            item.put("online_enabled",request.path("onlineEnabled").asBoolean()?"允许":"关闭");
            item.put("overseas_enabled",request.path("overseasEnabled").asBoolean()?"允许":"关闭");
        }
        return Map.copyOf(item);
    }

    private static String statusLabel(String status){
        return switch(status){
            case "ACTIVE"->"使用中";
            case "LOCKED"->"已临时锁定";
            case "LOST"->"已挂失";
            default->status;
        };
    }


    private static String text(JsonNode args,String name){
        var value=args.path(name);
        return value.isTextual()?value.asText().strip():"";
    }

    private static String strip(String value){
        return value==null?"":value.replace("（合成）","").replace("(合成)","").replace("·","").replaceAll("\\s+","").strip();
    }
}
