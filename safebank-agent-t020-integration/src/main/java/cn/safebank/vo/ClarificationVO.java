package cn.safebank.vo;
import java.util.*;
public record ClarificationVO(String question,List<String> missingSlots,List<RecipientVO> recipientCandidates) {}
