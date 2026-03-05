package org.schabi.newpipe.local.nostr;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;
import org.schabi.newpipe.local.subscription.FeedGroupIcon;

final class GroupRecord {
    final String name;
    int iconId;

    GroupRecord(@NonNull final String name, final int iconId) {
        this.name = name;
        this.iconId = iconId;
    }

    @NonNull
    static GroupRecord fromJson(@NonNull final JSONObject json) {
        final String name = json.optString("name", "");
        final int iconId = json.optInt("icon_id", FeedGroupIcon.ALL.getId());
        return new GroupRecord(name, iconId);
    }

    @NonNull
    JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("name", name)
                .put("icon_id", iconId);
    }
}
