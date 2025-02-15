/*
 * Copyright 2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.storage;

import static org.whispersystems.textsecuregcm.metrics.MetricsUtil.name;

import com.google.common.annotations.VisibleForTesting;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.commons.lang3.StringUtils;
import org.whispersystems.textsecuregcm.entities.PreKey;
import org.whispersystems.textsecuregcm.util.AttributeValues;
import org.whispersystems.textsecuregcm.util.Util;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.Select;

/**
 * A single-use pre-key store stores single-use pre-keys of a specific type. Keys returned by a single-use pre-key
 * store's {@link #take(UUID, long)} method are guaranteed to be returned exactly once, and repeated calls will never
 * yield the same key.
 * <p/>
 * Each {@link Account} may have one or more {@link Device devices}. Clients <em>should</em> regularly check their
 * supply of single-use pre-keys (see {@link #getCount(UUID, long)}) and upload new keys when their supply runs low. In
 * the event that a party wants to begin a session with a device that has no single-use pre-keys remaining, that party
 * may fall back to using the device's repeated-use ("last-resort") signed pre-key instead.
 */
public abstract class SingleUsePreKeyStore<K extends PreKey<?>> {

  private final DynamoDbAsyncClient dynamoDbAsyncClient;
  private final String tableName;

  private final Timer storeKeyTimer = Metrics.timer(name(getClass(), "storeKey"));
  private final Timer storeKeyBatchTimer = Metrics.timer(name(getClass(), "storeKeyBatch"));
  private final Timer getKeyCountTimer = Metrics.timer(name(getClass(), "getCount"));
  private final Timer deleteForDeviceTimer = Metrics.timer(name(getClass(), "deleteForDevice"));
  private final Timer deleteForAccountTimer = Metrics.timer(name(getClass(), "deleteForAccount"));

  final DistributionSummary keysConsideredForTakeDistributionSummary = DistributionSummary
      .builder(name(getClass(), "keysConsideredForTake"))
      .publishPercentiles(0.5, 0.75, 0.95, 0.99, 0.999)
      .distributionStatisticExpiry(Duration.ofMinutes(10))
      .register(Metrics.globalRegistry);

  final DistributionSummary availableKeyCountDistributionSummary = DistributionSummary
      .builder(name(getClass(), "availableKeyCount"))
      .publishPercentiles(0.5, 0.75, 0.95, 0.99, 0.999)
      .distributionStatisticExpiry(Duration.ofMinutes(10))
      .register(Metrics.globalRegistry);

  private final String takeKeyTimerName = name(getClass(), "takeKey");
  private static final String KEY_PRESENT_TAG_NAME = "keyPresent";

  private final Counter parseBytesFromStringCounter = Metrics.counter(name(getClass(), "parseByteArray"), "format", "string");
  private final Counter readBytesFromByteArrayCounter = Metrics.counter(name(getClass(), "parseByteArray"), "format", "bytes");

  static final String KEY_ACCOUNT_UUID = "U";
  static final String KEY_DEVICE_ID_KEY_ID = "DK";
  static final String ATTR_PUBLIC_KEY = "P";
  static final String ATTR_SIGNATURE = "S";

  protected SingleUsePreKeyStore(final DynamoDbAsyncClient dynamoDbAsyncClient, final String tableName) {
    this.dynamoDbAsyncClient = dynamoDbAsyncClient;
    this.tableName = tableName;
  }

  /**
   * Stores a batch of single-use pre-keys for a specific device. All previously-stored keys for the device are cleared
   * before storing new keys.
   *
   * @param identifier the identifier for the account/identity with which the target device is associated
   * @param deviceId the identifier for the device within the given account/identity
   * @param preKeys a collection of single-use pre-keys to store for the target device
   *
   * @return a future that completes when all previously-stored keys have been removed and the given collection of
   * pre-keys has been stored in its place
   */
  public CompletableFuture<Void> store(final UUID identifier, final long deviceId, final List<K> preKeys) {
    final Timer.Sample sample = Timer.start();

    return delete(identifier, deviceId)
        .thenCompose(ignored -> CompletableFuture.allOf(preKeys.stream()
            .map(preKey -> store(identifier, deviceId, preKey))
            .toList()
            .toArray(new CompletableFuture[0])))
        .thenRun(() -> sample.stop(storeKeyBatchTimer));
  }

  private CompletableFuture<Void> store(final UUID identifier, final long deviceId, final K preKey) {
    final Timer.Sample sample = Timer.start();

    return dynamoDbAsyncClient.putItem(PutItemRequest.builder()
            .tableName(tableName)
            .item(getItemFromPreKey(identifier, deviceId, preKey))
            .build())
        .thenRun(() -> sample.stop(storeKeyTimer));
  }

