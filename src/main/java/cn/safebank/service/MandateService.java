package cn.safebank.service;
import cn.safebank.BankException;
import cn.safebank.common.SimulationClock;
import cn.safebank.dao.MandateMapper;
import cn.safebank.entity.MandateEntity;
import cn.safebank.vo.MandateVO;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
@Service public class MandateService {
    /** 临近阈值固定为业务日起 7 天内（含当天）；边界在单元测试覆盖 D+0/D+7/D+8。 */
    static final int NEAR_DAYS=7;
    private final MandateMapper mandates;
    private final Clock clock;
    public MandateService(MandateMapper mandates,Clock clock){
        this.mandates=mandates;this.clock=clock;
    }
    public MandateEntity owned(String userId,String id){
        var r=mandates.selectOwned(userId,id);
        if(r==null)throw new BankException("NOT_FOUND","资源不存在或不属于当前用户");
        return r;
    }
    public List<MandateVO> list(String userId){
        LocalDate businessDate=SimulationClock.businessDate(clock);
        return mandates.selectByUser(userId).stream().map(r->{
            Integer days=r.getRenewalDate()==null?null:(int)ChronoUnit.DAYS.between(businessDate,r.getRenewalDate());
            return new MandateVO(r.getId(),r.getUserId(),r.getMerchant(),r.getAmountMinor(),r.getRenewalDate(),r.getStatus(),r.getContractStatus(),r.getBillingInterval(),
                attention(r.getStatus(),days),days);
        }).toList();
    }
    /** 状态优先：已撤销/非有效不进入有效的日期提醒；有效项再按日期关注程度分组。旧日期只表示记录待核对，不是授权失效。 */
    static String attention(String status,Integer days){
        if("REVOKED".equals(status))return "已撤销";
        if(!"ACTIVE".equals(status))return "状态未确认";
        if(days==null)return "日期未知";
        if(days<0)return "记录日期已过待核对";
        if(days<=NEAR_DAYS)return "临近（7天内）";
        return "远期";
    }
}
