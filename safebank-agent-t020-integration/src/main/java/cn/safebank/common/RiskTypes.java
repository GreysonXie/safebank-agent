package cn.safebank.common;
public final class RiskTypes {
    private RiskTypes() {}
    public enum RiskLevel { GREEN,YELLOW,RED }
    public enum AssuranceRequirement { NONE,CONFIRMED,STEP_UP }
    public enum VerificationMethod { DEMO_OTP,EXTERNAL_MFA }
    public enum AuthorizationMethod { USER_CONFIRM,DEMO_OTP,EXTERNAL_MFA,SCHEDULE_DELEGATION }
    public enum AuthorizationAssurance { CONFIRMED,DEMO_VERIFIED,MFA_VERIFIED }
    public enum AuthorizationState { ACTIVE,SUBMISSION_STARTED,CONSUMED,REVOKED,EXPIRED }
    public enum UsageState { RESERVED,SUBMITTED,COMMITTED,RELEASED }
    public enum ChallengeState { PENDING_DELIVERY,SENT,VERIFIED,CONSUMED,EXPIRED,LOCKED,INVALIDATED,DELIVERY_FAILED }
    public enum RiskChannel { HTTP,AGENT,WORKFLOW,SCHEDULER,SYSTEM }
    public enum UsageProvenance { CURRENT_AUTHORIZATION,LEGACY_OBSERVED }
}
