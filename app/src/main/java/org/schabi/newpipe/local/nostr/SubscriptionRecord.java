package org.schabi.newpipe.local.nostr;

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.schabi.newpipe.database.subscription.NotificationMode;
import org.schabi.newpipe.database.subscription.SubscriptionEntity;
import org.schabi.newpipe.local.subscription.FeedGroupIcon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class SubscriptionRecord {
    final int serviceId;
    final String url;
    String name;
    String avatarUrl;
    Long subscriberCount;
    String description;
    long updatedTs;
    final Map<String, GroupRecord> groups = new HashMap<>();

    SubscriptionRecord(final int serviceId,
                       @NonNull final String url,
                       @Nullable final String name,
                       @Nullable final String avatarUrl,
                       @Nullable final Long subscriberCount,
                       @Nullable final String description) {
        this(serviceId, url, name, avatarUrl, subscriberCount, description, 0);
    }

    SubscriptionRecord(final int serviceId,
                       @NonNull final String url,
                       @Nullable final String name,
                       @Nullable final String avatarUrl,
                       @Nullable final Long subscriberCount,
                       @Nullable final String description,
                       final long updatedTs) {
        this.serviceId = serviceId;
        this.url = url;
        this.name = name;
        this.avatarUrl = avatarUrl;
        this.subscriberCount = subscriberCount;
        this.description = description;
        this.updatedTs = Math.max(0, updatedTs);
    }

    @Nullable
    static SubscriptionRecord fromJson(@NonNull final JSONObject json) {
        return fromJson(json, 0);
    }

    @Nullable
    static SubscriptionRecord fromJson(
            @NonNull final JSONObject json,
            final long fallbackUpdatedTs) {
        final int serviceId = json.optInt("service_id", Integer.MIN_VALUE);
        final String url = json.optString("url", null);
        if (serviceId == Integer.MIN_VALUE || TextUtils.isEmpty(url)) {
            return null;
        }
        final long itemUpdatedTs = Math.max(
                0,
                json.optLong("updated_ts", Math.max(0, fallbackUpdatedTs))
        );
        return new SubscriptionRecord(
                serviceId,
                url,
                json.optString("name", null),
                json.optString("avatar_url", null),
                json.has("subscriber_count")
                        ? json.optLong("subscriber_count")
                        : null,
                json.optString("description", null),
                itemUpdatedTs
        ).applyGroups(json.optJSONArray("groups"));
    }

    @NonNull
    static SubscriptionRecord fromEntity(@NonNull final SubscriptionEntity entity) {
        return fromEntity(entity, 0);
    }

    @NonNull
    static SubscriptionRecord fromEntity(@NonNull final SubscriptionEntity entity,
                                         final long updatedTs) {
        return new SubscriptionRecord(
                entity.getServiceId(),
                entity.getUrl(),
                entity.getName(),
                entity.getAvatarUrl(),
                entity.getSubscriberCount(),
                entity.getDescription(),
                updatedTs
        );
    }

    @NonNull
    JSONObject toJson() throws JSONException {
        final JSONObject json = new JSONObject()
                .put("service_id", serviceId)
                .put("url", url)
                .put("name", name)
                .put("avatar_url", avatarUrl)
                .put("description", description)
                .put("updated_ts", updatedTs);
        if (subscriberCount != null) {
            json.put("subscriber_count", subscriberCount);
        }
        if (!groups.isEmpty()) {
            final List<GroupRecord> sortedGroups = new ArrayList<>(groups.values());
            sortedGroups.sort((left, right) ->
                    left.name.compareToIgnoreCase(right.name));
            final JSONArray groupsArray = new JSONArray();
            for (final GroupRecord group : sortedGroups) {
                groupsArray.put(group.toJson());
            }
            json.put("groups", groupsArray);
        }
        return json;
    }

    @NonNull
    SubscriptionEntity toEntity() {
        return new SubscriptionEntity(
                0,
                serviceId,
                url,
                name,
                avatarUrl,
                subscriberCount,
                description,
                NotificationMode.DISABLED
        );
    }

    boolean applyTo(@NonNull final SubscriptionEntity target) {
        boolean changed = false;
        if (!TextUtils.equals(target.getName(), name) && !TextUtils.isEmpty(name)) {
            target.setName(name);
            changed = true;
        }
        if (!TextUtils.equals(target.getAvatarUrl(), avatarUrl)
                && !TextUtils.isEmpty(avatarUrl)) {
            target.setAvatarUrl(avatarUrl);
            changed = true;
        }
        if (!TextUtils.equals(target.getDescription(), description)
                && !TextUtils.isEmpty(description)) {
            target.setDescription(description);
            changed = true;
        }
        if (subscriberCount != null
                && (target.getSubscriberCount() == null
                || target.getSubscriberCount() < subscriberCount)) {
            target.setSubscriberCount(subscriberCount);
            changed = true;
        }
        return changed;
    }

    @NonNull
    private SubscriptionRecord applyGroups(@Nullable final JSONArray groupsArray) {
        if (groupsArray == null) {
            return this;
        }
        for (int i = 0; i < groupsArray.length(); i++) {
            final JSONObject groupJson = groupsArray.optJSONObject(i);
            if (groupJson != null) {
                mergeGroup(GroupRecord.fromJson(groupJson));
            } else {
                final String groupName = groupsArray.optString(i, null);
                if (!TextUtils.isEmpty(groupName)) {
                    mergeGroup(new GroupRecord(groupName, FeedGroupIcon.ALL.getId()));
                }
            }
        }
        return this;
    }

    void mergeGroupsFrom(@NonNull final SubscriptionRecord incoming) {
        for (final GroupRecord group : incoming.groups.values()) {
            mergeGroup(group);
        }
    }

    void mergeGroup(@Nullable final GroupRecord group) {
        if (group == null || TextUtils.isEmpty(group.name)) {
            return;
        }

        final GroupRecord existing = groups.get(group.name);
        if (existing == null) {
            groups.put(group.name, new GroupRecord(group.name, group.iconId));
            return;
        }
        if (existing.iconId == FeedGroupIcon.ALL.getId()
                && group.iconId != FeedGroupIcon.ALL.getId()) {
            existing.iconId = group.iconId;
        }
    }
}
