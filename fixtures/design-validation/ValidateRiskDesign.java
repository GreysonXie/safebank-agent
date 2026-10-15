import java.sql.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import org.h2.tools.RunScript;

// Standalone DDL design check, never loaded by the application.
class ValidateRiskDesign {
    static final List<String> checks=new ArrayList<>();
    static final String hash="0".repeat(64);
    static final String now="2026-10-09T10:00:00+08:00",end="2026-10-09T10:05:00+08:00";
    static void sql(Connection c,String statement,Object... values)throws SQLException{
        try(var p=c.prepareStatement(statement)){for(int i=0;i<values.length;i++)p.setObject(i+1,values[i]);p.executeUpdate();}
    }
    static void reject(Connection c,String name,String statement,Object... values)throws SQLException{
        try{sql(c,statement,values);throw new AssertionError("Accepted invalid row: "+name);}
        catch(SQLException e){if(!e.getSQLState().startsWith("23"))throw e;checks.add(name);}
    }
    static void decision(Connection c,String id,String action,String user,String level,String assurance)throws SQLException{
        sql(c,"INSERT INTO risk_decisions VALUES(?,?,?,1,'bank-risk-v1',?,?,'DESIGN_FIXTURE',DATE '2026-10-09',60000,0,60000,?,?,?)",id,action,user,level,assurance,hash,now,end);
    }
    static void grant(Connection c,String id,String action,String user,String decision)throws SQLException{
        sql(c,"INSERT INTO action_authorizations VALUES(?,?,?,?,NULL,?,'bank-risk-v1',DATE '2026-10-09','USER_CONFIRM','CONFIRMED','ACTIVE',?,?)",id,action,user,decision,hash,now,end);
    }
    static final String usage="INSERT INTO risk_transfer_usage VALUES(?,?,?,?,DATE '2026-10-09',?, ?,?,NULL,NULL,?)";
    public static void main(String[] args)throws Exception{
        try(var c=DriverManager.getConnection("jdbc:h2:mem:risk-design-only;DB_CLOSE_DELAY=-1","sa","")){
            for(String path:List.of("src/main/resources/schema.sql","fixtures/v03/v03-synthetic-bank.sql","docs/database/2026-10-09-risk-permission.sql")){
                try(var reader=Files.newBufferedReader(Path.of(path))){RunScript.execute(c,reader);}
            }
            try(var p=c.createStatement();var r=p.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA='PUBLIC' AND TABLE_TYPE='BASE TABLE'")){
                r.next();if(r.getInt(1)!=20)throw new AssertionError("Expected 20 tables");checks.add("existing schema plus seven tables");
            }
            for(String user:List.of("u1","u2"))sql(c,"INSERT INTO risk_user_guards(user_id,updated_at) VALUES(?,?)",user,now);
            for(String action:List.of("r-a","r-b","r-c","r-d","r-e","r-plan"))sql(c,"INSERT INTO actions VALUES(?,'u1',?,'{}',?,'AWAITING_AUTH',?,NULL,NULL)",action,action.equals("r-plan")?"SCHEDULE":"TRANSFER",hash,end);
            sql(c,"INSERT INTO actions VALUES('r-other','u2','TRANSFER','{}',?,'AWAITING_AUTH',?,NULL,NULL)",hash,end);
            decision(c,"d-a","r-a","u1","YELLOW","CONFIRMED");grant(c,"g-a","r-a","u1","d-a");
            sql(c,usage,"r-a","u1","g-a","CURRENT_AUTHORIZATION",60000,"RESERVED",end,now);
            checks.add("valid decision authorization and reservation");
            reject(c,"one usage row per action",usage,"r-a","u1","g-a","CURRENT_AUTHORIZATION",60000,"RESERVED",end,now);
            reject(c,"cross-user decision rejected","INSERT INTO risk_decisions VALUES('bad-owner','r-other','u1',1,'v1','YELLOW','CONFIRMED','X',DATE '2026-10-09',1,0,1,?,?,?)",hash,now,end);
            reject(c,"risk level and assurance must agree","INSERT INTO risk_decisions VALUES('bad-level','r-b','u1',1,'v1','GREEN','STEP_UP','X',DATE '2026-10-09',1,0,1,?,?,?)",hash,now,end);
            reject(c,"decision cannot expire at creation","INSERT INTO risk_decisions VALUES('bad-expiry','r-b','u1',1,'v1','YELLOW','CONFIRMED','X',DATE '2026-10-09',1,0,1,?,?,?)",hash,now,now);
            decision(c,"d-b","r-b","u1","RED","STEP_UP");
            reject(c,"challenge must reference same action","INSERT INTO verification_challenges VALUES('bad-challenge','r-b','u1','d-a',?,'DEMO_OTP',NULL,'synthetic-digest','SENT',0,?,?,NULL)",hash,now,end);
            reject(c,"attempt count above three rejected","INSERT INTO verification_challenges VALUES('bad-attempts','r-b','u1','d-b',?,'DEMO_OTP',NULL,'synthetic-digest','SENT',4,?,?,NULL)",hash,now,end);
            reject(c,"demo challenge requires digest","INSERT INTO verification_challenges VALUES('bad-digest','r-b','u1','d-b',?,'DEMO_OTP',NULL,NULL,'SENT',0,?,?,NULL)",hash,now,end);
            sql(c,"INSERT INTO verification_challenges VALUES('c-b','r-b','u1','d-b',?,'DEMO_OTP',NULL,'synthetic-digest','VERIFIED',0,?,?,?)",hash,now,end,now);
            sql(c,"INSERT INTO action_authorizations VALUES('g-b','r-b','u1','d-b','c-b',?,'bank-risk-v1',DATE '2026-10-09','DEMO_OTP','DEMO_VERIFIED','ACTIVE',?,?)",hash,now,end);
            checks.add("valid bound demo verification evidence");
            reject(c,"demo assurance cannot claim real MFA","INSERT INTO action_authorizations VALUES('bad-method','r-b','u1','d-b','c-b',?,'v1',DATE '2026-10-09','DEMO_OTP','MFA_VERIFIED','ACTIVE',?,?)",hash,now,end);
            reject(c,"negative reservation rejected",usage,"r-b","u1","g-b","CURRENT_AUTHORIZATION",-1,"RESERVED",end,now);
            reject(c,"new reservation requires authorization",usage,"r-c","u1",null,"CURRENT_AUTHORIZATION",100,"RESERVED",end,now);
            reject(c,"legacy data cannot fabricate reservation",usage,"r-d","u1",null,"LEGACY_OBSERVED",100,"RESERVED",end,now);
            sql(c,usage,"r-c","u1",null,"LEGACY_OBSERVED",100,"SUBMITTED",end,now);checks.add("legacy observation without fabricated authorization");
            sql(c,"INSERT INTO risk_transfer_usage VALUES('r-e','u1',NULL,'LEGACY_OBSERVED',NULL,100,'SUBMITTED',?,NULL,NULL,?)",end,now);
            checks.add("unknown legacy business date remains unresolved");
            reject(c,"new authorization must have business date","INSERT INTO risk_transfer_usage VALUES('r-b','u1','g-b','CURRENT_AUTHORIZATION',NULL,100,'SUBMITTED',?,NULL,NULL,?)",end,now);
            reject(c,"grant cannot cross action boundary",usage,"r-d","u1","g-a","CURRENT_AUTHORIZATION",100,"RESERVED",end,now);
            sql(c,"INSERT INTO schedules VALUES('s-plan','u1','checking','travel-recipient',8300,20,DATE '2026-11-20',DATE '2027-10-20','ACTIVE')");
            decision(c,"d-plan","r-plan","u1","YELLOW","CONFIRMED");grant(c,"g-plan","r-plan","u1","d-plan");
            sql(c,"INSERT INTO schedule_authorizations VALUES('s-plan','u1','g-plan','{}',?,'v1','YELLOW','ACTIVE',?,?)",hash,now,end);checks.add("schedule scope owner chain");
            reject(c,"schedule delegation cannot authorize RED","UPDATE schedule_authorizations SET maximum_risk_level='RED' WHERE schedule_id='s-plan'");
            try(var reader=Files.newBufferedReader(Path.of("docs/database/2026-10-09-risk-permission.sql"))){
                try{RunScript.execute(c,reader);throw new AssertionError("Duplicate migration accepted");}
                catch(SQLException e){if(e.getErrorCode()!=90045)throw e;checks.add("duplicate migration rejected");}
            }
        }
        var folder=Path.of("target/risk-design-qa");Files.createDirectories(folder);
        String names=checks.stream().map(s->"\""+s+"\"").reduce((a,b)->a+","+b).orElse("");
        String report="{\"passed\":true,\"checks\":"+checks.size()+",\"scope\":\"standalone H2 DDL and constraints; no production mutation or service concurrency verification\",\"modelRequests\":0,\"records\":["+names+"]}";
        Files.writeString(folder.resolve("report.json"),report);System.out.println(report);
    }
}
