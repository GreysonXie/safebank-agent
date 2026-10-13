package cn.safebank.service;
import cn.safebank.BankException;
import cn.safebank.dao.RecipientMapper;
import cn.safebank.entity.RecipientEntity;
import cn.safebank.vo.RecipientVO;
import org.springframework.stereotype.Service;
import java.util.List;
@Service public class RecipientService {
    private final RecipientMapper recipients;
    public RecipientService(RecipientMapper recipients){
        this.recipients=recipients;
    }
    public List<RecipientVO> search(String userId,String query){
        if(query==null||query.isBlank())throw new BankException("VALIDATION_ERROR","请输入收款人");
        return recipients.selectMatches(userId,query).stream().map(r->new RecipientVO(r.id(),r.name(),r.note(),r.label())).toList();
    }
    public RecipientEntity owned(String userId,String id){
        var row=recipients.selectOwned(userId,id);
        if(row==null)throw new BankException("NOT_FOUND","资源不存在或不属于当前用户");
        return row;
    }
}
