package cn.safebank.dao;

import cn.safebank.dao.projection.ManagedUserRow;
import cn.safebank.dao.projection.UserProfileRow;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface UserManagementMapper {
    List<ManagedUserRow> list(@Param("userId") String userId);
    UserProfileRow profile(@Param("userId") String userId);
    int insertProfile(@Param("userId") String userId,@Param("name") String name,@Param("phone") String phone,@Param("gender") String gender);
    int updateProfile(@Param("userId") String userId,@Param("name") String name,@Param("phone") String phone,@Param("gender") String gender);
    int insertDetails(@Param("userId") String userId,@Param("recipientId") String recipientId,@Param("gender") String gender,@Param("savingsTarget") boolean savingsTarget);
    int updateDetails(@Param("userId") String userId,@Param("recipientId") String recipientId,@Param("gender") String gender,@Param("savingsTarget") boolean savingsTarget);
    int detailCount(@Param("userId") String userId,@Param("recipientId") String recipientId);
}
