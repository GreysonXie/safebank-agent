package cn.safebank.service;
import cn.safebank.vo.*;
import org.springframework.stereotype.Service;
import java.util.*;

/** Read-only projection; the bank's workflow and action receipts own all states. */
@Service public class WorkflowPlanViewService {
 private final WorkflowService workflows;
 public WorkflowPlanViewService(WorkflowService workflows){this.workflows=workflows;}
 private static TaskPlanNodeVO node(String id,String type,List<String> dependencies,String state,String action,String error){return new TaskPlanNodeVO(id,type,dependencies,state,action,error);}
 public TaskPlanVO get(String user,UUID id){
  var w=workflows.get(user,id);var nodes=new ArrayList<TaskPlanNodeVO>();
  nodes.add(node("m","mandate_lookup",List.of(),"VALIDATED",null,null));nodes.add(node("a","account_query",List.of(),"VALIDATED",null,null));
  boolean unknown=false,waiting=false;var deps=new ArrayList<String>();int number=0;
  for(String mandate:w.request().mandateIds()){
   String key="r"+(++number);deps.add(key);
   var step=w.steps().stream().filter(s->s.key().equals("revoke:"+mandate)).max(Comparator.comparingLong(WorkflowStepVO::inputVersion));
   String state=step.map(WorkflowStepVO::status).orElse("WAITING_DEPENDENCY");String action=step.map(WorkflowStepVO::actionId).orElse(null);
   if(w.pendingAction() instanceof ActionVO current&&current.id().equals(action))state=current.status().name();
   unknown|=state.equals("UNKNOWN");waiting|=!Set.of("VERIFIED_SUCCESS","VERIFIED_FAILED","CANCELLED").contains(state);
   nodes.add(node(key,"mandate_revoke",List.of("m"),state.equals("UNKNOWN")?"BLOCKED_UNKNOWN":state,action,step.map(WorkflowStepVO::errorCode).orElse(null)));
  }
  String sum=unknown?"BLOCKED_UNKNOWN":waiting?"WAITING_DEPENDENCY":w.estimatedSavingMinor()>0?"VERIFIED_SUCCESS":"VERIFIED_FAILED";
  nodes.add(node("s","compute_savings",deps,sum,null,null));
  var scheduled=w.steps().stream().filter(s->s.kind().equals("SCHEDULE")&&s.inputVersion()==w.version()).findFirst();
  String state=unknown?"BLOCKED_UNKNOWN":scheduled.map(WorkflowStepVO::status).orElse("WAITING_DEPENDENCY");
  if(w.pendingAction() instanceof ActionVO current&&scheduled.isPresent()&&current.id().equals(scheduled.get().actionId()))state=current.status().name();
  nodes.add(node("t","schedule_create",List.of("s","a"),state.equals("UNKNOWN")?"BLOCKED_UNKNOWN":state,scheduled.map(WorkflowStepVO::actionId).orElse(null),scheduled.map(WorkflowStepVO::errorCode).orElse(null)));
  return new TaskPlanVO(w.id(),w.version(),w.state(),"verified_success_subset",w.request().month(),nodes);
 }
}
