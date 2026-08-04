package com.knowledgemeltingpot.workbench.application.port;

import com.knowledgemeltingpot.workbench.domain.UserNotification;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface NotificationRepository {
    boolean insert(UserNotification notification);

    List<UserNotification> findForUser(UUID userId, boolean unreadOnly, int limit);

    int unreadCount(UUID userId);

    boolean markRead(UUID notificationId, UUID userId, Instant readAt);

    int markAllRead(UUID userId, Instant readAt);
}