  /**
   * Attempts to retrieve a single-use pre-key for a specific device. Keys may only be returned by this method at most
   * once; once the key is returned, it is removed from the key store and subsequent calls to this method will never
   * return the same key.
   *
   * @param identifier the identifier for the account/identity with which the target device is associated
   * @param deviceId the identifier for the device within the given account/identity
   *
   * @return a future that yields a single-use pre-key if one is available or empty if no single-use pre-keys are
   * available for the target device
   */
  public CompletableFuture<Optional<K>> take(final UUID identifier, final long deviceId) {
    final Timer.Sample sample = Timer.start();
    final AttributeValue partitionKey = getPartitionKey(identifier);
    final AtomicInteger keysConsidered = new AtomicInteger(0);

    return Flux.from(dynamoDbAsyncClient.queryPaginator(QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("#uuid = :uuid AND begins_with (#sort, :sortprefix)")
                .expressionAttributeNames(Map.of("#uuid", KEY_ACCOUNT_UUID, "#sort", KEY_DEVICE_ID_KEY_ID))
                .expressionAttributeValues(Map.of(
                    ":uuid", partitionKey,
                    ":sortprefix", getSortKeyPrefix(deviceId)))
                .projectionExpression(KEY_DEVICE_ID_KEY_ID)
                .consistentRead(false)
                .build())
            .items())
        .map(item -> DeleteItemRequest.builder()
            .tableName(tableName)
            .key(Map.of(
                KEY_ACCOUNT_UUID, partitionKey,
                KEY_DEVICE_ID_KEY_ID, item.get(KEY_DEVICE_ID_KEY_ID)))
            .returnValues(ReturnValue.ALL_OLD)
            .build())
        .flatMap(deleteItemRequest -> Mono.fromFuture(dynamoDbAsyncClient.deleteItem(deleteItemRequest)), 1)
        .doOnNext(deleteItemResponse -> keysConsidered.incrementAndGet())
        .filter(DeleteItemResponse::hasAttributes)
        .next()
        .map(deleteItemResponse -> getPreKeyFromItem(deleteItemResponse.attributes()))
        .toFuture()
        .thenApply(Optional::ofNullable)
        .whenComplete((maybeKey, throwable) -> {
          sample.stop(Metrics.timer(takeKeyTimerName, KEY_PRESENT_TAG_NAME, String.valueOf(maybeKey != null && maybeKey.isPresent())));
          keysConsideredForTakeDistributionSummary.record(keysConsidered.get());
        });
  }

  /**
   * Estimates the number of single-use pre-keys available for a given device.

   * @param identifier the identifier for the account/identity with which the target device is associated
   * @param deviceId the identifier for the device within the given account/identity

   * @return a future that yields the approximate number of single-use pre-keys currently available for the target
   * device
   */
  public CompletableFuture<Integer> getCount(final UUID identifier, final long deviceId) {
    final Timer.Sample sample = Timer.start();

    // Getting an accurate count from DynamoDB can be very confusing. See:
    //
    // - https://github.com/aws/aws-sdk-java/issues/693
    // - https://github.com/aws/aws-sdk-java/issues/915
    // - https://docs.aws.amazon.com/amazondynamodb/latest/developerguide/Query.html#Query.Count
    return Flux.from(dynamoDbAsyncClient.queryPaginator(QueryRequest.builder()
            .tableName(tableName)
            .keyConditionExpression("#uuid = :uuid AND begins_with (#sort, :sortprefix)")
            .expressionAttributeNames(Map.of("#uuid", KEY_ACCOUNT_UUID, "#sort", KEY_DEVICE_ID_KEY_ID))
            .expressionAttributeValues(Map.of(
                ":uuid", getPartitionKey(identifier),
                ":sortprefix", getSortKeyPrefix(deviceId)))
            .select(Select.COUNT)
            .consistentRead(false)
            .build()))
        .map(QueryResponse::count)
        .reduce(0, Integer::sum)
        .toFuture()
        .whenComplete((keyCount, throwable) -> {
          sample.stop(getKeyCountTimer);

          if (throwable == null && keyCount != null) {
            availableKeyCountDistributionSummary.record(keyCount);
          }
        });
  }

