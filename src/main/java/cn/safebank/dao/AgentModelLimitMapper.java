package cn.safebank.dao;
import org.apache.ibatis.annotations.*;
@Mapper public interface AgentModelLimitMapper {
 @Insert("INSERT INTO agent_model_turn_limits(turn_id,input_limit,created_at) VALUES(#{turn},#{limit},UTC_TIMESTAMP(6))") int insert(@Param("turn") String turn,@Param("limit") int limit);
 @Select("SELECT input_limit FROM agent_model_turn_limits WHERE turn_id=#{turn}") Integer limit(String turn);
}
