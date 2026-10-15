package cn.safebank;
import cn.safebank.service.*;
import cn.safebank.dto.*;
import cn.safebank.vo.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class WorkflowPlanViewTest {
 @Test void revisedUnknownStepOverridesOldCancelledCard(){
  var workflows=mock(WorkflowService.class);UUID id=UUID.randomUUID();
  var req=new WorkflowRequestDTO("2026-09",List.of("music"),"checking","travel-recipient","2026-11-20","2027-10-20",20,true);
  var steps=List.of(new WorkflowStepVO("revoke:music","REVOKE","CANCELLED","old",1,1500,null),new WorkflowStepVO("revoke:music","REVOKE","UNKNOWN","new",2,1500,"LOOKUP_UNAVAILABLE"));
  when(workflows.get("u1",id)).thenReturn(new WorkflowVO(id,2,"UNKNOWN",req,0,false,steps,NoPendingActionVO.INSTANCE,null));
  var plan=new WorkflowPlanViewService(workflows).get("u1",id);assertEquals("new",plan.nodes().get(2).actionId());assertEquals("BLOCKED_UNKNOWN",plan.nodes().get(3).displayState());assertEquals("BLOCKED_UNKNOWN",plan.nodes().get(4).displayState());
 }
 @Test void unknownBlocksSavingsAndScheduleWithoutLosingSuccessfulStep(){
  var workflows=mock(WorkflowService.class);UUID id=UUID.randomUUID();
  var req=new WorkflowRequestDTO("2026-09",List.of("music","video"),"checking","travel-recipient","2026-11-20","2027-10-20",20,true);
  var steps=List.of(new WorkflowStepVO("revoke:music","REVOKE","VERIFIED_SUCCESS","a1",1,1500,null),new WorkflowStepVO("revoke:video","REVOKE","UNKNOWN","a2",1,6800,"LOOKUP_UNAVAILABLE"));
  when(workflows.get("u1",id)).thenReturn(new WorkflowVO(id,1,"UNKNOWN",req,1500,true,steps,NoPendingActionVO.INSTANCE,null));
  var plan=new WorkflowPlanViewService(workflows).get("u1",id);
  assertEquals("VERIFIED_SUCCESS",plan.nodes().get(2).displayState());
  assertEquals("BLOCKED_UNKNOWN",plan.nodes().get(4).displayState());
  assertEquals("BLOCKED_UNKNOWN",plan.nodes().get(5).displayState());
  assertEquals(List.of("r1","r2"),plan.nodes().get(4).dependsOn());assertEquals("a2",plan.nodes().get(3).actionId());
  verify(workflows).get("u1",id);verifyNoMoreInteractions(workflows);
 }
}
