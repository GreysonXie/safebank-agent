package cn.safebank.dao;
import cn.safebank.entity.TransactionEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;
@Mapper public interface TransactionMapper {
    List<java.time.LocalDate> selectBookedDates(@Param("userId") String userId);
    TransactionEntity selectActionDebit(@Param("userId") String userId,@Param("id") String id);
    List<TransactionEntity> selectPeriod(@Param("userId") String userId,@Param("start") java.time.LocalDate start,@Param("end") java.time.LocalDate end);
    List<cn.safebank.dao.projection.RecurringPaymentRow> selectRecurring(@Param("userId") String userId);
    int insert(TransactionEntity row);
}
