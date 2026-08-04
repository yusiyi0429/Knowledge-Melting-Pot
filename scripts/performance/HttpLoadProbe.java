import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

/** JDK-only bounded HTTP load probe for the local Compose acceptance gate. */
public final class HttpLoadProbe {
    private HttpLoadProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 5) {
            throw new IllegalArgumentException(
                    "Usage: HttpLoadProbe <url> [requests] [concurrency] [maxP95Ms] [maxErrorRate]");
        }
        URI target = URI.create(args[0]);
        if (!(target.getScheme().equals("http") || target.getScheme().equals("https"))) {
            throw new IllegalArgumentException("Only HTTP(S) targets are supported");
        }
        int requests = integer(args, 1, 300, 1, 10_000, "requests");
        int concurrency = integer(args, 2, 16, 1, 256, "concurrency");
        long maxP95Ms = integer(args, 3, 1_500, 1, 60_000, "maxP95Ms");
        double maxErrorRate = decimal(args, 4, 0.0, 0.0, 1.0, "maxErrorRate");

        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder()
                .cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        HttpRequest request = HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json")
                .GET()
                .build();

        for (int warmup = 0; warmup < Math.min(20, requests); warmup++) {
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("Warmup returned HTTP " + response.statusCode());
            }
        }

        List<Callable<Sample>> tasks = new ArrayList<>(requests);
        for (int index = 0; index < requests; index++) {
            tasks.add(() -> execute(client, request));
        }
        long started = System.nanoTime();
        List<Sample> samples = new ArrayList<>(requests);
        try (var executor = Executors.newFixedThreadPool(concurrency)) {
            for (var result : executor.invokeAll(tasks)) {
                samples.add(result.get());
            }
        }
        long elapsedNanos = System.nanoTime() - started;

        List<Long> latencies = samples.stream().map(Sample::latencyMs).sorted().toList();
        long failures = samples.stream().filter(sample -> !sample.succeeded()).count();
        double errorRate = failures / (double) requests;
        long p50 = percentile(latencies, 0.50);
        long p95 = percentile(latencies, 0.95);
        long p99 = percentile(latencies, 0.99);
        double throughput = requests / (elapsedNanos / 1_000_000_000.0);

        System.out.printf(Locale.ROOT,
                "PERFORMANCE_RESULT requests=%d concurrency=%d failures=%d error_rate=%.4f p50_ms=%d p95_ms=%d p99_ms=%d throughput_rps=%.2f%n",
                requests, concurrency, failures, errorRate, p50, p95, p99, throughput);
        if (errorRate > maxErrorRate || p95 > maxP95Ms) {
            System.err.printf(Locale.ROOT,
                    "PERFORMANCE_BUDGET_FAILED max_error_rate=%.4f max_p95_ms=%d%n",
                    maxErrorRate, maxP95Ms);
            System.exit(1);
        }
    }

    private static Sample execute(HttpClient client, HttpRequest request) {
        long started = System.nanoTime();
        try {
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            return new Sample(Duration.ofNanos(System.nanoTime() - started).toMillis(), response.statusCode() == 200);
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return new Sample(Duration.ofNanos(System.nanoTime() - started).toMillis(), false);
        }
    }

    private static long percentile(List<Long> sorted, double percentile) {
        int index = Math.max(0, (int) Math.ceil(sorted.size() * percentile) - 1);
        return sorted.get(index);
    }

    private static int integer(String[] args, int index, int fallback, int minimum, int maximum, String label) {
        int value = args.length > index ? Integer.parseInt(args[index]) : fallback;
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(label + " is outside the supported range");
        }
        return value;
    }

    private static double decimal(String[] args, int index, double fallback,
            double minimum, double maximum, String label) {
        double value = args.length > index ? Double.parseDouble(args[index]) : fallback;
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException(label + " is outside the supported range");
        }
        return value;
    }

    private record Sample(long latencyMs, boolean succeeded) {
    }
}
