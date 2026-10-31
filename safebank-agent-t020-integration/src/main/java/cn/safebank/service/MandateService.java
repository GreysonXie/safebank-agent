package cn.safebank.service;
import cn.safebank.BankException;
import cn.safebank.dao.MandateMapper;
import cn.safebank.entity.MandateEntity;
import cn.safebank.vo.MandateVO;
import org.springframework.stereotype.Service;
import java.util.List;
@Service public class MandateService {
    private final MandateMapper mandates;
    public MandateService(MandateMapper mandates){
        this.mandates=mandates;
    }
    public MandateEntity owned(String userId,String id){
        var r=mandates.selectOwned(userId,id);
        if(r==null)throw new BankException("NOT_FOUND","资源不存在或不属于当前用户");
        return r;
    }
    public List<MandateVO> list(String userId){
        return mandates.selectByUser(userId).stream().map(r->new MandateVO(r.getId(),r.getUserId(),r.getMerchant(),r.getAmountMinor(),r.getRenewalDate(),r.getStatus(),r.getContractStatus(),r.getBillingInterval())).toList();
    }
}
