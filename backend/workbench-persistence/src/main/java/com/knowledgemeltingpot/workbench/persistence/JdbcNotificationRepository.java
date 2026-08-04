package com.knowledgemeltingpot.workbench.persistence;

import com.knowledgemeltingpot.workbench.application.port.NotificationRepository;
import com.knowledgemeltingpot.workbench.domain.UserNotification;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcNotificationRepository implements NotificationRepository {
    private final JdbcClient jdbc;

    public JdbcNotificationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean insert(UserNotification notification) {
        return jdbc.sql("""
                INSERT INTO user_notification (
                    id, user_id, notification_type, title, message, resource_type, resource_id, created_at, read_at)
                VALUES (:id, :userId, :type, :title, :message, :resourceType, :resourceId, :createdAt, :readAt)
                ON CONFLICT (user_id, notification_type, resource_type, resource_id) DO NOTHING
                """).param("id", notification.id()).param("userId", notification.userId())
                .param("type", notification.type()).param("title", notification.title())
                .param("message", notification.message()).param("resourceType", notification.resourceType())
                .param("resourceId", notification.resourceId()).param("createdAt", JdbcTimes.toJdbc(notification.createdAt()))
                .param("readAt", notification.readAt() == null ? null : JdbcTimes.toJdbc(notification.readAt()))
                .update() == 1;
    }

    @Override
    public List<UserNotification> findForUser(UUID userId, boolean unreadOnly, int limit) {
        return jdbc.sql("""
                SELECT id, user_id, notification_type, title, message, resource_type, resource_id, created_at, read_at
                FROM user_notification
                WHERE user_id = :userId AND (:unreadOnly = FALSE OR read_at IS NULL)
                ORDER BY created_at DESC, id LIMIT :limit
                """).param("userId", userId).param("unreadOnly", unreadOnly).param("limit", limit)
                .query(JdbcNotificationRepository::map).list();
    }

    @Override
    public int unreadCount(UUID userId) {
        Integer count = jdbc.sql("SELECT COUNT(*) FROM user_notification WHERE user_id = :userId AND read_at IS NULL")
                .param("userId", userId).query(Integer.class).single();
        return count == null ? 0 : count;
    }

    @Override
    public boolean markRead(UUID notificationId, UUID userId, Instant readAt) {
        return jdbc.sql("""
                UPDATE user_notification SET read_at = COALESCE(read_at, :readAt)
                WHERE id = :id AND user_id = :userId
                """).param("id", notificationId).param("userId", userId)
                .param("readAt", JdbcTimes.toJdbc(readAt)).update() == 1;
    }

    @Override
    public int markAllRead(UUID userId, Instant readAt) {
        return jdbc.sql("UPDATE user_notification SET read_at = :readAt WHERE user_id = :userId AND read_at IS NULL")
                .param("userId", userId).param("readAt", JdbcTimes.toJdbc(readAt)).update();
    }

    private static UserNotification map(ResultSet rs, int row) throws SQLException {
        var readAt = rs.getTimestamp("read_at");
        return new UserNotification(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getString("notification_type"), rs.getString("title"), rs.getString("message"),
                rs.getString("resource_type"), rs.getObject("resource_id", UUID.class),
                rs.getTimestamp("created_at").toInstant(), readAt == null ? null : readAt.toInstant());
    }
}
