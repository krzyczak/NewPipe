package org.schabi.newpipe.local.nostr;

import android.text.TextUtils;

import androidx.annotation.NonNull;

import org.schabi.newpipe.database.AppDatabase;
import org.schabi.newpipe.database.subscription.SubscriptionEntity;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

final class NostrSubscriptionDeletionHelper {
    private NostrSubscriptionDeletionHelper() {
    }

    static void alignLocalClocks(@NonNull final Map<String, SubscriptionRecord> localSubscriptions,
                                 @NonNull final Map<String, Long> clocksByKey) {
        clocksByKey.keySet().retainAll(localSubscriptions.keySet());
        for (final Map.Entry<String, SubscriptionRecord> entry : localSubscriptions.entrySet()) {
            final String key = entry.getKey();
            final SubscriptionRecord record = entry.getValue();
            final long clockTs = valueOrZero(clocksByKey.get(key));
            if (clockTs > 0) {
                record.updatedTs = Math.max(record.updatedTs, clockTs);
            }
        }
    }

    static void applyToRecords(@NonNull final Map<String, SubscriptionRecord> subscriptions,
                               @NonNull final Map<String, Long> deletionsByKey) {
        if (subscriptions.isEmpty() || deletionsByKey.isEmpty()) {
            return;
        }
        final Iterator<Map.Entry<String, SubscriptionRecord>> iterator =
                subscriptions.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<String, SubscriptionRecord> entry = iterator.next();
            final long deletionTs = valueOrZero(deletionsByKey.get(entry.getKey()));
            if (deletionTs <= 0) {
                continue;
            }
            if (Math.max(0, entry.getValue().updatedTs) <= deletionTs) {
                iterator.remove();
            }
        }
    }

    static void applyToDatabase(@NonNull final AppDatabase database,
                                @NonNull final Map<String, Long> deletionsByKey,
                                @NonNull final Map<String, Long> clocksByKey) {
        if (deletionsByKey.isEmpty()) {
            return;
        }

        database.runInTransaction(() -> {
            for (final SubscriptionEntity entity : database.subscriptionDAO().getAllBlocking()) {
                final String url = entity.getUrl();
                if (TextUtils.isEmpty(url)) {
                    continue;
                }
                final String key = composeKey(entity.getServiceId(), url);
                final long deletionTs = valueOrZero(deletionsByKey.get(key));
                if (deletionTs <= 0) {
                    continue;
                }

                final long clockTs = valueOrZero(clocksByKey.get(key));
                if (clockTs <= deletionTs) {
                    database.subscriptionDAO().deleteSubscription(entity.getServiceId(), url);
                }
            }
        });
    }

    @NonNull
    static Map<String, Long> buildCanonicalClocks(
            @NonNull final Map<String, SubscriptionRecord> canonicalSubscriptions,
            @NonNull final Map<String, Long> previousClocksByKey,
            @NonNull final Map<String, SubscriptionRecord> mergedSubscriptionsByKey,
            final long nowTs) {
        final Map<String, Long> output = new HashMap<>();
        for (final Map.Entry<String, SubscriptionRecord> entry
                : canonicalSubscriptions.entrySet()) {
            final String key = entry.getKey();
            final SubscriptionRecord canonicalRecord = entry.getValue();
            final SubscriptionRecord mergedRecord = mergedSubscriptionsByKey.get(key);
            final long previousTs = valueOrZero(previousClocksByKey.get(key));
            final long mergedTs = mergedRecord == null ? 0 : Math.max(0, mergedRecord.updatedTs);
            long clockTs = Math.max(previousTs, mergedTs);
            if (clockTs <= 0) {
                clockTs = Math.max(1, nowTs);
            }
            canonicalRecord.updatedTs = clockTs;
            output.put(key, clockTs);
        }
        return output;
    }

    private static long valueOrZero(final Long value) {
        return value == null ? 0 : value;
    }

    @NonNull
    private static String composeKey(final int serviceId, @NonNull final String url) {
        return serviceId + "|" + url;
    }
}
