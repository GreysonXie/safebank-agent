package cn.safebank.service;
import cn.safebank.dao.AuditEventMapper;
import cn.safebank.entity.AuditEventEntity;
import cn.safebank.vo.AuditEventVO;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
@Service public class AuditService {
    private final AuditEventMapper audit;
    private final Clock clock;
    public AuditService(AuditEventMapper audit,Clock clock){
        this.audit=audit;
        this.clock=clock;
    }
    public void record(String userId,String actionId,String event){
        var row=new AuditEventEntity();
        row.setId(UUID.randomUUID().toString());
        row.setUserId(userId);
        row.setActionId(actionId);
        row.setEvent(event);
        row.setRecordedAt(OffsetDateTime.now(clock));
        if(audit.insert(row)!=1)throw new IllegalStateException("审计写入失败");
    }
    public List<AuditEventVO> list(String userId){
        return audit.selectByUser(userId).stream().map(r->new AuditEventVO(r.getActionId(),r.getEvent(),r.getRecordedAt())).toList();
    }
}
