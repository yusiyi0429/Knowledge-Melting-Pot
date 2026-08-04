package com.knowledgemeltingpot.workbench.application.service;

import com.knowledgemeltingpot.workbench.application.error.NotFoundException;
import com.knowledgemeltingpot.workbench.application.port.JobRepository;
import com.knowledgemeltingpot.workbench.application.port.NotificationRepository;
import com.knowledgemeltingpot.workbench.domain.Job;
import com.knowledgemeltingpot.workbench.domain.JobStatus;
import com.knowledgemeltingpot.workbench.domain.UserNotification;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationService {
    private static final Map<String, String> JOB_LABELS = Map.ofEntries(
            Map.entry("INGEST", "素材处理"), Map.entry("EXTRACT", "知识萃取"),
            Map.entry("REEXTRACT", "重新萃取"), Map.entry("ALIGN", "知识对齐"),
            Map.entry("GENERATE_ASSET", "资产生成"), Map.entry("GENERATE_ALL", "全部资产生成"),
            Map.entry("SCENE_EXPLORE", "场景探索"), Map.entry("EVALUATE", "评测"));
    private final NotificationRepository notifications;
    private final JobRepository jobs;
    private final Clock clock;

    public NotificationService(NotificationRepository notifications, JobRepository jobs, Clock clock) {
        this.notifications = notifications;
        this.jobs = jobs;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Inbox inbox(UUID userId, boolean unreadOnly, int limit) {
        int bounded = Math.min(Math.max(limit, 1), 100);
        return new Inbox(notifications.unreadCount(userId), notifications.findForUser(userId, unreadOnly, bounded));
    }

    @Transactional
    public void markRead(UUID notificationId, UUID userId) {
        if (!notifications.markRead(notificationId, userId, Instant.now(clock))) {
            throw new NotFoundException("notification does not exist");
        }
    }

    @Transactional
    public int markAllRead(UUID userId) {
        return notifications.markAllRead(userId, Instant.now(clock));
    }

    @Transactional
    public void notifyTerminalJob(UUID jobId) {
        Job job = jobs.find(jobId).orElse(null);
        if (job == null || !job.status().terminal()) return;
        String type = "JOB_" + job.status().name();
        String label = JOB_LABELS.getOrDefault(job.type().name(), "后台任务");
        String title = label + switch (job.status()) {
            case SUCCEEDED -> "已完成";
            case FAILED -> "未完成";
            case CANCELLED -> "已取消";
            default -> "状态已更新";
        };
        String message = job.status() == JobStatus.FAILED && !job.errorCode().isBlank()
                ? "错误代码：" + job.errorCode() : "可打开任务查看详细状态。";
        notifications.insert(new UserNotification(UUID.randomUUID(), job.requestedBy(), type, title, message,
                "JOB", job.id(), Instant.now(clock), null));
    }

    public record Inbox(int unreadCount, List<UserNotification> items) {
        public Inbox { items = List.copyOf(items); }
    }
}
