package cn.safebank.common;
import java.util.List;
public record ReconciliationResult(int migrated,int unresolved,boolean transfersLocked,List<String> unresolvedActionIds) {}
