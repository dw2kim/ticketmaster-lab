package lab.ticketmaster;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling // step 6: the waiting room admits people every second
public class TicketmasterApplication {

    public static void main(String[] args) {
        SpringApplication.run(TicketmasterApplication.class, args);
    }
}
