/*
 * Copyright 2013-2020 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package org.whispersystems.textsecuregcm.storage;

import static com.codahale.metrics.MetricRegistry.name;

import com.codahale.metrics.SharedMetricRegistries;
import com.codahale.metrics.Timer;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.whispersystems.textsecuregcm.util.Constants;

@SuppressWarnings("OptionalUsedAsFieldOrParameterType")
public abstract class AccountDatabaseCrawlerListener {

  private final Timer processChunkTimer;

  abstract public void onCrawlStart();

  abstract public void onCrawlEnd();

  abstract protected void onCrawlChunk(Optional<UUID> fromUuid, List<Account> chunkAccounts);

  public AccountDatabaseCrawlerListener() {
    processChunkTimer = SharedMetricRegistries.getOrCreate(Constants.METRICS_NAME).timer(name(AccountDatabaseCrawlerListener.class, "processChunk", getClass().getSimpleName()));
  }

  public void timeAndProcessCrawlChunk(Optional<UUID> fromUuid, List<Account> chunkAccounts) {
    try (Timer.Context timer = processChunkTimer.time()) {
      onCrawlChunk(fromUuid, chunkAccounts);
    }
  }

}
