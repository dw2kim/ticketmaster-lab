package lab.ticketmaster.search;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Step 9: talk to Elasticsearch over plain HTTP + JSON, so every request is visible (like the
 * SQL elsewhere in this lab). A real app might use the Elasticsearch Java client instead.
 * Short timeouts: if Elasticsearch is down, search falls back to Postgres quickly.
 */
@Configuration
public class ElasticClient {

    @Bean
    RestClient elastic(@Value("${search.elasticsearch.url}") String url) {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(5));
        // RestClient.builder() finds Jackson 3 by itself for JSON bodies (Spring Boot 4 only
        // provides a RestClient.Builder bean with the extra spring-boot-restclient module).
        return RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }
}
