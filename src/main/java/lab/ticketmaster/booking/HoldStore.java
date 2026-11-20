package lab.ticketmaster.booking;

import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Picks where holds live, from booking.hold-store (postgres | redis). */
@Component
public class HoldStore {

    private final Holds current;

    public HoldStore(Map<String, Holds> all, @Value("${booking.hold-store}") String store) {
        this.current = all.get(store);
        if (current == null) {
            throw new IllegalArgumentException("booking.hold-store=" + store + " is not one of " + all.keySet());
        }
    }

    public Holds current() {
        return current;
    }
}
