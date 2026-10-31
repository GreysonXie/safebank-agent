package cn.safebank.service;
import cn.safebank.dao.RiskUserGuardMapper;
import cn.safebank.entity.RiskUserGuardEntity;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.DependsOn;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.function.Supplier;
@Service @DependsOn("riskSchemaInitializer")
public class RiskUserLockService {
    private final RiskUserGuardMapper guards;
    private final Clock clock;
    private final RiskActionContext context;
    private final TransactionTemplate tx;
    public RiskUserLockService(RiskUserGuardMapper guards,Clock clock,PlatformTransactionManager manager,RiskActionContext context){this.guards=guards;this.clock=clock;this.tx=new TransactionTemplate(manager);this.context=context;}
    public <T> T withActionLock(String user,String id,Supplier<T> work){return withUserLock(user,()->context.inAction(user,id,work));}
    public <T> T withUserLock(String userId,Supplier<T> work){
        return tx.execute(s->{
            if(guards.select(userId)==null){
                var row=new RiskUserGuardEntity();row.setUserId(userId);row.setFailedVerifications(0);row.setReconciliationRequired(false);row.setRevision(0L);row.setUpdatedAt(OffsetDateTime.now(clock));
                try{if(guards.insert(row)!=1)throw new IllegalStateException("User guard insert failed");}catch(DuplicateKeyException sameUserRace){if(guards.select(userId)==null)throw sameUserRace;}
            }
            if(guards.selectForUpdate(userId)==null)throw new IllegalStateException("User guard missing");
            return work.get();
        });
    }
}
