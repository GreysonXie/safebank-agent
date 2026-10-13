package cn.safebank.common;
public record SubmissionPermit(String actionId,String authorizationId,Mode mode) {
    public enum Mode { NEW_BANK_SUBMISSION,LOOKUP_ONLY,READ_QUERY }
}
