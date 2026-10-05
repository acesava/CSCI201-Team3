package edu.usc.csci201.team3;

import java.net.URI;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import static org.junit.jupiter.api.Assertions.*;

/** Optional integration test against a disposable local DynamoDB instance. */
@EnabledIfEnvironmentVariable(named = "DYNAMODB_LOCAL_ENDPOINT", matches = ".+")
class DynamoIpCounterTest {
    @Test void concurrentRequestsCannotExceedTheSharedAllowance() throws Exception {
        URI endpoint = URI.create(System.getenv("DYNAMODB_LOCAL_ENDPOINT"));
        assertTrue("http".equals(endpoint.getScheme())
                && ("localhost".equals(endpoint.getHost()) || "127.0.0.1".equals(endpoint.getHost())),
                "This test must only use a local disposable database");
        try (var client = DynamoDbClient.builder().endpointOverride(endpoint)
                .region(Region.US_WEST_2)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("local", "local")))
                .httpClientBuilder(UrlConnectionHttpClient.builder()).build()) {
            String table = "ip-test-" + UUID.randomUUID();
            client.createTable(r -> r.tableName(table).billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(AttributeDefinition.builder().attributeName("pk").attributeType("S").build())
                    .keySchema(KeySchemaElement.builder().attributeName("pk").keyType("HASH").build()));
            try {
                var first = new DynamoIpCounter(client, table);
                var second = new DynamoIpCounter(client, table);
                var results = new ArrayList<Future<Boolean>>();
                try (var workers = Executors.newFixedThreadPool(12)) {
                    for (int i = 0; i < 60; i++) {
                        var counter = i % 2 == 0 ? first : second;
                        results.add(workers.submit(() -> counter.acquire("shared-window", 230, 30)));
                    }
                    int accepted = 0;
                    for (var result : results) if (result.get()) accepted++;
                    assertEquals(30, accepted);
                }
                var item = client.getItem(r -> r.tableName(table).consistentRead(true)
                        .key(Map.of("pk", AttributeValue.fromS("shared-window")))).item();
                assertEquals("30", item.get("requestCount").n());
                assertEquals("230", item.get("expiresAt").n());
                assertTrue(first.acquire("another-window", 240, 30));
            } finally {
                client.deleteTable(r -> r.tableName(table));
            }
        }
    }
}
