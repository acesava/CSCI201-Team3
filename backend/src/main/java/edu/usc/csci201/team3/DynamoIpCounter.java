package edu.usc.csci201.team3;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.core.retry.RetryPolicy;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

final class DynamoIpCounter implements IpRateLimiter.CounterStore {
    private final DynamoDbClient client;
    private final String table;

    DynamoIpCounter(DynamoDbClient client, String table) {
        this.client = client;
        this.table = table;
    }

    static IpRateLimiter production() {
        String table = System.getenv("IP_RATE_LIMIT_TABLE");
        if (table == null || table.isBlank()) {
            return new IpRateLimiter((key, expiry, limit) -> {
                throw new IllegalStateException("IP_RATE_LIMIT_TABLE is required");
            }, Clock.systemUTC());
        }
        DynamoDbClient client = DynamoDbClient.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder()
                        .connectionTimeout(Duration.ofSeconds(1))
                        .socketTimeout(Duration.ofSeconds(2)))
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(Duration.ofSeconds(2))
                        .retryPolicy(RetryPolicy.none()).build())
                .build();
        return new IpRateLimiter(new DynamoIpCounter(client, table), Clock.systemUTC());
    }

    public boolean acquire(String key, long expiresAt, int limit) {
        try {
            // Conditional update is atomic across all concurrent Lambda instances.
            client.updateItem(UpdateItemRequest.builder().tableName(table)
                    .key(Map.of("pk", AttributeValue.fromS(key)))
                    .updateExpression("SET expiresAt = :expiry ADD #n :one")
                    .conditionExpression("attribute_not_exists(#n) OR #n < :limit")
                    .expressionAttributeNames(Map.of("#n", "requestCount"))
                    .expressionAttributeValues(Map.of(
                            ":expiry", AttributeValue.fromN(Long.toString(expiresAt)),
                            ":one", AttributeValue.fromN("1"),
                            ":limit", AttributeValue.fromN(Integer.toString(limit))))
                    .build());
            return true;
        } catch (ConditionalCheckFailedException exhausted) {
            return false;
        }
    }
}
