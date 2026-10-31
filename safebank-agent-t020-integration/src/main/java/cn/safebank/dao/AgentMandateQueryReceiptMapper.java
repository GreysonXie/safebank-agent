package cn.safebank.dao;
import cn.safebank.entity.AgentMandateQueryReceiptEntity;
import org.apache.ibatis.annotations.*;
@Mapper public interface AgentMandateQueryReceiptMapper {
 int insert(AgentMandateQueryReceiptEntity row);
 int capture(AgentMandateQueryReceiptEntity row);
 AgentMandateQueryReceiptEntity latest(@Param("userId") String userId,@Param("conversationId") String conversationId,@Param("now") java.time.OffsetDateTime now);
 AgentMandateQueryReceiptEntity findValid(@Param("userId") String userId,@Param("conversationId") String conversationId,@Param("actionId") String actionId,@Param("now") java.time.OffsetDateTime now);
 int invalidateConversation(@Param("userId") String userId,@Param("conversationId") String conversationId);
 int invalidateUser(@Param("userId") String userId);
}