package cn.safebank.dto;
import cn.safebank.common.ContextTypes.*;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
public record ContextProposalDTO(Intent intent,Map<String,JsonNode> updates,List<String> clears,List<String> missingSlots,boolean pause) {}
