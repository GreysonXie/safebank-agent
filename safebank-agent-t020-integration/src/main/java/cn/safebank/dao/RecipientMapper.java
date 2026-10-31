package cn.safebank.dao;
import cn.safebank.entity.RecipientEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
@Mapper public interface RecipientMapper {
    int insert(RecipientEntity recipient);
    int updateDetails(RecipientEntity recipient);
    RecipientEntity selectOwned(@Param("userId") String userId,@Param("id") String id);
    List<cn.safebank.dao.projection.RecipientMatchRow> selectMatches(@Param("userId") String userId,@Param("query") String query);
}
