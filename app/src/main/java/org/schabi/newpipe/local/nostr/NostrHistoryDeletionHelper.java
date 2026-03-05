package org.schabi.newpipe.local.nostr;

import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;
import org.schabi.newpipe.database.AppDatabase;
import org.schabi.newpipe.database.history.model.StreamHistoryEntry;
import org.schabi.newpipe.database.stream.model.StreamEntity;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class NostrHistoryDeletionHelper {
    private static final String TAG = "NostrHistoryDeletion";

    private NostrHistoryDeletionHelper() {
    }

    @NonNull
    static Map<String, Long> readFromPreferences(@NonNull final SharedPreferences preferences,
                                                 @NonNull final String preferenceKey) {
        final String raw = preferences.getString(preferenceKey, null);
        if (TextUtils.isEmpty(raw)) {
            return new HashMap<>();
        }

        final Map<String, Long> output = new HashMap<>();
        try {
            final JSONObject json = new JSONObject(raw);
            final Iterator<String> keyIterator = json.keys();
            while (keyIterator.hasNext()) {
                final String key = keyIterator.next();
                if (TextUtils.isEmpty(key)) {
                    continue;
                }
                final long deletionTs = parseTimestamp(json.opt(key));
                if (deletionTs > 0) {
                    output.put(key, deletionTs);
                }
            }
        } catch (final JSONException ignored) {
            // Ignore malformed preferences.
        }
        return output;
    }

    static void writeToPreferences(@NonNull final SharedPreferences preferences,
                                   @NonNull final String preferenceKey,
                                   @NonNull final Map<String, Long> deletions) {
        if (deletions.isEmpty()) {
            preferences.edit().remove(preferenceKey).apply();
            return;
        }

        final JSONObject json = new JSONObject();
        for (final Map.Entry<String, Long> entry : deletions.entrySet()) {
            if (TextUtils.isEmpty(entry.getKey())
                    || entry.getValue() == null
                    || entry.getValue() <= 0) {
                continue;
            }
            try {
                json.put(entry.getKey(), entry.getValue());
            } catch (final JSONException ignored) {
                // Ignore malformed tombstone.
            }
        }
        if (json.length() == 0) {
            preferences.edit().remove(preferenceKey).apply();
            return;
        }
        preferences.edit().putString(preferenceKey, json.toString()).apply();
    }

    @NonNull
    static Map<String, Long> mergeMaps(@NonNull final Map<String, Long> remote,
                                       @NonNull final Map<String, Long> local) {
        final Map<String, Long> merged = new HashMap<>(remote);
        for (final Map.Entry<String, Long> entry : local.entrySet()) {
            mergeTimestamp(merged, entry.getKey(), entry.getValue());
        }
        return merged;
    }

    static void mergeTimestamp(@NonNull final Map<String, Long> sink,
                               @Nullable final String key,
                               @Nullable final Long incomingTs) {
        if (TextUtils.isEmpty(key) || incomingTs == null || incomingTs <= 0) {
            return;
        }
        final Long current = sink.get(key);
        if (current == null || incomingTs > current) {
            sink.put(key, incomingTs);
        }
    }

    static long parseTimestamp(@Nullable final Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        if (value instanceof JSONObject) {
            final JSONObject asJson = (JSONObject) value;
            final long deletedAt = asJson.optLong("deleted_at", 0);
            if (deletedAt > 0) {
                return deletedAt;
            }
            final long deletedAtTs = asJson.optLong("deleted_at_ts", 0);
            if (deletedAtTs > 0) {
                return deletedAtTs;
            }
            return asJson.optLong("ts", 0);
        }
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (final NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    @NonNull
    static JSONObject toJson(@NonNull final Map<String, Long> deletions,
                             final int maxEntries,
                             final int maxBytes) {
        final List<Map.Entry<String, Long>> entries = new ArrayList<>(deletions.entrySet());
        entries.sort((left, right) -> Long.compare(
                right.getValue() == null ? 0 : right.getValue(),
                left.getValue() == null ? 0 : left.getValue()
        ));

        final JSONObject json = new JSONObject();
        int added = 0;
        boolean truncated = false;
        for (final Map.Entry<String, Long> entry : entries) {
            final Long deletionTs = entry.getValue();
            if (TextUtils.isEmpty(entry.getKey()) || deletionTs == null || deletionTs <= 0) {
                continue;
            }
            try {
                json.put(entry.getKey(), deletionTs);
                added++;
            } catch (final JSONException ignored) {
                // Skip malformed tombstone.
            }

            if (added > maxEntries || utf8Size(json.toString()) > maxBytes) {
                json.remove(entry.getKey());
                truncated = true;
                break;
            }
        }
        if (truncated) {
            Log.d(TAG, "Trimmed history deletion snapshot from " + entries.size()
                    + " to " + json.length() + " entries");
        }
        return json;
    }

    static void applyToDatabase(@NonNull final AppDatabase database,
                                @NonNull final Map<String, Long> deletions) {
        if (deletions.isEmpty()) {
            return;
        }
        database.runInTransaction(() -> {
            final Set<Long> removedStreamIds = new HashSet<>();
            final List<StreamHistoryEntry> entries = database.streamHistoryDAO()
                    .getHistorySortedByIdBlocking();
            for (final StreamHistoryEntry entry : entries) {
                if (!removedStreamIds.add(entry.getStreamId())) {
                    continue;
                }
                final StreamEntity stream = entry.getStreamEntity();
                if (stream == null || TextUtils.isEmpty(stream.getUrl())) {
                    continue;
                }
                final String key = composeKey(stream.getServiceId(), stream.getUrl());
                final Long deletionTs = deletions.get(key);
                if (deletionTs == null || deletionTs <= 0) {
                    continue;
                }
                if (entry.getAccessDate().toEpochSecond() <= deletionTs) {
                    database.streamStateDAO().deleteState(entry.getStreamId());
                    database.streamHistoryDAO().deleteStreamHistory(entry.getStreamId());
                }
            }
        });
    }

    static void prune(@NonNull final Map<String, Long> deletions,
                      @NonNull final Map<String, Long> historyAccessTsByKey) {
        if (deletions.isEmpty()) {
            return;
        }
        final Iterator<Map.Entry<String, Long>> iterator = deletions.entrySet().iterator();
        while (iterator.hasNext()) {
            final Map.Entry<String, Long> entry = iterator.next();
            final long deletionTs = entry.getValue() == null ? 0 : entry.getValue();
            if (deletionTs <= 0) {
                iterator.remove();
                continue;
            }
            final Long historyAccessTs = historyAccessTsByKey.get(entry.getKey());
            if (historyAccessTs != null && historyAccessTs > deletionTs) {
                iterator.remove();
            }
        }
    }

    private static int utf8Size(@NonNull final String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    @NonNull
    private static String composeKey(final int serviceId, @NonNull final String url) {
        return serviceId + "|" + url;
    }
}
