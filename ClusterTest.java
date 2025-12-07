// Cluster Connection Test - 독립 실행 테스트
// 실행: java --source 21 ClusterTest.java (클래스패스 필요)

import org.fluxgate.redis.config.RedisRateLimiterConfig;
import org.fluxgate.redis.connection.RedisConnectionProvider;
import org.fluxgate.redis.connection.RedisConnectionProvider.RedisMode;

public class ClusterTest {
    public static void main(String[] args) throws Exception {
        // Cluster URI (쉼표로 구분된 여러 노드)
        String clusterUri = "redis://127.0.0.1:7001,redis://127.0.0.1:7002,redis://127.0.0.1:7003";

        System.out.println("=== FluxGate Redis Cluster Connection Test ===");
        System.out.println("Connecting to: " + clusterUri);
        System.out.println();

        try (RedisRateLimiterConfig config = new RedisRateLimiterConfig(clusterUri)) {
            RedisConnectionProvider provider = config.getConnectionProvider();

            System.out.println("Mode: " + provider.getMode());
            System.out.println("Connected: " + provider.isConnected());
            System.out.println("Ping: " + provider.ping());
            System.out.println();

            // Cluster nodes 확인
            if (provider.getMode() == RedisMode.CLUSTER) {
                System.out.println("Cluster Nodes:");
                for (String node : provider.clusterNodes()) {
                    System.out.println("  " + node);
                }
            }

            System.out.println();
            System.out.println("=== SUCCESS: Cluster connection works! ===");
        }
    }
}
