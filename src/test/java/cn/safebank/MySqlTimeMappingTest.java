package cn.safebank;

import org.apache.ibatis.type.TypeHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.time.*;
import java.time.temporal.ChronoUnit;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="SAFEBANK_MYSQL_TEST_URL",matches=".+")
class MySqlTimeMappingTest {
    @Test void productionMysqlClockUsesDatabaseMicrosecondPrecision() throws Exception {
        var config=new cn.safebank.config.MyBatisDialectConfig();
        var clock=(Clock)config.getClass().getMethod("mysqlClock",boolean.class).invoke(config,false);
        for(int i=0;i<20;i++)assertEquals(0,clock.instant().getNano()%1000);
        assertEquals(ZoneId.of("Asia/Shanghai"),clock.getZone());
    }
    @SuppressWarnings("unchecked")
    @Test void utcMicrosNullAndShanghaiBoundaryRoundTrip() throws Exception {
        var ds=MySqlTestSupport.fresh("time");MySqlTestSupport.migration(ds).migrate();
        var handler=(TypeHandler<OffsetDateTime>)Class.forName("cn.safebank.config.UtcOffsetDateTimeTypeHandler").getConstructor().newInstance();
        var a=OffsetDateTime.parse("2026-10-10T23:59:59.999999999+08:00");
        try(var c=ds.getConnection()) {
            try(var s=c.prepareStatement("INSERT INTO execution_observations(action_id,observed_at) VALUES(?,?)")) {
                String[] ids={"offset","utc","midnight","null"};
                OffsetDateTime[] times={a,a.withOffsetSameInstant(ZoneOffset.UTC),OffsetDateTime.parse("2026-10-11T00:00:00+08:00"),null};
                for(int i=0;i<ids.length;i++){s.setString(1,ids[i]);handler.setParameter(s,2,times[i],org.apache.ibatis.type.JdbcType.TIMESTAMP);s.executeUpdate();}
            }
            for(String id:new String[]{"offset","utc","midnight","null"}) {
                try(var s=c.prepareStatement("SELECT observed_at FROM execution_observations WHERE action_id=?")){s.setString(1,id);try(var r=s.executeQuery()){assertTrue(r.next());var value=handler.getResult(r,"observed_at");
                    if(id.equals("null")){assertNull(value);continue;}
                    assertEquals(ZoneOffset.UTC,value.getOffset());
                    if(id.equals("midnight"))assertEquals(LocalDate.of(2026,10,11),value.atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDate());
                    else {assertEquals(a.toInstant().truncatedTo(ChronoUnit.MICROS),value.toInstant());assertEquals(LocalDate.of(2026,10,10),value.atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDate());}
                }}
            }
        }
    }
}
