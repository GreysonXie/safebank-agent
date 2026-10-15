import cn.safebank.service.SavingsTaskPlanValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
/** Offline production Java contract verification, with no Spring/database writes. */
public class SavingsPlanOracle {
 public static void main(String[] args) throws Exception {
  var json=new ObjectMapper();var proposal=json.readTree(System.in.readAllBytes());
  new SavingsTaskPlanValidator(json).validate(proposal);
  System.out.println("{\"javaContractAccepted\":true}");
 }
}
