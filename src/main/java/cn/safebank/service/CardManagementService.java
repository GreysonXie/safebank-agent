package cn.safebank.service;

import cn.safebank.BankException;
import cn.safebank.dao.*;
import cn.safebank.dto.*;
import cn.safebank.entity.*;
import cn.safebank.vo.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Local synthetic cards. Settings do not claim to control a real bank's payment channel. */
@Service
public class CardManagementService {
    private final CardMapper cards;
    private final AccountMapper accounts;
    private final ObjectMapper json;
    private final Clock clock;
    public CardManagementService(CardMapper cards,AccountMapper accounts,ObjectMapper json,Clock clock){
        this.cards=cards;this.accounts=accounts;this.json=json;this.clock=clock;
    }
    public List<BankCardVO> list(String userId){return cards.list(userId).stream().map(this::view).toList();}
    public List<CardOperationVO> history(String userId){return cards.history(userId).stream().map(this::view).toList();}
    public CardOperationVO get(String userId,String id){return view(required(cards.operation(userId,id)));}
    @Transactional
    public CardOperationVO preview(String userId,CardChangeDTO request){
        var current=validate(userId,request,false);
        String payload=encode(request),id="card-op-"+UUID.randomUUID();
        var now=now();
        var operation=new CardOperationEntity(id,userId,current==null?null:current.id(),request.kind(),payload,
                hash(id+"|"+userId+"|"+payload),"AWAITING_CONFIRMATION",current==null?0:current.version(),now,now.plusMinutes(10),null,null,null);
        require(cards.insertOperation(operation));return view(operation);
    }
    @Transactional
    public CardOperationVO confirm(String userId,String id,CardConfirmDTO confirmation){
        var operation=required(cards.lockOperation(userId,id));
        if(!confirmation.confirmed())throw error("AUTH_REQUIRED","请明确勾选并确认本次卡片操作");
        if(!MessageDigest.isEqual(operation.payloadHash().getBytes(StandardCharsets.UTF_8),confirmation.payloadHash().getBytes(StandardCharsets.UTF_8)))
            throw error("CONFLICT","操作参数不匹配，请核对原预览");
        if("COMPLETED".equals(operation.status()))return view(operation);
        if(!"AWAITING_CONFIRMATION".equals(operation.status()))throw error("CONFLICT","预览已取消");
        if(!operation.expiresAt().isAfter(now()))throw error("AUTH_EXPIRED","卡片预览已过期，请重新生成");
        var request=decode(operation.payload(),CardChangeDTO.class);
        var current=validate(userId,request,true);
        if(current!=null&&current.version()!=operation.expectedVersion())throw error("CONFLICT","卡片状态已变化，请重新生成预览");
        BankCardEntity result;
        if(current==null){
            String cardId="card-"+UUID.randomUUID();
            String suffix=String.format("%04d",new SecureRandom().nextInt(10000));
            result=new BankCardEntity(cardId,userId,request.accountId(),request.label().trim(),request.cardType(),suffix,"ACTIVE",
                    request.perTransactionLimitMinor(),request.dailyLimitMinor(),request.onlineEnabled(),request.overseasEnabled(),1,now());
            require(cards.insert(result));
        }else{
            String status=switch(request.kind()){case "LOCK"->"LOCKED";case "UNLOCK"->"ACTIVE";case "REPORT_LOST"->"LOST";default->current.status();};
            boolean limits="UPDATE_LIMITS".equals(request.kind()),controls="UPDATE_CONTROLS".equals(request.kind());
            result=new BankCardEntity(current.id(),userId,current.accountId(),current.label(),current.cardType(),current.suffix(),status,
                    limits?request.perTransactionLimitMinor():current.perTransactionLimitMinor(),limits?request.dailyLimitMinor():current.dailyLimitMinor(),
                    controls?request.onlineEnabled():current.onlineEnabled(),controls?request.overseasEnabled():current.overseasEnabled(),current.version(),current.createdAt());
            require(cards.update(result));
            result=cards.owned(userId,result.id());
        }
        var complete=new CardOperationEntity(operation.id(),userId,result.id(),operation.kind(),operation.payload(),operation.payloadHash(),"COMPLETED",
                operation.expectedVersion(),operation.createdAt(),operation.expiresAt(),now(),"CARD-R-"+UUID.randomUUID(),encode(view(result)));
        require(cards.finish(complete));return view(complete);
    }
    @Transactional
    public CardOperationVO cancel(String userId,String id){
        var operation=required(cards.lockOperation(userId,id));
        if(!"AWAITING_CONFIRMATION".equals(operation.status()))return view(operation);
        var cancelled=new CardOperationEntity(operation.id(),userId,operation.cardId(),operation.kind(),operation.payload(),operation.payloadHash(),"CANCELLED",
                operation.expectedVersion(),operation.createdAt(),operation.expiresAt(),null,null,null);
        require(cards.finish(cancelled));return view(cancelled);
    }
    private BankCardEntity validate(String userId,CardChangeDTO r,boolean lock){
        if("APPLY".equals(r.kind())){
            // Serialize all applications for this owner, even when different accounts are selected.
            // Lock before any snapshot read so the card count includes the preceding confirmation.
            if(lock&&accounts.lockFirstOwned(userId)==null)throw error("NOT_FOUND","当前用户没有演示账户");
            if(accounts.selectOwned(userId,r.accountId())==null)throw error("NOT_FOUND","请选择本人的关联账户");
            if(r.label()==null||r.label().isBlank()||r.label().length()>40||!Set.of("DEBIT","VIRTUAL").contains(Objects.toString(r.cardType(),"")))throw error("VALIDATION_ERROR","请填写40字以内的卡片名称和有效类型");
            if(cards.list(userId).stream().filter(c->!"LOST".equals(c.status())).count()>=8)throw error("LIMIT_EXCEEDED","最多持有 8 张未挂失演示卡片");
            limits(r);controls(r);return null;
        }
        if(r.cardId()==null)throw error("NOT_FOUND","请选择卡片");
        var c=lock?cards.lock(userId,r.cardId()):cards.owned(userId,r.cardId());
        if(c==null)throw error("NOT_FOUND","卡片不存在或不属于当前用户");
        if("LOST".equals(c.status()))throw error("INVALID_STATE","已挂失卡片不可恢复，请申请新卡");
        switch(r.kind()){
            case "LOCK"->{if(!"ACTIVE".equals(c.status()))throw error("INVALID_STATE","只能锁定使用中的卡片");}
            case "UNLOCK"->{if(!"LOCKED".equals(c.status()))throw error("INVALID_STATE","只能解锁临时锁定的卡片");}
            case "UPDATE_LIMITS"->limits(r);
            case "UPDATE_CONTROLS"->controls(r);
            case "REPORT_LOST"->{}
            default->throw error("VALIDATION_ERROR","不支持的卡片操作");
        }
        return c;
    }
    private void limits(CardChangeDTO r){if(r.perTransactionLimitMinor()==null||r.dailyLimitMinor()==null||r.perTransactionLimitMinor()<1||r.dailyLimitMinor()>10000000||r.perTransactionLimitMinor()>r.dailyLimitMinor())throw error("VALIDATION_ERROR","单笔限额须大于 0 且不超过日限额，日限额最高 ¥100,000");}
    private void controls(CardChangeDTO r){if(r.onlineEnabled()==null||r.overseasEnabled()==null)throw error("VALIDATION_ERROR","请明确选择线上与境外交易开关");}
    private OffsetDateTime now(){return OffsetDateTime.ofInstant(clock.instant(),ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);}
    private BankCardVO view(BankCardEntity c){var a=accounts.selectOwned(c.userId(),c.accountId());return new BankCardVO(c.id(),c.accountId(),a.getLabel(),a.getBalanceMinor(),c.label(),c.cardType(),"DEMO •••• "+c.suffix(),c.status(),c.perTransactionLimitMinor(),c.dailyLimitMinor(),c.onlineEnabled(),c.overseasEnabled(),c.version());}
    private CardOperationVO view(CardOperationEntity o){return new CardOperationVO(o.id(),o.kind(),o.status(),o.payloadHash(),decode(o.payload(),CardChangeDTO.class),o.expectedVersion(),o.createdAt(),o.expiresAt(),o.executedAt(),o.receiptId(),o.resultJson()==null?null:decode(o.resultJson(),BankCardVO.class));}
    private CardOperationEntity required(CardOperationEntity o){if(o==null)throw error("NOT_FOUND","卡片操作不存在或不属于当前用户");return o;}
    private void require(int count){if(count!=1)throw error("CONFLICT","状态已变化，请刷新后重试");}
    private BankException error(String code,String message){return new BankException(code,message);}
    private String encode(Object o){try{return json.writeValueAsString(o);}catch(JsonProcessingException e){throw new IllegalStateException("Card data encoding failed",e);}}
    private <T>T decode(String s,Class<T> type){try{return json.readValue(s,type);}catch(JsonProcessingException e){throw new IllegalStateException("Card data decoding failed",e);}}
    private String hash(String payload){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
