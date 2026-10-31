package cn.safebank.vo;
import cn.safebank.dto.CardChangeDTO;
import java.time.OffsetDateTime;
public record CardOperationVO(String id,String kind,String status,String payloadHash,CardChangeDTO request,
        long expectedVersion,OffsetDateTime createdAt,OffsetDateTime expiresAt,OffsetDateTime executedAt,
        String receiptId,BankCardVO card) {}
