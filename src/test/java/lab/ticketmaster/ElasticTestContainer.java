package lab.ticketmaster;

import java.time.Duration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/** One real Elasticsearch for all tests that need it (it takes ~20 s and ~1 GB to start). */
final class ElasticTestContainer {

    static final GenericContainer<?> ELASTIC = new GenericContainer<>("elasticsearch:9.1.5")
            .withEnv("discovery.type", "single-node")
            .withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withExposedPorts(9200)
            .waitingFor(Wait.forHttp("/").forStatusCode(200).withStartupTimeout(Duration.ofMinutes(3)));

    static {
        ELASTIC.start();
    }

    static void register(DynamicPropertyRegistry registry) {
        registry.add("search.elasticsearch.url",
                () -> "http://" + ELASTIC.getHost() + ":" + ELASTIC.getMappedPort(9200));
    }

    private ElasticTestContainer() {}
}
