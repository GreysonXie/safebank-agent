package cn.safebank.dao;
import cn.safebank.entity.AgentTurnReceiptEntity;
import org.apache.ibatis.annotations.*;
import java.util.List;
@Mapper public interface AgentTurnReceiptMapper {
    List<AgentTurnReceiptEntity> selectByConversation(@Param("userId") String userId,@Param("conversationId") String conversationId);
    int updateOwned(AgentTurnReceiptEntity row);
    AgentTurnReceiptEntity selectRequest(@Param("userId") String userId,@Param("conversationId") String conversationId,@Param("requestId") String requestId);
    AgentTurnReceiptEntity selectRequestForUpdate(@Param("userId") String userId,@Param("conversationId") String conversationId,@Param("requestId") String requestId);
    List<AgentTurnReceiptEntity> selectWithBankReferences(@Param("userId") String userId);
    int interruptProcessing();
    int insert(AgentTurnReceiptEntity row);
}
