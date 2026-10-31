package cn.safebank.service;

import cn.safebank.BankException;
import cn.safebank.dao.*;
import cn.safebank.dto.*;
import cn.safebank.entity.*;
import cn.safebank.vo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class UserManagementService {
    private final UserManagementMapper users;
    private final RecipientMapper recipients;
    private final AccountMapper accounts;
    public UserManagementService(UserManagementMapper users,RecipientMapper recipients,AccountMapper accounts){
        this.users=users;this.recipients=recipients;this.accounts=accounts;
    }
    public List<ManagedUserVO> list(String userId){
        return users.list(userId).stream().map(r->new ManagedUserVO(r.id(),r.name(),r.phone(),r.gender(),r.note(),
                r.accountId(),r.accountLabel(),r.balanceMinor(),userId.equals(r.accountUserId()),r.savingsTarget())).toList();
    }
    public UserProfileVO profile(String userId){
        var result=users.profile(userId);
        return result==null?new UserProfileVO("演示用户","","UNKNOWN"):new UserProfileVO(result.name(),result.phone(),result.gender());
    }
    @Transactional
    public UserProfileVO saveProfile(String userId,UserProfileRequestDTO request){
        lockOwner(userId);
        int changed=users.profile(userId)==null
                ?users.insertProfile(userId,request.name().trim(),request.phone(),request.gender())
                :users.updateProfile(userId,request.name().trim(),request.phone(),request.gender());
        require(changed);return profile(userId);
    }
    @Transactional
    public ManagedUserVO create(String userId,ManagedUserRequestDTO request){
        lockOwner(userId);
        String accountId=request.accountId();
        if(accountId==null||accountId.isBlank()){
            var account=new AccountEntity();accountId="demo-"+UUID.randomUUID();
            account.setId(accountId);account.setUserId("external");account.setLabel(request.name().trim()+" · 合成账户");
            account.setBalanceMinor(request.initialBalanceMinor());require(accounts.insert(account));
        }else{
            if(accounts.selectOwned(userId,accountId)==null)throw new BankException("NOT_FOUND","只能关联本人账户或新建合成收款账户");
            if(request.initialBalanceMinor()!=0)throw new BankException("VALIDATION_ERROR","已有账户余额不能通过资料修改");
        }
        var recipient=new RecipientEntity();recipient.setId("contact-"+UUID.randomUUID());recipient.setUserId(userId);
        recipient.setName(request.name().trim());recipient.setPhone(request.phone());recipient.setNote(request.note().trim());recipient.setAccountId(accountId);
        require(recipients.insert(recipient));require(users.insertDetails(userId,recipient.getId(),request.gender(),request.savingsTarget()));
        return find(userId,recipient.getId());
    }
    @Transactional
    public ManagedUserVO update(String userId,String id,ManagedUserRequestDTO request){
        lockOwner(userId);
        var recipient=recipients.selectOwned(userId,id);
        if(recipient==null)throw new BankException("NOT_FOUND","联系人不存在或不属于当前用户");
        if(request.initialBalanceMinor()!=0||(request.accountId()!=null&&!recipient.getAccountId().equals(request.accountId())))
            throw new BankException("VALIDATION_ERROR","编辑资料不能修改关联账户或余额，请新增联系人");
        recipient.setName(request.name().trim());recipient.setPhone(request.phone());recipient.setNote(request.note().trim());
        require(recipients.updateDetails(recipient));
        require(users.detailCount(userId,id)==0?users.insertDetails(userId,id,request.gender(),request.savingsTarget())
                :users.updateDetails(userId,id,request.gender(),request.savingsTarget()));
        return find(userId,id);
    }
    private ManagedUserVO find(String userId,String id){return list(userId).stream().filter(u->u.id().equals(id)).findFirst().orElseThrow();}
    private void lockOwner(String userId){
        if(accounts.lockFirstOwned(userId)==null)throw new BankException("NOT_FOUND","当前用户没有演示账户");
    }
    private void require(int count){if(count!=1)throw new BankException("CONFLICT","资料已变化，请刷新重试");}
}
