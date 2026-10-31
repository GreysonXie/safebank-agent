package cn.safebank.dao;
import cn.safebank.entity.MandateEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
@Mapper public interface MandateMapper {
    List<MandateEntity> selectByUser(@Param("userId") String userId);
    MandateEntity selectOwned(@Param("userId") String userId,@Param("id") String id);
    MandateEntity lockOwned(@Param("userId") String userId,@Param("id") String id);
    int revoke(@Param("userId") String userId,@Param("id") String id);
    int insert(MandateEntity row);
    int activate(@Param("userId") String userId,@Param("id") String id,@Param("renewalDate") java.time.LocalDate renewalDate);
}
