import cn.safebank.*;
import cn.safebank.service.SavingsTaskFactProjector;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.io.*;
import java.util.*;
/** Production immutable workflow projection plus actual Java answer validator. */
class SavingsTaskFactsOracle {
 public static void main(String[] args)throws Exception{
  var json=new ObjectMapper();var snapshot=json.readTree(Files.readString(Path.of(args[0])));
  var grounded=new GroundedAnswer(json,List.of(new BankReplyComposer.Evidence("agent_savings_task_status",SavingsTaskFactProjector.project(snapshot,false))));
  System.out.println(json.writeValueAsString(Map.of("facts",grounded.factCatalog())));System.out.flush();
  var reader=new BufferedReader(new InputStreamReader(System.in,java.nio.charset.StandardCharsets.UTF_8));String line;
  while((line=reader.readLine())!=null){
   try{var answer=grounded.render(json.readTree(line));System.out.println(json.writeValueAsString(Map.of("ok",true,"text",answer.text())));}
   catch(BankException error){System.out.println(json.writeValueAsString(Map.of("ok",false,"code",error.code,"message",error.getMessage())));}
   System.out.flush();
  }
 }
}
