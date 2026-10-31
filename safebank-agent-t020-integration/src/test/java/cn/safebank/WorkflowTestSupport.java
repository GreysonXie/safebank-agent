package cn.safebank;
import cn.safebank.dto.WorkflowRequestDTO;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import static cn.safebank.WorkflowTestContracts.*;
/** Temporary type compatibility adapter; no SQL or business decisions. */
public class WorkflowTestSupport {
    private final cn.safebank.service.WorkflowService workflows;private final ObjectMapper json;
    public WorkflowTestSupport(cn.safebank.service.WorkflowService workflows,ObjectMapper json){this.workflows=workflows;this.json=json;}
    private WorkflowRequestDTO request(WorkflowRequest r){return json.convertValue(r,WorkflowRequestDTO.class);}
    private WorkflowView view(cn.safebank.vo.WorkflowVO v){return json.convertValue(v,WorkflowView.class);}
    public WorkflowView create(String user,WorkflowRequest r){return view(workflows.create(user,request(r)));}
    public List<WorkflowView> list(String user){return workflows.list(user).stream().map(this::view).toList();}
    public WorkflowView get(String user,UUID id){return view(workflows.get(user,id));}
    public WorkflowView resume(String user,UUID id,long version){return view(workflows.resume(user,id,version));}
    public WorkflowView revise(String user,UUID id,long version,WorkflowRequest r){return view(workflows.revise(user,id,version,request(r)));}
    public WorkflowView cancel(String user,UUID id,long version){return view(workflows.cancel(user,id,version));}
}
