package cn.safebank.config;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import java.sql.*;
import java.time.*;
import java.time.temporal.ChronoUnit;

/** MySQL-only mapping: UTC instant at microsecond precision, no original offset retained. */
public final class UtcOffsetDateTimeTypeHandler extends BaseTypeHandler<OffsetDateTime> {
    public void setNonNullParameter(PreparedStatement ps,int i,OffsetDateTime value,JdbcType type) throws SQLException {
        ps.setObject(i,LocalDateTime.ofInstant(value.toInstant().truncatedTo(ChronoUnit.MICROS),ZoneOffset.UTC));
    }
    private OffsetDateTime utc(LocalDateTime value){return value==null?null:value.atOffset(ZoneOffset.UTC);}
    public OffsetDateTime getNullableResult(ResultSet rs,String column) throws SQLException{return utc(rs.getObject(column,LocalDateTime.class));}
    public OffsetDateTime getNullableResult(ResultSet rs,int column) throws SQLException{return utc(rs.getObject(column,LocalDateTime.class));}
    public OffsetDateTime getNullableResult(CallableStatement cs,int column) throws SQLException{return utc(cs.getObject(column,LocalDateTime.class));}
}
