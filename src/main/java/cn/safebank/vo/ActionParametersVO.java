package cn.safebank.vo;
public sealed interface ActionParametersVO permits TransferParametersVO,RevokeParametersVO,ScheduleParametersVO,QueryParametersVO {
}
