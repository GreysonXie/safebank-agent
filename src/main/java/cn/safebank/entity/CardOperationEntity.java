package cn.safebank.entity;
import java.time.OffsetDateTime;
public record CardOperationEntity(String id,String userId,String cardId,String kind,String payload,
        String payloadHash,String status,long expectedVersion,OffsetDateTime createdAt,
        OffsetDateTime expiresAt,OffsetDateTime executedAt,String receiptId,String resultJson) {}