  /**
   * Removes all single-use pre-keys for all devices associated with the given account/identity.
   *
   * @param identifier the identifier for the account/identity for which to remove single-use pre-keys
   *
   * @return a future that completes when all single-use pre-keys have been removed for all devices associated with the
   * given account/identity
   */
  public CompletableFuture<Void> delete(final UUID identifier) {
    final Timer.Sample sample = Timer.start();

    return deleteItems(getPartitionKey(identifier), Flux.from(dynamoDbAsyncClient.queryPaginator(QueryRequest.builder()
            .tableName(tableName)
            .keyConditionExpression("#uuid = :uuid")
            .expressionAttributeNames(Map.of("#uuid", KEY_ACCOUNT_UUID))
            .expressionAttributeValues(Map.of(":uuid", getPartitionKey(identifier)))
            .projectionExpression(KEY_DEVICE_ID_KEY_ID)
            .consistentRead(true)
            .build())
        .items()))
        .thenRun(() -> sample.stop(deleteForAccountTimer));
  }

  /**
   * Removes all single-use pre-keys for a specific device.
   *
   * @param identifier the identifier for the account/identity with which the target device is associated
   * @param deviceId the identifier for the device within the given account/identity

   * @return a future that completes when all single-use pre-keys have been removed for the target device
   */
  public CompletableFuture<Void> delete(final UUID identifier, final long deviceId) {
    final Timer.Sample sample = Timer.start();

    return deleteItems(getPartitionKey(identifier), Flux.from(dynamoDbAsyncClient.queryPaginator(QueryRequest.builder()
            .tableName(tableName)
            .keyConditionExpression("#uuid = :uuid AND begins_with (#sort, :sortprefix)")
            .expressionAttributeNames(Map.of("#uuid", KEY_ACCOUNT_UUID, "#sort", KEY_DEVICE_ID_KEY_ID))
            .expressionAttributeValues(Map.of(
                ":uuid", getPartitionKey(identifier),
                ":sortprefix", getSortKeyPrefix(deviceId)))
            .projectionExpression(KEY_DEVICE_ID_KEY_ID)
            .consistentRead(true)
            .build())
        .items()))
        .thenRun(() -> sample.stop(deleteForDeviceTimer));
  }

  private CompletableFuture<Void> deleteItems(final AttributeValue partitionKey, final Flux<Map<String, AttributeValue>> items) {
    return items
        .map(item -> DeleteItemRequest.builder()
            .tableName(tableName)
            .key(Map.of(
                KEY_ACCOUNT_UUID, partitionKey,
                KEY_DEVICE_ID_KEY_ID, item.get(KEY_DEVICE_ID_KEY_ID)
            ))
            .build())
        .flatMap(deleteItemRequest -> Mono.fromFuture(dynamoDbAsyncClient.deleteItem(deleteItemRequest)))
        // Idiom: wait for everything to finish, but discard the results
        .reduce(0, (a, b) -> 0)
        .toFuture()
        .thenRun(Util.NOOP);
  }

  protected static AttributeValue getPartitionKey(final UUID accountUuid) {
    return AttributeValues.fromUUID(accountUuid);
  }

  protected static AttributeValue getSortKey(final long deviceId, final long keyId) {
    final ByteBuffer byteBuffer = ByteBuffer.wrap(new byte[16]);
    byteBuffer.putLong(deviceId);
    byteBuffer.putLong(keyId);
    return AttributeValues.fromByteBuffer(byteBuffer.flip());
  }

  private static AttributeValue getSortKeyPrefix(final long deviceId) {
    final ByteBuffer byteBuffer = ByteBuffer.wrap(new byte[8]);
    byteBuffer.putLong(deviceId);
    return AttributeValues.fromByteBuffer(byteBuffer.flip());
  }

  protected abstract Map<String, AttributeValue> getItemFromPreKey(final UUID identifier, final long deviceId,
      final K preKey);

  protected abstract K getPreKeyFromItem(final Map<String, AttributeValue> item);

  /**
   * Extracts a byte array from an {@link AttributeValue} that may be either a byte array or a base64-encoded string.
   *
   * @param attributeValue the {@code AttributeValue} from which to extract a byte array
   *
   * @return the byte array represented by the given {@code AttributeValue}
   */
  @VisibleForTesting
  byte[] extractByteArray(final AttributeValue attributeValue) {
    if (attributeValue.b() != null) {
      readBytesFromByteArrayCounter.increment();
      return attributeValue.b().asByteArray();
    } else if (StringUtils.isNotBlank(attributeValue.s())) {
      parseBytesFromStringCounter.increment();
      return Base64.getDecoder().decode(attributeValue.s());
    }

    throw new IllegalArgumentException("Attribute value has neither a byte array nor a string value");
  }
}
