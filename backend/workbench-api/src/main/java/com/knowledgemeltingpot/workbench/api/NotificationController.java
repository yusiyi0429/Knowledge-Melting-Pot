package com.knowledgemeltingpot.workbench.api;

import com.knowledgemeltingpot.workbench.api.security.CurrentUser;
import com.knowledgemeltingpot.workbench.application.service.NotificationService;
import com.knowledgemeltingpot.workbench.domain.UserNotification;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final NotificationService notifications;
    private final CurrentUser currentUser;

    public NotificationController(NotificationService notifications, CurrentUser currentUser) {
        this.notifications = notifications;
        this.currentUser = currentUser;
    }

    @GetMapping
    public InboxResponse inbox(@RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "30") int limit, Authentication authentication) {
        NotificationService.Inbox inbox = notifications.inbox(currentUser.id(authentication), unreadOnly, limit);
        return new InboxResponse(inbox.unreadCount(), inbox.items().stream().map(NotificationResponse::from).toList());
    }

    @PatchMapping("/{notificationId}/read")
    public ResponseEntity<Void> read(@PathVariable UUID notificationId, Authentication authentication) {
        notifications.markRead(notificationId, currentUser.id(authentication));
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/read")
    public MarkAllResponse readAll(Authentication authentication) {
        return new MarkAllResponse(notifications.markAllRead(currentUser.id(authentication)));
    }

    public record InboxResponse(int unreadCount, List<NotificationResponse> items) { }

    public record NotificationResponse(UUID id, String type, String title, String message,
            String resourceType, UUID resourceId, Instant createdAt, Instant readAt, boolean read) {
        static NotificationResponse from(UserNotification value) {
            return new NotificationResponse(value.id(), value.type(), value.title(), value.message(),
                    value.resourceType(), value.resourceId(), value.createdAt(), value.readAt(), value.read());
        }
    }

    public record MarkAllResponse(int updated) { }
}
