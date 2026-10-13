package cn.safebank.dao;
import cn.safebank.entity.AgentConversationEntity;
import org.apache.ibatis.annotations.*;
import java.util.List;
@Mapper public interface AgentConversationMapper {
    AgentConversationEntity selectOwned(@Param("userId") String userId,@Param("id") String id);
    AgentConversationEntity selectOwnedForUpdate(@Param("userId") String userId,@Param("id") String id);
    int updateIfVersion(@Param("userId") String userId,@Param("id") String id,@Param("expectedVersion") long expectedVersion,@Param("next") AgentConversationEntity next);
    int insert(AgentConversationEntity row);
}
