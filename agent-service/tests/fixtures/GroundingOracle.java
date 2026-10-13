import cn.safebank.*;
import com.fasterxml.jackson.databind.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
/** Test-only stdin oracle: exact existing Java fact validator, no database or HTTP. */
class GroundingOracle {
 public static void main(String[] args)throws Exception{
  var json=new ObjectMapper();var input=json.readTree(Files.readString(Path.of(args[0])));var evidence=new ArrayList<BankReplyComposer.Evidence>();
  for(var row:input.path("evidence"))evidence.add(new BankReplyComposer.Evidence(row.path("tool").asText(),json.convertValue(row.path("data"),Object.class)));
  var grounded=new GroundedAnswer(json,evidence);
  System.out.println(json.writeValueAsString(Map.of("facts",grounded.factCatalog())));System.out.flush();
  var reader=new BufferedReader(new InputStreamReader(System.in,java.nio.charset.StandardCharsets.UTF_8));String line;
  while((line=reader.readLine())!=null){
   try{var answer=json.readTree(line);var rendered=grounded.render(answer);System.out.println(json.writeValueAsString(Map.of("ok",true,"text",rendered.text())));}
   catch(BankException e){System.out.println(json.writeValueAsString(Map.of("ok",false,"code",e.code,"message",e.getMessage())));}
   System.out.flush();
  }
 }
}
