package cn.safebank.service;
import cn.safebank.dao.RiskEventMapper;
import cn.safebank.entity.RiskEventEntity;
import cn.safebank.common.RiskTypes.RiskChannel;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.UUID;
@Service public class RiskEventService {
    private final RiskEventMapper events;private final Clock clock;
    public RiskEventService(RiskEventMapper events,Clock clock){this.events=events;this.clock=clock;}
    public void append(RiskEventEntity event){if(events.insert(event)!=1)throw new IllegalStateException("Risk event insert failed");}
    public void record(String userId,String actionId,String decisionId,String code,String reason,RiskChannel channel){
        var e=new RiskEventEntity();e.setId(UUID.randomUUID().toString());e.setRequestId(UUID.randomUUID().toString());e.setUserId(userId);e.setActionId(actionId);e.setDecisionId(decisionId);e.setEventCode(code);e.setReasonCode(reason);e.setChannel(channel.name());e.setPolicyVersion(RiskPolicyService.VERSION);e.setRecordedAt(OffsetDateTime.now(clock));append(e);
    }
}
