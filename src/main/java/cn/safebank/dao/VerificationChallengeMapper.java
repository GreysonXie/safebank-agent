package cn.safebank.dao;
import cn.safebank.entity.VerificationChallengeEntity;
import org.apache.ibatis.annotations.*;
import java.util.List;
@Mapper public interface VerificationChallengeMapper {
    VerificationChallengeEntity selectOwned(@Param("userId") String userId,@Param("id") String id);
    VerificationChallengeEntity selectOwnedForUpdate(@Param("userId") String userId,@Param("id") String id);
    List<VerificationChallengeEntity> selectByUser(@Param("userId") String userId);
    List<VerificationChallengeEntity> selectByAction(@Param("userId") String userId,@Param("actionId") String actionId);
    int insert(VerificationChallengeEntity row);
    int update(VerificationChallengeEntity row);
}
